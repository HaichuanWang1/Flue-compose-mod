-- wf_render.lua — Watchmaker (.watch / JBwatch) renderer for Axmol/Cocos2d-x.
--
-- Parses a watch.xml face definition (format 1.0 flat layers and 2.0 Studio
-- groups/themes) and builds live cocos nodes for it. See the format reference
-- in watchmaker-definition/ (WM_FORMAT_1.0.md, WM_FORMAT_2.0.md, WM_TAGS.md).
--
-- Update model (the import-time indexing the engine is built around):
--   • at LOAD, every dynamic attribute is compiled to a Lua function
--     (wf_expr) and wrapped in a binding (wf_binding) that is indexed into
--     the update bucket of every {tag} it reads (wf_engine);
--   • at RUNTIME, the scene fires events (ms / second / minute / hour / day /
--     bridge pushes); each event recomputes only its active tags and changed
--     tags run their buckets. No XML, no gsub, no re-classification — just
--     compiled chunks and table lookups.
-- The face's global script (scripts/script.txt) loads BEFORE the layers so
-- its variables resolve inside attribute expressions (wf_script, wf_sandbox).
--
-- Coordinate model: the watch canvas is 512×512, origin at the centre.
-- Authored layer positions are screen-down (positive Y = toward the bottom);
-- the renderer flips them into cocos Y-up at the single `Y_SIGN` site below.
-- The caller supplies a `stage` cc.Node already positioned at screen centre
-- and scaled so 512 units fill the watch — this module works purely in canvas
-- units and never touches screen pixels.
--
-- Layout per layer:  holder (carries x/y, rotation, anim_scale, opacity, skew)
--                    └─ visual (the sprite/label/draw-node, base-scaled to fit)
-- Groups (2.0) are holders with no visual; children attach to them, so nested
-- transforms fall out of the cocos scene graph for free.

local WFExpr    = require "wf_expr"
local WFEngine  = require "wf_engine"
local WFStopwatch = require "wf_stopwatch"
local WFSandbox = require "wf_sandbox"
local WFScript  = require "wf_script"
local WFProtect = require "wf_protect"
local B         = require "wf_binding"
local WFBinding, WFSetterBinding, WFCompositeBinding, WFColor =
    B.WFBinding, B.WFSetterBinding, B.WFCompositeBinding, B.WFColor

local WFRender = {}
WFRender.__index = WFRender

local floor, rad, cos, sin = math.floor, math.rad, math.cos, math.sin
local fu = cc.FileUtils:getInstance()
local function fileExists(p) return fu:isFileExist(p) end

-- On-screen Y sign for authored layer POSITIONS. WM_FORMAT_1.0 §4 documents
-- Y-up (+1), but real faces render vertically inverted on device — content the
-- designer placed at the bottom lands at the top — i.e. the format's authored Y
-- is screen-down. So a layer's authored (x, y) becomes cocos (x, Y_SIGN*y). This
-- is the ONE place format coordinates enter the cocos scene; flip to +1 to revert.
--
-- Only holder positions are negated. Per-layer geometry (markers, rings, hands,
-- text arcs) is procedural and already authored in cocos Y-up (12 o'clock = +Y),
-- so it is NOT touched — negating it would send 12 o'clock to the bottom and make
-- hands point down. Visuals are never mirrored, so glyphs/sprites stay upright.
local Y_SIGN = -1
local function num(v, d) return tonumber(v) or d or 0 end
local function clamp(v, lo, hi) if v < lo then return lo elseif v > hi then return hi else return v end end

-- WatchMaker's `text_size` is authored as a canvas-unit box height, not a native
-- font point size. The reference renderers create labels at text_size * 1.28 (so
-- the visible glyph height lands near 1.6 * text_size in the 512 canvas). Without
-- this factor TTF text renders ~28% too small next to shapes authored in the same
-- canvas. Clamp near the 512 canvas / max font-texture size like the reference.
local TTF_FONT_SCALE = 1.28
local function fontPx(size) return math.min(492, floor(size * TTF_FONT_SCALE)) end

-- Encode a single Unicode codepoint to UTF-8 (Lua 5.1 has no utf8.char). Only
-- the <=0xFFFF range is needed here — Material Icons live in the Private-Use
-- Area (0xE000-0xF8FF), always 3 bytes.
local function utf8encode(cp)
    if cp < 0x80 then return string.char(cp) end
    if cp < 0x800 then
        return string.char(0xC0 + floor(cp / 0x40), 0x80 + cp % 0x40)
    end
    return string.char(0xE0 + floor(cp / 0x1000),
                       0x80 + floor(cp / 0x40) % 0x40,
                       0x80 + cp % 0x40)
end

-- Material Icons (and its Outlined/Round/Sharp/TwoTone siblings) select glyphs
-- through OpenType ligatures: the string "favorite" is shaped into one Private-
-- Use glyph (U+E87D). Axmol's FreeType label does NO ligature shaping, and the
-- font's component letters (f,a,v,...) are BLANK glyphs, so an un-substituted
-- icon name renders as nothing (the empty gap on such faces). Translate icon
-- names to their PUA codepoint before building the label. The name->codepoint
-- table is canonical/stable across the classic variants and lazy-loaded once.
local function isMaterialIconsFont(font) return font:sub(1, 13) == "MaterialIcons" end
local materialMap
-- Resolve a label string for a Material-Icons layer. Almost every icon layer is
-- a single name (or an expression evaluating to one), so a whole-string lookup
-- is the fast path — no Lua pattern matching per update (see the no-regex rule).
-- `cache` memoises per layer since an icon usually cycles a handful of values.
local function materialSubst(str, cache)
    if not str or str == "" then return str end
    local hit = cache[str]
    if hit ~= nil then return hit end
    materialMap = materialMap or require("wf_material_icons")
    local out
    local cp = materialMap[str]
    if cp then
        out = utf8encode(cp)                          -- whole string is one icon
    elseif str:find(" ", 1, true) then               -- plain find, not a pattern
        -- space-separated names: byte-walk split, translate each known token
        local parts, start, n = {}, 1, #str
        for i = 1, n + 1 do
            if i > n or str:byte(i) == 32 then
                local tok = str:sub(start, i - 1)
                local tcp = tok ~= "" and materialMap[tok]
                parts[#parts + 1] = tcp and utf8encode(tcp) or tok
                start = i + 1
            end
        end
        out = table.concat(parts, " ")
    else
        out = str                                     -- not an icon name; leave as-is
    end
    cache[str] = out
    return out
end

-- Engine event names that are NOT bridge push categories (everything else
-- active in the engine gets a WatchfaceBridge.onChange listener).
local CLOCK_EVENTS = {
    ms = true, second = true, minute = true, hour = true, day = true,
    static = true, theme = true,
}

local c3b, c4b, c4f = WFColor.c3b, WFColor.c4b, WFColor.c4f

-- ── DrawNode fill helpers (only proven-available primitives) ──────────────────

local function fillQuad(dn, p1, p2, p3, p4, col)
    dn:drawTriangle(p1, p2, p3, col)
    dn:drawTriangle(p1, p3, p4, col)
end

-- Filled annular arc, swept from 12 o'clock. a0,a1 are sweep degrees (a1>a0).
-- `col` is a c4f, or a function(sweepMid)→c4f for gradient fills.
local function fillArc(dn, ri, ro, a0, a1, cw, col)
    if a1 <= a0 then return end
    local steps = math.max(1, floor((a1 - a0) / 3))
    local isFn = type(col) == "function"
    local function pt(sweep, r)
        -- 12 o'clock = +Y (90°); clockwise sweep decreases the polar angle
        local th = cw and rad(90 - sweep) or rad(90 + sweep)
        return cc.p(r * cos(th), r * sin(th))
    end
    for i = 0, steps - 1 do
        local s0 = a0 + (a1 - a0) * i / steps
        local s1 = a0 + (a1 - a0) * (i + 1) / steps
        local c = isFn and col((s0 + s1) / 2) or col
        fillQuad(dn, pt(s0, ri), pt(s0, ro), pt(s1, ro), pt(s1, ri), c)
    end
end

-- Rounded end cap for a ring arc: a disc centred on the arc centreline at
-- `sweep` degrees (same polar convention as fillArc).
local function ringCap(dn, rc, capR, sweep, cw, col)
    local th = cw and rad(90 - sweep) or rad(90 + sweep)
    dn:drawSolidCircle(cc.p(rc * cos(th), rc * sin(th)), capR, 0, 24, 1, 1, col)
end

-- Interpolated fill colour between two WFColor.rgb tables (alpha 1: layer
-- opacity is applied ONCE via the holder — DrawNode multiplies node opacity
-- into vertex alpha, so baking it here would square it).
local function lerpRGB(ca, cb, t)
    return cc.c4f((ca.r + (cb.r - ca.r) * t) / 255,
                  (ca.g + (cb.g - ca.g) * t) / 255,
                  (ca.b + (cb.b - ca.b) * t) / 255, 1)
end

-- corner_type → which corners round: {TL,TR,BL,BR} (WM_FORMAT_1.0 §7.12;
-- shared by `rounded` and `progress`, same table as the UL Rounded shader).
local CORNERS = {
    [0]={0,0,0,0},[1]={1,1,1,1},[2]={1,1,1,0},[3]={1,1,0,1},[4]={1,0,1,1},
    [5]={0,1,1,1},[6]={1,1,0,0},[7]={0,0,1,1},[8]={1,0,1,0},[9]={0,1,0,1},
    [10]={1,0,0,1},[11]={0,1,1,0},[12]={1,0,0,0},[13]={0,1,0,0},
    [14]={0,0,1,0},[15]={0,0,0,1},[16]={0,0,0,0},
}

-- Quarter-circle fan around a corner centre (radians a0..a1).
local function cornerFan(dn, cx, cy, r, a0, a1, col)
    local steps = math.max(2, floor((a1 - a0) / rad(15)))
    local prev
    for i = 0, steps do
        local a = a0 + (a1 - a0) * i / steps
        local p = cc.p(cx + r * cos(a), cy + r * sin(a))
        if prev then dn:drawTriangle(cc.p(cx, cy), prev, p, col) end
        prev = p
    end
end

-- Solid rect x0..x1 × y0..y1 with per-corner radii rr = {tl, tr, bl, br}
-- (0 = sharp). Drawn as a middle band + top/bottom bands with corner fans.
local function fillRoundedRect(dn, x0, y0, x1, y1, rr, col)
    if x1 <= x0 or y1 <= y0 then return end
    local tl, tr, bl, br = rr[1] or 0, rr[2] or 0, rr[3] or 0, rr[4] or 0
    local top, bot = math.max(tl, tr), math.max(bl, br)
    if y1 - top > y0 + bot then
        dn:drawSolidRect(cc.p(x0, y0 + bot), cc.p(x1, y1 - top), col)
    end
    if top > 0 then
        if x1 - tr > x0 + tl then
            dn:drawSolidRect(cc.p(x0 + tl, y1 - top), cc.p(x1 - tr, y1), col)
        end
        if tl > 0 then
            cornerFan(dn, x0 + tl, y1 - tl, tl, rad(90), rad(180), col)
            if top > tl then dn:drawSolidRect(cc.p(x0, y1 - top), cc.p(x0 + tl, y1 - tl), col) end
        end
        if tr > 0 then
            cornerFan(dn, x1 - tr, y1 - tr, tr, rad(0), rad(90), col)
            if top > tr then dn:drawSolidRect(cc.p(x1 - tr, y1 - top), cc.p(x1, y1 - tr), col) end
        end
    end
    if bot > 0 then
        if x1 - br > x0 + bl then
            dn:drawSolidRect(cc.p(x0 + bl, y0), cc.p(x1 - br, y0 + bot), col)
        end
        if bl > 0 then
            cornerFan(dn, x0 + bl, y0 + bl, bl, rad(180), rad(270), col)
            if bot > bl then dn:drawSolidRect(cc.p(x0, y0 + bl), cc.p(x0 + bl, y0 + bot), col) end
        end
        if br > 0 then
            cornerFan(dn, x1 - br, y0 + br, br, rad(270), rad(360), col)
            if bot > br then dn:drawSolidRect(cc.p(x1 - br, y0 + br), cc.p(x1, y0 + bot), col) end
        end
    end
end

-- Axis-aligned gradient body drawn as flat strips; colAt(t) samples the
-- gradient, t0..t1 is the parameter range this span covers.
local function gradStrips(dn, x0, y0, x1, y1, vertical, colAt, t0, t1)
    if x1 <= x0 or y1 <= y0 then return end
    local N = 16
    if vertical then
        local hh = y1 - y0
        for i = 0, N - 1 do
            dn:drawSolidRect(cc.p(x0, y0 + hh * i / N), cc.p(x1, y0 + hh * (i + 1) / N),
                colAt(t0 + (t1 - t0) * (i + 0.5) / N))
        end
    else
        local ww = x1 - x0
        for i = 0, N - 1 do
            dn:drawSolidRect(cc.p(x0 + ww * i / N, y0), cc.p(x0 + ww * (i + 1) / N, y1),
                colAt(t0 + (t1 - t0) * (i + 0.5) / N))
        end
    end
end

-- Dim-mode colour pick: UL swaps color→color_dim for EVERY layer type, not
-- just text.
local function colAttr(rec)
    return (rec.renderer.dim and rec.L.color_dim) and "color_dim" or "color"
end

-- UL sizing quirk: when the texture is non-square but the authored box is
-- square, BOTH axes scale by the width ratio (texture aspect preserved instead
-- of squashing into the box) — WatchSkin's `tSizeOrig.height = width` fixup.
local function setBaseScale(rec, texW, texH, W, H)
    if texW <= 0 or texH <= 0 then return end
    if texW ~= texH and W == H then
        local s = W / texW
        rec.bsx, rec.bsy = s, s
    else
        rec.bsx, rec.bsy = W / texW, H / texH
    end
end

-- NOTE on text sizing: UL scales labels to contentHeight == 1.6 × text_size,
-- but that rule does NOT transfer to axmol — axmol's Label reports different
-- content-height metrics than UL's cocos2d-x 3.x, so applying it here made
-- native faces render text visibly too large (and metric-heavy fonts too
-- small). Confirmed on device 2026-07-07; TTF labels stay at fontPx(text_size)
-- unscaled, bitmap fonts scale per-line (both device-validated earlier).

-- Shape outlines traced from the UL reference mask textures (shape_*.png,
-- 512×512, alpha-sampled + Douglas-Peucker). Coordinates are BOX units
-- (x right, y up, ±0.5 = the layer's width/height box). The masks carry
-- internal padding — e.g. the heart spans y 4%..96% and the hexagon is a
-- regular hexagon at 86.6% of the box height — so idealized full-bleed
-- polygons never lined up with the reference rendering. Each polygon is
-- star-shaped from its centroid (cx,cy), so a triangle fan fills it.
local SHAPE_PTS = {
    triangle = { cx = 0, cy = -0.1442, pts = {
        -0.4973,-0.4313,  0.4973,-0.4313,  0,0.4222,
    } },
    pentagon = { cx = 0, cy = -0.0483, pts = {
        0,0.4673,  0.4922,0.1116,  0.3054,-0.4686,  -0.3054,-0.4686,  -0.4922,0.1116,
    } },
    hexagon = { cx = 0, cy = 0, pts = {
        0.5,0,  0.25,-0.433,  -0.25,-0.433,  -0.5,0,  -0.25,0.433,  0.25,0.433,
    } },
    star = { cx = 0, cy = -0.0486, pts = {
        0,0.4710,  0.1506,0.1607,  0.4941,0.1120,  0.2464,-0.1310,  0.3024,-0.4686,
        0,-0.3102,  -0.3024,-0.4686,  -0.2464,-0.1310,  -0.4941,0.1120,  -0.1506,0.1607,
    } },
    heart = { cx = -0.0010, cy = 0.0791, pts = {
        0.4873,0.0791, 0.4490,-0.0207, 0.3884,-0.1025, -0.0010,-0.4521, -0.3920,-0.0991, -0.4588,-0.0016,
        -0.4854,0.0791, -0.4995,0.1760, -0.4913,0.2624, -0.4530,0.3507, -0.3784,0.4189, -0.3188,0.4447,
        -0.2511,0.4570, -0.1679,0.4452, -0.1293,0.4315, -0.0573,0.3825, 0.0010,0.3096, 0.0545,0.3787,
        0.1142,0.4236, 0.1837,0.4497, 0.2491,0.4570, 0.3525,0.4327, 0.3997,0.4036, 0.4462,0.3586,
        0.4954,0.2452, 0.4980,0.1492,
    } },
}

-- Fan-fill a traced outline scaled to a w×h box.
local function fillShapePts(dn, w, h, def, col)
    local pts = def.pts
    local n = #pts / 2
    local c = cc.p(def.cx * w, def.cy * h)
    local prev
    for i = 0, n do
        local k = (i % n) * 2
        local p = cc.p(pts[k + 1] * w, pts[k + 2] * h)
        if prev then dn:drawTriangle(c, prev, p, col) end
        prev = p
    end
end

-- squarify morph: stretch the placement radius toward a square outline
local function squarifyRadius(r, angle, sq)
    if sq <= 0 then return r end
    local f = sq / 100
    local c, s = math.abs(cos(angle)), math.abs(sin(angle))
    local m = math.max(c, s)
    if m < 1e-4 then return r end
    return r * ((1 - f) + f / m)
end

-- ── WFLayerRec — one layer's record: node handles + compiled attributes ──────
-- The rec owns the per-attribute compiled descriptors and offers the eval/bind
-- helpers builders use. All compilation happens through here, once per attr.

local WFLayerRec = {}
WFLayerRec.__index = WFLayerRec

function WFLayerRec.new(renderer, L, holder, key, index)
    return setmetatable({
        renderer = renderer,
        engine   = renderer.engine,
        sandbox  = renderer.sandbox,
        L        = L,
        holder   = holder,
        key      = key,
        index    = index,
        type     = L.type,
        bsx = 1, bsy = 1,
        _descs   = {},
        _dispVis = true,   -- display/dim gate (visibleIn)
        _opVis   = true,   -- opacity gate (false once opacity hits 0)
    }, WFLayerRec)
end

-- A layer is a tap zone when it declares a tap_action or carries image_tap
-- frames. Tap zones must stay in the render tree even at opacity 0 so the
-- hit-test still finds them (chainVisible) — opacity-0 hot spots are tappable
-- by design; see tapEligible.
function WFLayerRec:isTapZone()
    return (self.L.tap_action and self.L.tap_action ~= "") or self.tap ~= nil
end

-- Holder visibility = display/dim gate AND opacity gate. Culling an off-page
-- (opacity 0) holder with setVisible(false) skips its whole subtree in
-- visit/transform/draw; without it a multi-screen face pays full draw calls for
-- every hidden page every frame — setOpacity(0) alone still renders the node
-- (the GPU just blends it invisibly). Tap zones are exempt from the opacity
-- gate so opacity-0 hot spots stay tappable.
function WFLayerRec:applyVis()
    local vis = self._dispVis ~= false
    if vis and self._opVis == false and not self:isTapZone() then vis = false end
    self.holder:setVisible(vis)
end

-- Compiled descriptor for an attribute (memoised; kind fixed per attr).
function WFLayerRec:desc(attr, kind)
    local d = self._descs[attr]
    if not d then
        d = WFExpr.compile(self.L[attr], kind, self.engine, self.sandbox)
        self._descs[attr] = d
    end
    return d
end

-- Pre-compile an attribute (composite builders declare their reads up front so
-- every dependency is indexed before the face starts running).
function WFLayerRec:use(attr, kind)
    self:desc(attr, kind)
end

-- Evaluate NOW (build time or inside a composite redraw).
function WFLayerRec:evalNum(attr, default)
    local d = self:desc(attr, "num")
    if d.const then
        if d.value == nil then return default end
        return d.value
    end
    return tonumber(d.fn(self.engine.V)) or default
end

function WFLayerRec:evalText(attr, default)
    local d = self:desc(attr, "text")
    if d.const then return d.value or default end
    local v = d.fn(self.engine.V)
    return v == nil and (default or "") or tostring(v)
end

function WFLayerRec:evalSub(attr, default)
    local d = self:desc(attr, "sub")
    if d.const then return d.value or default end
    local v = d.fn(self.engine.V)
    return v == nil and (default or "") or tostring(v)
end

-- Bind one numeric property: constants apply once at build; dynamic values
-- become a setter binding indexed into every dep tag's bucket.
function WFLayerRec:bindNum(attr, default, setter)
    local d = self:desc(attr, "num")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "num", default, setter), d.deps)
end

function WFLayerRec:bindText(attr, setter)
    local d = self:desc(attr, "text")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "text", "", setter), d.deps)
end

function WFLayerRec:bindSub(attr, setter)
    local d = self:desc(attr, "sub")
    if d.const then
        if d.value ~= nil then setter(self, d.value) end
        return
    end
    self.engine:bind(WFSetterBinding.new(self.engine, self, d, "text", "", setter), d.deps)
end

-- Composite: one redraw closure fired when ANY of the listed attributes' tags
-- change (attrs = { {name, kind}, ... }). A fully static composite draws once
-- and is never revisited.
function WFLayerRec:composite(redraw, attrs)
    local deps, seen = {}, {}
    for _, spec in ipairs(attrs) do
        local d = self:desc(spec[1], spec[2])
        if not d.const then
            for _, t in ipairs(d.deps) do
                if not seen[t] then seen[t] = true; deps[#deps + 1] = t end
            end
        end
    end
    if #deps == 0 then
        local ok, err = pcall(redraw, self)
        if not ok then print("[WFRender] static draw '" .. tostring(self.type) .. "': " .. tostring(err)) end
        return false
    end
    self.engine:bind(WFCompositeBinding.new(self, redraw), deps)
    return true
end

-- Composite whose output depends on renderer.dim (color vs color_dim pick).
-- Bound composites re-run through engine:repaint() on a dim flip, but a fully
-- static composite draws once and never re-fires — register those for an
-- explicit re-run in setDim().
function WFLayerRec:dimComposite(redraw, attrs)
    local bound = self:composite(redraw, attrs)
    if not bound and self.L.color_dim then
        local rdr = self.renderer
        rdr.dimRedraws[#rdr.dimRedraws + 1] = function()
            local ok, err = pcall(redraw, self)
            if not ok then print("[WFRender] dim redraw '" .. tostring(self.type) .. "': " .. tostring(err)) end
        end
    end
    return bound
end

-- Live-geometry convention (one rule for EVERY builder): composite() only
-- self-fires when fully static, so a genuinely dynamic redraw would otherwise
-- leave the record's defaults applied (bsx/bsy = 1 — the original wrong-size
-- bug) until its first dependency tag fires. These variants ALWAYS run the
-- redraw once at registration, so derived state (bsx/bsy, shapeW/H, hitW/H)
-- is correct the moment the builder returns — Pass 1 and bindEffects read it.
function WFLayerRec:compositeNow(redraw, attrs)
    if self:composite(redraw, attrs) then
        local ok, err = pcall(redraw, self)
        if not ok then print("[WFRender] initial draw '" .. tostring(self.type) .. "': " .. tostring(err)) end
    end
end

function WFLayerRec:dimCompositeNow(redraw, attrs)
    if self:dimComposite(redraw, attrs) then
        local ok, err = pcall(redraw, self)
        if not ok then print("[WFRender] initial draw '" .. tostring(self.type) .. "': " .. tostring(err)) end
    end
end

-- ── module ────────────────────────────────────────────────────────────────────

function WFRender.new(stage)
    local self = setmetatable({}, WFRender)
    self.stage  = stage
    self.recs   = {}          -- every layer record
    self.taps   = {}          -- tappable recs (tap_action / image_tap frames)
    self.dimRedraws = {}      -- static composites that must re-run on dim flip
    self.videos = {}          -- MediaPlayer widgets (stopped on dim/switch)
    self.retained = {}        -- retained off-graph nodes (stencil RT/labels)
    self._mapRecs = {}        -- map layers fed by the "map" bridge pushes
    self.dim    = false
    self.theme  = { ucolor = "ffffff", ucolor2 = "ffffff", ucolor3 = "ffffff" }
    self.bgColor = "000000"
    self.onNeedFrame = nil    -- scene hook: frame-loop need changed after load
    -- Baked-run RT captures must NOT run mid-update: an event handler firing
    -- later in the same scheduler pass can destroy/mutate nodes whose draw
    -- commands the capture already queued (calendar/text_curved rebuilds,
    -- label RT reallocs) → the renderer flushes commands on freed objects at
    -- end of frame (GLThread SIGSEGV). Instead, rebakes only mark themselves
    -- dirty; ONE listener on the director's after-update event (fires after
    -- every scheduled callback, before visit/render) performs each pending
    -- bake exactly once per frame against settled state.
    self._dirtyBakes = {}
    -- Android EGL context loss (app switch): VolatileTextureMgr reloads
    -- file-backed textures but RenderTexture CONTENTS are gone — every baked
    -- run / label RT / stencil mask shows garbage. Builders register their RT
    -- re-render closures in _ctxRestore; the recreated event flags a restore,
    -- performed at the next after-update pass (never mid-recreation).
    self._ctxRestore = {}
    self._needCtxRestore = false
    self:attachListeners()
    -- Deferred-release rows: see deferRelease().
    self._deathRow, self._deathGrave = {}, {}
    return self
end

-- Register the renderer-lifetime listeners. Idempotent, and re-run on EVERY
-- face load: if anything ever kills a listener (scene exit/re-enter, a future
-- dispatcher reset), the next load restores it instead of leaving bakes
-- permanently unserviced (black runs). The tick watchdog covers the window
-- in between.
-- NOTE: EventDispatcher:addCustomEventListener is an auto-binding whose
-- std::function arg ASSERTS from Lua — use the manual EventListenerCustom
-- binding + fixed-priority registration instead.
function WFRender:attachListeners()
    -- Unconditional remove+re-add: a director reset/restart wipes the
    -- dispatcher while the Lua fields still hold the orphaned listeners, so a
    -- presence check on self._* can NEVER detect the wipe (observed live: the
    -- watchdog draining every frame because after-update stopped arriving).
    -- removeEventListener is a safe no-op when the listener isn't registered.
    local disp = cc.Director:getInstance():getEventDispatcher()
    if self._bakeListener then disp:removeEventListener(self._bakeListener) end
    if self._ctxListener then disp:removeEventListener(self._ctxListener) end
    self._bakeListener = cc.EventListenerCustom:create("director_after_update",
        function()
            self._afterUpdateSeen = true   -- liveness beacon for the tick watchdog
            self:_serviceBakes()
        end)
    disp:addEventListenerWithFixedPriority(self._bakeListener, 1)
    self._afterUpdateSeen = true
    self._ctxListener = cc.EventListenerCustom:create("event_renderer_recreated",
        function() self._needCtxRestore = true end)
    disp:addEventListenerWithFixedPriority(self._ctxListener, 1)
end

-- Detach the after-update listener (activity teardown — a recreated scene
-- builds a fresh renderer; without this the old closure leaks and keeps firing).
function WFRender:shutdown()
    local disp = cc.Director:getInstance():getEventDispatcher()
    if self._bakeListener then
        disp:removeEventListener(self._bakeListener)
        self._bakeListener = nil
    end
    if self._ctxListener then
        disp:removeEventListener(self._ctxListener)
        self._ctxListener = nil
    end
end

-- Queue a run rebake for the end of the current update pass. Deduped: several
-- events (minute + var publishes + dim repaint) marking the same run in one
-- frame cost a single capture, taken after ALL of them applied.
function WFRender:markBakeDirty(bake)
    local d = self._dirtyBakes
    for i = 1, #d do if d[i] == bake then return end end
    d[#d + 1] = bake
end

function WFRender:_serviceBakes()
    if self._needCtxRestore then
        self._needCtxRestore = false
        -- Build order = members (label RTs, stencil masks) before flatten
        -- runs, so run captures see restored member content. Run entries just
        -- mark the dirty set, drained below in this same pass.
        for _, fn in ipairs(self._ctxRestore) do
            local ok, err = pcall(fn)
            if not ok then print("[WFRender] ctx restore error: " .. tostring(err)) end
        end
        print("[WFRender] context restore: " .. #self._ctxRestore .. " RT re-render(s)")
    end
    local d = self._dirtyBakes
    if #d == 0 then return end
    self._dirtyBakes = {}
    for _, bake in ipairs(d) do
        local ok, err = pcall(bake)
        if not ok then print("[WFRender] rebake error: " .. tostring(err)) end
    end
end

-- Release a render-graph object SAFELY. A RenderTexture bake enqueues commands
-- (onBegin/onEnd callbacks, the visited children's draw commands) that the
-- renderer only flushes at the END of the frame; releasing any such object
-- synchronously frees it before the flush → GLThread SIGSEGV. Two-phase row →
-- grave → release guarantees at least one full render flush between the last
-- possible queued reference and the free.
function WFRender:deferRelease(obj)
    self._deathRow[#self._deathRow + 1] = obj
    if self._deathTimer then return end
    local sched = cc.Director:getInstance():getScheduler()
    self._deathTimer = sched:scheduleScriptFunc(function() self:_pumpDeathRow() end, 0, false)
end

function WFRender:_pumpDeathRow()
    local grave = self._deathGrave
    self._deathGrave, self._deathRow = self._deathRow, {}
    for _, o in ipairs(grave) do pcall(function() o:release() end) end
    if #self._deathGrave == 0 and #self._deathRow == 0 and self._deathTimer then
        local sched = cc.Director:getInstance():getScheduler()
        sched:unscheduleScriptEntry(self._deathTimer)
        self._deathTimer = nil
    end
end

function WFRender:clear()
    -- Back up the outgoing face's script variables before its engine dies (face
    -- switch). The activity-destroy case is saved in WatchfaceScene:onExit.
    print("[WFPersist] clear() script=" .. tostring(self.script ~= nil))
    if self.script and self.script.saveVars then
        local sok, serr = pcall(function() self.script:saveVars() end)
        if not sok then print("[WFPersist] saveVars error: " .. tostring(serr)) end
    end
    -- Drop bridge listeners from the previous face, or stale closures would keep
    -- firing into a dead engine after a switch.
    if self._asyncHandlers and WatchfaceBridge and WatchfaceBridge.offChange then
        for bridgeCat, fn in pairs(self._asyncHandlers) do
            WatchfaceBridge.offChange(bridgeCat, fn)
        end
    end
    self._asyncHandlers = {}
    -- Release the previous face's audio device — a face switch must never
    -- leave the OpenAL mixer (and its wakelock) running.
    if self.script and self.script.releaseAudio then self.script:releaseAudio() end
    -- Stop video decoders BEFORE the nodes are released (MediaCodec +
    -- AudioTrack must never outlive the face — see the audio hygiene rule).
    for _, mp in ipairs(self.videos or {}) do pcall(function() mp:stop() end) end
    -- Off-graph nodes retained for stencil masks / baked layers die with the
    -- face — but a RenderTexture's begin() enqueues a CallbackCommand bound to
    -- `this` (onBegin) that the renderer only flushes at the END of the current
    -- frame. On a mid-frame face switch a rebake may have queued that command
    -- earlier this frame; releasing the RT synchronously here frees it before
    -- the flush, so the queued onBegin runs on a dangling RT → GLThread SIGSEGV
    -- in Director::getMatrix. deferRelease() holds them across a full render
    -- flush so every queued command runs on a still-live object first.
    for _, obj in ipairs(self.retained or {}) do self:deferRelease(obj) end
    -- Pending run rebakes belong to the outgoing face — they must never fire
    -- against its (now dying) RTs/groups after the switch.
    self._dirtyBakes = {}
    if self.stage then self.stage:removeAllChildren() end
    self._ctxRestore, self._needCtxRestore = {}, false
    self.recs, self.taps, self.dimRedraws = {}, {}, {}
    self.videos, self._mapRecs, self.retained = {}, {}, {}
    self._videosRunning = true   -- next build's players start playing (Builders.video)
    self._colorIdx = 0
    self.engine, self.sandbox, self.script = nil, nil, nil
    self.needFrame, self.needSecond = false, false
    WFBinding.errorReported = false
end

-- Resolve an image filename to a loadable absolute path. Protected faces ship
-- images as .ppng / .pjpg (XOR-obfuscated PNG / JPEG); decode them to a sibling
-- .png / .jpg on first use (cached) so cc.Sprite can load them.
function WFRender:imageFile(path)
    if not path or path == "" then return "" end
    local dir = self.base .. "images/"
    local ext = path:sub(-5):lower()
    if ext == ".ppng" then
        local png = dir .. path:sub(1, -6) .. ".png"
        if fileExists(png) or WFProtect.ensureDecoded(dir .. path, png) then return png end
    elseif ext == ".pjpg" then
        local jpg = dir .. path:sub(1, -6) .. ".jpg"
        if fileExists(jpg) or WFProtect.ensureDecoded(dir .. path, jpg) then return jpg end
    end
    local full = dir .. path
    if fileExists(full) then return full end
    -- Fall back to the FileUtils search paths (bundled assets) when the face's
    -- own images/ dir has no such file — lets the built-in default face load its
    -- background straight from assets (e.g. "wf_default/bg.png").
    if fileExists(path) then return path end
    return full
end

-- ── XML parsing ───────────────────────────────────────────────────────────────

-- Parse key="value" / key='value' attributes. Quote-agnostic: some exporters
-- emit single-quoted attributes, and a double-quote-only parser would drop every
-- attribute (so each layer loses its `type` and gets skipped → 0 layers).
local function parseAttrs(s)
    local a = {}
    for k, _, v in s:gmatch("([%w_]+)%s*=%s*([\"'])(.-)%2") do a[k] = v end
    return a
end

-- Returns watchAttrs, themeSets(list), layers(list of attr tables in doc order).
local function parseXml(xml)
    local watch = parseAttrs(xml:match("<Watch(.-)>") or "")
    local themes = {}
    for body in xml:gmatch("<ThemeSet(.-)>") do
        local a = parseAttrs(body)
        if a.ucolor then themes[#themes + 1] = a end
    end
    local layers = {}
    for body in xml:gmatch("<Layer(.-)>") do
        local a = parseAttrs(body)
        if a.type then layers[#layers + 1] = a end
    end
    return watch, themes, layers
end

-- alignment → cocos anchor point (used for the main visual and effect copies)
local ALIGN_AP = { cc=cc.p(.5,.5), tl=cc.p(0,1), tc=cc.p(.5,1), tr=cc.p(1,1),
                   cl=cc.p(0,.5), cr=cc.p(1,.5), bl=cc.p(0,0), bc=cc.p(.5,0), br=cc.p(1,0) }

-- ── visibility (display = b / d / bd) ─────────────────────────────────────────

local function visibleIn(display, dim)
    if not display or display == "" or display == "bd" then return true end
    if dim then return display:find("d") ~= nil else return display:find("b") ~= nil end
end

-- ── per-layer builders ────────────────────────────────────────────────────────
-- Each builder creates rec.visual (or leaves nil for groups), sets rec.bsx/bsy
-- (intrinsic base scale to fit width/height) and registers its dynamic-content
-- bindings through the rec helpers. Holder transform (x/y/rotation/scale/
-- opacity) is bound generically afterwards.

local Builders = {}

-- Sprite tint from `color` (+ dim-mode `color_dim` pick, like UL applies to
-- every image/shape layer).
local function bindTint(rec, sp)
    if rec.L.color_dim then
        rec:use("color", "sub"); rec:use("color_dim", "sub")
        rec:dimComposite(function(r)
            sp:setColor(c3b(r:evalSub(colAttr(r), "ffffff")))
        end, { { "color", "sub" }, { "color_dim", "sub" } })
    else
        rec:bindSub("color", function(_, v) sp:setColor(c3b(v)) end)
    end
end

-- ── layer effects (WM_EFFECTS.md §1–2): shadow / outline / glow ───────────────
-- Shared by the text and shape builders (the docs' support matrix). Effects are
-- offset copies of the layer's visual placed behind it in the same holder (UL
-- parity — they inherit transform/opacity/visibility). Copy COUNT is fixed at
-- build time (a dynamic o_size/w_distance keeps its initial ring/copy count);
-- colors, opacities and offsets stay live through composites.
--   opts.makeCopy(z)             → new effect node parented at local z-order z
--   opts.paint(node, hex, op)    → color a copy (op is 0..100)
--   opts.baseX/baseY             → the visual's rest position inside the holder
--   opts.base()                  → live variant of baseX/baseY: read each fire
--                                  (DrawNode shapes derive it from width/height)
--   opts.geomAttrs               → attr specs appended to every effect
--                                  composite, so copies re-place/repaint when
--                                  live layer geometry moves
--   opts.nativeOutline(c4b, px)  → optional hard-stroke fast path (TTF labels)
local function bindEffects(rec, opts)
    local L = rec.L
    local paint = opts.paint
    local base = opts.base or function() return opts.baseX or 0, opts.baseY or 0 end
    local function withGeom(attrs)
        for _, a in ipairs(opts.geomAttrs or {}) do attrs[#attrs + 1] = a end
        return attrs
    end

    if L.outline == "Glow" then
        -- rings of 8 copies at 45° steps; UL: ring r sits at 2*r px up to
        -- o_size. Rings are capped for the watch GPU — the step widens so the
        -- halo keeps the authored o_size spread.
        local size  = math.max(1, rec:evalNum("o_size", 10))
        local rings = math.ceil(size / 2)
        local step  = 2
        if rings > 8 then rings = 8; step = size / rings end
        local glows = {}
        for ring = 1, rings do
            for a = 0, 7 do
                glows[#glows + 1] = { node = opts.makeCopy(-2),
                    ox = ring * step * cos(rad(a * 45)),
                    oy = ring * step * sin(rad(a * 45)), ring = ring }
            end
        end
        rec:use("o_color", "sub"); rec:use("o_opacity", "num")
        rec:composite(function(r)
            local bx, by = base()
            local col = r:evalSub("o_color", "ffffff")
            local op  = clamp(r:evalNum("o_opacity", 100), 0, 100)
            for _, g in ipairs(glows) do
                g.node:setPosition(bx + g.ox, by + g.oy)
                paint(g.node, col, (op / rings) * (1 - (g.ring - 1) / rings))
            end
        end, withGeom({ { "o_color", "sub" }, { "o_opacity", "num" } }))
    elseif L.outline == "Outline" then
        if opts.nativeOutline then
            rec:use("o_color", "sub"); rec:use("o_size", "num"); rec:use("o_opacity", "num")
            rec:composite(function(r)
                opts.nativeOutline(c4b(r:evalSub("o_color", "ffffff"),
                                       clamp(r:evalNum("o_opacity", 100), 0, 100)),
                                   math.max(1, floor(r:evalNum("o_size", 4))))
            end, { { "o_color", "sub" }, { "o_size", "num" }, { "o_opacity", "num" } })
        else
            -- hard stroke as 8 copies pushed out o_size in every direction
            -- (UL's shape outline; also the fallback for non-TTF text)
            local ring = {}
            for a = 0, 7 do ring[a + 1] = opts.makeCopy(-2) end
            rec:use("o_color", "sub"); rec:use("o_size", "num"); rec:use("o_opacity", "num")
            rec:composite(function(r)
                local bx, by = base()
                local col  = r:evalSub("o_color", "ffffff")
                local op   = clamp(r:evalNum("o_opacity", 100), 0, 100)
                local size = r:evalNum("o_size", 4)
                for a = 0, 7 do
                    ring[a + 1]:setPosition(bx + size * cos(rad(a * 45)),
                                            by + size * sin(rad(a * 45)))
                    paint(ring[a + 1], col, op)
                end
            end, withGeom({ { "o_color", "sub" }, { "o_size", "num" }, { "o_opacity", "num" } }))
        end
    end

    if L.shadow == "Drop" or L.shadow == "Flat" then
        -- w_angle is the light direction; the shadow goes opposite. Drop = one
        -- copy at w_distance, Flat = stacked copies at 1..floor(w_distance).
        local n = L.shadow == "Drop" and 1
                  or math.max(0, floor(rec:evalNum("w_distance", 5)))
        local shadows = {}
        for i = 1, n do shadows[i] = opts.makeCopy(-1) end
        if n > 0 then
            rec:use("w_color", "sub"); rec:use("w_distance", "num")
            rec:use("w_angle", "num"); rec:use("w_opacity", "num")
            rec:composite(function(r)
                local bx, by = base()
                local col  = r:evalSub("w_color", "000000")
                local op   = clamp(r:evalNum("w_opacity", 100), 0, 100)
                local ang  = rad(r:evalNum("w_angle", 135))
                local dist = r:evalNum("w_distance", 5)
                for i, s in ipairs(shadows) do
                    local d = L.shadow == "Drop" and dist or i
                    s:setPosition(bx - d * cos(ang), by - d * sin(ang))
                    paint(s, col, op)
                end
            end, withGeom({ { "w_color", "sub" }, { "w_distance", "num" },
                            { "w_angle", "num" }, { "w_opacity", "num" } }))
        end
    end
end

-- ── shape shaders (WM_EFFECTS.md §3–4) ────────────────────────────────────────
-- The UL fragment shaders ship VERBATIM (Source/shaders/wm_*.frag, compiled by
-- axslcc into assets/axslc/custom/). Each program loads ONCE per app run and is
-- shared by every layer using that shader; each layer carries only its own
-- ProgramState with the u_* uniforms, refreshed through one composite so
-- tag-driven values ({drms}, {bl}, script vars) animate like UL's per-frame
-- uniforms. u_* semantics follow the UL .fsh sources (the WM_EFFECTS tables
-- for Progress and Radar are wrong): Progress u_1=%, u_2=angle, u_3=opIn,
-- u_4=opOut; Radar u_1=angle, u_2=opIn, u_3=opOut.

-- uniform spec per shader: { name, "color"|"num", default }
local SHADER_UNIFORMS = {
    GradientLinear  = { { "u_1", "color", "ffffff" }, { "u_2", "color", "000000" },
                        { "u_3", "num", 0 }, { "u_4", "num", 100 } },
    GradientRadial  = { { "u_1", "color", "ffffff" }, { "u_2", "color", "000000" },
                        { "u_3", "num", 100 } },
    Rainbow         = { { "u_1", "num", 0 } },
    HSV             = { { "u_1", "num", 0 }, { "u_2", "num", 0 }, { "u_3", "num", 0 } },
    RGB             = { { "u_1", "num", 0 }, { "u_2", "num", 0 }, { "u_3", "num", 0 } },
    Segment         = { { "u_1", "num", 0 }, { "u_2", "num", 100 }, { "u_3", "num", 0 } },
    SegmentBetween  = { { "u_1", "num", 0 }, { "u_2", "num", 360 },
                        { "u_3", "num", 100 }, { "u_4", "num", 0 } },
    Radar           = { { "u_1", "num", 0 }, { "u_2", "num", 100 }, { "u_3", "num", 0 } },
    Progress        = { { "u_1", "num", 0 }, { "u_2", "num", 0 },
                        { "u_3", "num", 100 }, { "u_4", "num", 0 } },
    ProgressBetween = { { "u_1", "num", 0 }, { "u_2", "num", 0 }, { "u_3", "num", 0 },
                        { "u_4", "num", 100 }, { "u_5", "num", 0 } },
}

-- one backend Program per shader, loaded on first use and never re-created
local shaderPrograms = {}
local function shaderProgram(name)
    local prog = shaderPrograms[name]
    if prog == nil then
        local ok, p = pcall(function()
            -- 3 = backend::VertexLayoutType::Sprite (V3F_C4B_T2F)
            return axb.ProgramManager:getInstance():loadProgram(
                "positionTextureColor_vs", "custom/wm_" .. name .. "_fs", 3)
        end)
        prog = (ok and p) or false
        if not prog then
            print("[WFRender] shader program 'wm_" .. name .. "' failed to load: " .. tostring(p))
        end
        shaderPrograms[name] = prog
    end
    return prog
end

-- setUniform's Lua binding takes raw bytes: pack floats little-endian
local spack, srep, sbyte = string.pack, string.rep, string.byte
local function packFloats(...)
    local s = spack("<" .. srep("f", select("#", ...)), ...)
    return { sbyte(s, 1, #s) }
end

-- Attach `shader=` to a textured node. The node's tint/opacity flow into the
-- shader as v_color (UL's u_opacity); u_* refresh through one composite.
-- Returns false for unknown shaders so callers fall back to the plain path.
local function bindShapeShader(rec, sp)
    local L = rec.L
    local spec = L.shader and SHADER_UNIFORMS[L.shader]
    if not spec then return false end
    local prog = shaderProgram(L.shader)
    if not prog then return false end
    local st = axb.ProgramState:new(prog)
    sp:setProgramState(st)
    local attrs = { { "color", "sub" }, { "color_dim", "sub" } }
    rec:use("color", "sub"); rec:use("color_dim", "sub")
    for _, u in ipairs(spec) do
        local kind = u[2] == "color" and "sub" or "num"
        rec:use(u[1], kind)
        attrs[#attrs + 1] = { u[1], kind }
    end
    rec:dimComposite(function(r)
        sp:setColor(c3b(r:evalSub(colAttr(r), "ffffff")))
        for _, u in ipairs(spec) do
            if u[2] == "color" then
                local c = WFColor.rgb(r:evalSub(u[1], u[3]))
                st:setUniform(u[1], packFloats(c.r / 255, c.g / 255, c.b / 255))
            else
                st:setUniform(u[1], packFloats(r:evalNum(u[1], u[3])))
            end
        end
    end, attrs)
    return true
end

-- A white-texture quad for primitive/rounded shapes: the shaders sample
-- texcoords, which DrawNode geometry doesn't carry.
local function whiteQuad()
    local tex = cc.Director:getInstance():getTextureCache():getWhiteTexture()
    return tex and cc.Sprite:createWithTexture(tex) or nil
end

-- Bake an off-graph node into a retained RenderTexture and hand back the
-- sprite + a rebake(). Labels (straight or curved) carry font-ATLAS
-- texcoords, so their shaders must run over a texture whose 0..1 spans the
-- layer box instead. getSize() → content w,h; the node draws centred. The RT
-- reallocates only when the content outgrows it (released via retained).
local function bakedSprite(self, node, getSize)
    node:retain()
    self.retained[#self.retained + 1] = node
    local rtSlot = #self.retained + 1
    local rt, rw, rh
    local sp = cc.Sprite:create()
    local function rebake()
        local w, h = getSize()
        w = math.max(2, math.ceil(w)); h = math.max(2, math.ceil(h))
        if not rt or w > rw or h > rh or (rw - w) > 8 or (rh - h) > 8 then
            local nrt = cc.RenderTexture:create(w, h)
            if not nrt then
                -- keep the old RT and its last-baked content (degraded, alive)
                print("[WFRender] bakedSprite: RT create failed (" .. w .. "x" .. h .. ")")
                return
            end
            -- The outgrown RT may have begin/end commands queued by an earlier
            -- rebake THIS frame (two events can fire one binding twice per
            -- frame) — a synchronous release() frees it before the renderer
            -- flushes those commands (GLThread SIGSEGV). Defer instead; the
            -- retained slot is overwritten below so clear() won't touch it.
            if rt then self:deferRelease(rt) end
            rt, rw, rh = nrt, w, h
            rt:retain()
            self.retained[rtSlot] = rt
        end
        node:setPosition(rw / 2, rh / 2)
        rt:beginWithClear(0, 0, 0, 0)
        node:visit()
        rt:endToLua()
        sp:setTexture(rt:getSprite():getTexture())
        sp:setTextureRect(cc.rect(0, 0, rw, rh))
        sp:setFlippedY(true)             -- RT textures are y-flipped
    end
    rebake()
    -- EGL context loss: the RT's backend names belong to the dead context —
    -- swap in a FRESH RT before re-rendering (see the run-RT restore note).
    self._ctxRestore[#self._ctxRestore + 1] = function()
        if rt then
            local nrt = cc.RenderTexture:create(rw, rh)
            if not nrt then rt = nil; return end   -- old stays retained; next rebake retries
            self:deferRelease(rt)
            rt = nrt
            rt:retain(); self.retained[rtSlot] = rt
        end
        rebake()
    end
    return sp, rebake
end

function Builders.group(self, rec) end   -- container only; no visual

function Builders.image(self, rec)
    local L = rec.L
    local sp = cc.Sprite:create(self:imageFile(L.path))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    -- live width/height: re-derive the base scale and re-apply it to the node
    -- (mirrors the post-builder application in the main build loop)
    rec:use("width", "num"); rec:use("height", "num")
    rec:compositeNow(function(r)
        setBaseScale(r, cs.width, cs.height, r:evalNum("width", cs.width), r:evalNum("height", cs.height))
        sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
    end, { { "width", "num" }, { "height", "num" } })
    -- shader consumes the image alpha as its mask (UL applies shaders to
    -- image layers the same way); otherwise plain tint
    if not bindShapeShader(rec, sp) then bindTint(rec, sp) end
end

Builders.gif = Builders.image   -- native gif: render first frame statically

function Builders.image_tap(self, rec)
    local L = rec.L
    local frames = {}
    for f in (L.paths or ""):gmatch("[^`]+") do frames[#frames + 1] = f end
    rec.frames, rec.frameIdx = frames, 1
    local sp = frames[1] and cc.Sprite:create(self:imageFile(frames[1]))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    rec:use("width", "num"); rec:use("height", "num")
    rec:compositeNow(function(r)
        setBaseScale(r, cs.width, cs.height, r:evalNum("width", cs.width), r:evalNum("height", cs.height))
        sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
    end, { { "width", "num" }, { "height", "num" } })
    if not bindShapeShader(rec, sp) then bindTint(rec, sp) end   -- survives setTexture
    rec.tap = function()
        rec.frameIdx = (rec.frameIdx % #frames) + 1
        sp:setTexture(self:imageFile(frames[rec.frameIdx]))
    end
end

function Builders.image_gif(self, rec)
    local L = rec.L
    local frames = {}
    for f in (L.path or ""):gmatch("[^`]+") do frames[#frames + 1] = f end
    if #frames == 0 then return end
    rec.frames = frames
    rec.delay = math.max(0.02, rec:evalNum("gif_delay", 50) / 1000)
    local sp = cc.Sprite:create(self:imageFile(frames[1]))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    rec:use("width", "num"); rec:use("height", "num")
    rec:compositeNow(function(r)
        setBaseScale(r, cs.width, cs.height, r:evalNum("width", cs.width), r:evalNum("height", cs.height))
        sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
    end, { { "width", "num" }, { "height", "num" } })
    if not bindShapeShader(rec, sp) then bindTint(rec, sp) end   -- survives setTexture
    -- frame stepping is elapsed-time-driven, not tag-driven: a raw ms listener.
    -- Because it drives the sprite through a closure (not a {tag} descriptor),
    -- recHasFastDep can't see it — flag the rec so flattenStatics never bakes
    -- this animating layer into a static RT (it would freeze on frame 1).
    rec._noFlatten = true
    rec.lastFrame = -1
    self.engine:listen("ms", function(clock)
        local fi = floor(clock.elapsed / rec.delay) % #frames + 1
        if fi ~= rec.lastFrame then
            rec.lastFrame = fi
            sp:setTexture(self:imageFile(frames[fi]))
        end
    end)
end

function Builders.text(self, rec)
    local L = rec.L
    local size = math.max(1, floor(rec:evalNum("text_size", 20)))
    local font = L.font or ""
    local function makeLabel(str)
        local lab
        if font:sub(1, 3) == "bm:" then
            local fnt = self.base .. "fonts_bm/" .. font:sub(4) .. ".fnt"
            -- BM atlases usually carry ONLY uppercase glyphs; UL uppercases
            -- every bitmap-font string (lowercase renders as missing glyphs
            -- with no visible error — blank text).
            if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, str:upper()) end
            if lab then rec.isBM = true end
        end
        if not lab and font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then
                lab = cc.Label:createWithTTF(str, ttf, fontPx(size))
                if lab then rec.isTTF = true end
            else
                print("[WFRender] font '" .. font .. ".ttf' not found at " .. ttf)
            end
        end
        if not lab then lab = cc.Label:createWithSystemFont(str, "sans-serif", fontPx(size)) end
        return lab
    end
    local isIcon = isMaterialIconsFont(font)
    local iconCache = isIcon and {} or nil
    local function caseOf(str)
        -- icon-name -> Private-Use glyph first (before any case transform, which
        -- would break the lowercase ligature names)
        if isIcon then str = materialSubst(str, iconCache) end
        if rec.isBM then return str:upper() end
        if L.transform == "uc" then return str:upper()
        elseif L.transform == "lc" then return str:lower() end
        return str
    end
    local lab = makeLabel(caseOf(rec:evalText("text", "")))
    rec.visual = lab
    lab:setHorizontalAlignment(cc.TEXT_ALIGNMENT_CENTER)
    -- Bitmap fonts render at their atlas size; scale so a line's visible height
    -- lands near 1.6 * text_size, matching the reference renderer's bitmap rule.
    -- (TTF labels are already created at fontPx(text_size) — see sizing NOTE.)
    if rec.isBM then
        local ch = lab:getContentSize().height
        local lines = 1; for _ in (L.text or ""):gmatch("\n") do lines = lines + 1 end
        local perLine = (ch > 0 and ch / lines) or size
        rec.bsx = 1.6 * size / perLine; rec.bsy = rec.bsx
    end
    if L.stencil == "y" or L.stencil == "Y" then
        -- WM_EFFECTS §6: the glyphs become a see-through cutout — everything
        -- behind the layer is hidden except through the text-shaped holes.
        -- A Label can't be an alpha-threshold stencil directly (the alpha-test
        -- program doesn't reach its internal commands, so glyph QUADS punch
        -- rectangles). Like UL: bake the glyphs into a RenderTexture and use
        -- that sprite as the mask of an inverted ClippingNode; a FULL-CANVAS
        -- plate of the layer color draws where the glyphs are not.
        local RTS = 512
        local ap = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
        lab:setAnchorPoint(ap)
        lab:setScale(rec.bsx, rec.bsy)       -- bake base scale into the mask
        lab:setPosition(RTS / 2, RTS / 2)
        local rt = cc.RenderTexture:create(RTS, RTS)
        -- neither is in the scene graph — keep them alive until face switch
        lab:retain(); rt:retain()
        self.retained[#self.retained + 1] = lab
        self.retained[#self.retained + 1] = rt
        local rtSlot = #self.retained
        rec.bsx, rec.bsy = 1, 1              -- the clip node itself stays unscaled
        local function renderMask()
            rt:beginWithClear(0, 0, 0, 0)
            lab:visit()
            rt:endToLua()
        end
        local mask = cc.Sprite:createWithTexture(rt:getSprite():getTexture())
        mask:setFlippedY(true)               -- RT textures are y-flipped
        local clip = cc.ClippingNode:create()
        clip:setInverted(true)
        clip:setAlphaThreshold(0.05)
        clip:setStencil(mask)
        -- Never bake a ClippingNode into a run RT: the RT has no stencil
        -- attachment (the cutout renders wrong), and the clip enqueues its
        -- StencilStateManager's MEMBER CustomCommand whose callbacks capture
        -- the manager — a queued command surviving the node is a GLThread UAF.
        rec._noFlatten = true
        local plate = cc.DrawNode:create()
        plate:setCascadeOpacityEnabled(true)
        clip:addChild(plate)
        rec.visual = clip
        -- plate rect = the 512 canvas mapped into holder space (the holder
        -- sits at the authored x/y)
        local hx, hy = rec:evalNum("x", 0), Y_SIGN * rec:evalNum("y", 0)
        local function redrawPlate(r)
            plate:clear()
            plate:drawSolidRect(cc.p(-256 - hx, -256 - hy),
                                cc.p(256 - hx, 256 - hy),
                                c4f(r:evalSub(colAttr(r), "ffffff"), 100))
        end
        rec:bindText("text", function(_, v)
            lab:setString(caseOf(v))
            renderMask()
        end)
        renderMask()
        -- EGL context loss: recreate the mask RT (dead backend names), re-point
        -- the clip's stencil sprite at the new texture, re-render the glyphs.
        self._ctxRestore[#self._ctxRestore + 1] = function()
            local nrt = cc.RenderTexture:create(RTS, RTS)
            if not nrt then return end
            self:deferRelease(rt)
            rt = nrt
            rt:retain(); self.retained[rtSlot] = rt
            mask:setTexture(rt:getSprite():getTexture())
            renderMask()
        end
        rec:use("color", "sub"); rec:use("color_dim", "sub")
        rec:dimComposite(redrawPlate, { { "color", "sub" }, { "color_dim", "sub" } })
        return
    end

    if L.shader and SHADER_UNIFORMS[L.shader] then
        -- labels carry font-ATLAS texcoords: bake the glyphs (white — the
        -- layer color arrives as the sprite tint) into an RT sized to the
        -- text box and run the shader over THAT sprite
        lab:setAnchorPoint(cc.p(.5, .5))
        lab:setScale(rec.bsx, rec.bsy)
        local bsx, bsy = rec.bsx, rec.bsy
        rec.bsx, rec.bsy = 1, 1
        local sp, rebake = bakedSprite(self, lab, function()
            local cs = lab:getContentSize()
            return cs.width * bsx + 4, cs.height * bsy + 4
        end)
        rec.visual = sp
        bindShapeShader(rec, sp)
        rec:bindText("text", function(_, v)
            lab:setString(caseOf(v))
            rebake()
        end)
        return
    end

    -- effect copies: labels mirroring lab's string, behind it in the holder
    local fx = {}
    local function addFx(z)
        local c = makeLabel(caseOf(rec:evalText("text", "")))
        c:setHorizontalAlignment(cc.TEXT_ALIGNMENT_CENTER)
        local ap = L.alignment and ALIGN_AP[L.alignment]
        if ap then c:setAnchorPoint(ap) end
        c:setScaleX(rec.bsx); c:setScaleY(rec.bsy)
        c:setCascadeOpacityEnabled(true)
        rec.holder:addChild(c, z)
        fx[#fx + 1] = c
        return c
    end
    bindEffects(rec, {
        makeCopy = addFx,
        paint = function(n, hex, op)
            n:setColor(c3b(hex)); n:setOpacity(floor(op * 2.55))
        end,
        nativeOutline = rec.isTTF
            and function(col, px) lab:enableOutline(col, px) end or nil,
    })

    rec:bindText("text", function(_, v)
        local s = caseOf(v)
        lab:setString(s)
        for i = 1, #fx do fx[i]:setString(s) end
    end)
    -- colour follows dim mode (color_dim); alpha stays 100 — layer opacity is
    -- applied once, via the holder (baking it here would square it)
    rec:use("color", "sub"); rec:use("color_dim", "sub")
    rec:dimComposite(function(r)
        lab:setTextColor(c4b(r:evalSub(colAttr(r), "ffffff"), 100))
    end, { { "color", "sub" }, { "color_dim", "sub" } })
end

function Builders.image_cond(self, rec)
    local L = rec.L
    local sp = cc.Sprite:create(self:imageFile(L.path))
    if not sp then return end
    rec.visual = sp
    local cs = sp:getContentSize()
    local cols, rows = (L.cond_grid or "1x1"):match("(%d+)x(%d+)")
    cols, rows = num(cols, 1), num(rows, 1)
    local cw, chh = cs.width / cols, cs.height / rows
    -- shader rides the sprite (texcoords span the CELL's atlas sub-rect —
    -- same quirk as UL, which also sets the program straight on the sprite)
    bindShapeShader(rec, sp)
    -- WM night weather sets (weather_night="Y"): the face ships BOTH 3×3
    -- sheets, base + "_n" (the "_n" one only differs where night art exists:
    -- clear/few-clouds get moon glyphs in cells 4/3). {wci} always carries
    -- the "d" suffix — the WM editor generates day-code-only cond_values even
    -- for night-enabled elements — so night handling happens HERE: by day
    -- show the base sheet with the expression's cell as-is; by night show the
    -- "_n" sheet and replace the sun cells (clear 1→4 moon, few clouds 2→3
    -- moon+cloud, scattered 3→3, broken 4→9 cloud stack). Confirmed against a
    -- WM-exported test face (weather_set_1*.png, 2026-07-10).
    local nightSet = (L.weather_night == "Y")
    local dayFile, nightFile
    if nightSet then
        local stem, ext = (L.path or ""):match("^(.*)(%.%w+)$")   -- build time: patterns OK
        if stem then
            if stem:sub(-2) == "_n" then stem = stem:sub(1, -3) end
            local df, nf = self:imageFile(stem .. ext), self:imageFile(stem .. "_n" .. ext)
            dayFile   = fileExists(df) and df or self:imageFile(L.path)
            nightFile = fileExists(nf) and nf or dayFile
        end
        if not dayFile then nightSet = false end
    end
    if nightSet then
        -- the sheet/cell choice rides WF.weather.isDaytime, which is invisible
        -- to the descriptor dependency scan — never bake this layer
        rec._noFlatten = true
    end
    local NIGHT_CELL = { [1] = 4, [2] = 3, [3] = 3, [4] = 9 }
    local curFile
    rec.lastIdx = -1
    rec:use("cond_value", "num"); rec:use("width", "num"); rec:use("height", "num")
    rec:use("color", "sub"); rec:use("color_dim", "sub")
    local function redraw(r)
        local night = nightSet and WF.weather.isDaytime == false
        if nightSet then
            local want = night and nightFile or dayFile
            if want ~= curFile then
                curFile = want
                sp:setTexture(want)
                local tcs = sp:getTexture():getContentSize()
                if tcs.width > 0 then cw, chh = tcs.width / cols, tcs.height / rows end
                rec.lastIdx = -1          -- force the rect onto the new sheet
            end
        end
        -- cw/chh are the TEXTURE cell size (texture ÷ grid, static per sheet);
        -- only the authored box is live and feeds the base scale
        setBaseScale(r, cw, chh, r:evalNum("width", cw), r:evalNum("height", chh))
        sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
        local cell = clamp(floor(r:evalNum("cond_value", 1)), 1, cols * rows)
        if night then cell = NIGHT_CELL[cell] or cell end
        local idx = cell - 1
        if idx ~= rec.lastIdx then
            rec.lastIdx = idx
            local col, row = idx % cols, floor(idx / cols)   -- row-major, top-left
            sp:setTextureRect(cc.rect(col * cw, row * chh, cw, chh))
        end
        sp:setColor(c3b(r:evalSub(colAttr(r), "ffffff")))
    end
    rec:dimCompositeNow(redraw, { { "cond_value", "num" }, { "width", "num" },
        { "height", "num" }, { "color", "sub" }, { "color_dim", "sub" } })
    if nightSet then
        -- isDaytime can flip without any {tag} value changing (wci stays "d");
        -- a raw weather listener keeps the sheet honest on every push
        self.engine:listen("weather", function()
            local ok, err = pcall(redraw, rec)
            if not ok then print("[WFRender] weather_night redraw: " .. tostring(err)) end
        end)
    end
end

function Builders.shape(self, rec)
    local L = rec.L
    -- 2.0 shapes may ship an image mask; prefer it when present.
    local shapeImg = L.path and L.path ~= "" and self:imageFile(L.path)
    if shapeImg and fileExists(shapeImg) then
        local sp = cc.Sprite:create(shapeImg)
        if sp then
            rec.visual = sp
            local cs = sp:getContentSize()
            local copies = {}
            rec:use("width", "num"); rec:use("height", "num")
            rec:compositeNow(function(r)
                setBaseScale(r, cs.width, cs.height, r:evalNum("width", cs.width), r:evalNum("height", cs.height))
                sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
                -- effect copies take their scale at creation; keep them in step
                for _, c in ipairs(copies) do c:setScaleX(r.bsx); c:setScaleY(r.bsy) end
            end, { { "width", "num" }, { "height", "num" } })
            -- shader consumes the image alpha as its mask (exactly UL);
            -- otherwise plain tint
            if not bindShapeShader(rec, sp) then
                bindTint(rec, sp)
            end
            bindEffects(rec, {
                makeCopy = function(z)
                    local c = cc.Sprite:create(shapeImg)
                    local ap = L.alignment and ALIGN_AP[L.alignment]
                    if ap then c:setAnchorPoint(ap) end
                    c:setScaleX(rec.bsx); c:setScaleY(rec.bsy)
                    c:setCascadeOpacityEnabled(true)
                    rec.holder:addChild(c, z)
                    copies[#copies + 1] = c
                    return c
                end,
                paint = function(n, hex, op)
                    n:setColor(c3b(hex)); n:setOpacity(floor(op * 2.55))
                end,
            })
            return
        end
    end
    -- Otherwise draw the primitive with a DrawNode. w/h are upvalues refreshed
    -- by the geometry composite below — drawPrim, the effect composites and the
    -- base() offsets all read them live.
    local w, h = rec:evalNum("width", 50), rec:evalNum("height", 50)
    local shp = (L.shape or "Square"):lower()
    local ap = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
    local GEOM = { { "width", "num" }, { "height", "num" } }
    -- refresh the derived box + re-apply the anchor shift the main build loop
    -- performs once off rec.shapeW (anchored shapes stay pinned on resize)
    local function applyBox(r)
        w, h = r:evalNum("width", 50), r:evalNum("height", 50)
        r.shapeW, r.shapeH = w, h
        r.visual:setPosition((0.5 - ap.x) * w, (0.5 - ap.y) * h)
    end
    local function drawPrim(node, col)
        node:clear()
        if shp == "circle" then
            node:drawSolidCircle(cc.p(0, 0), w / 2, 0, 48, 1, h / w, col)
        elseif SHAPE_PTS[shp] then
            fillShapePts(node, w, h, SHAPE_PTS[shp], col)
        else   -- Square / default
            node:drawSolidRect(cc.p(-w / 2, -h / 2), cc.p(w / 2, h / 2), col)
        end
    end
    if L.shader and SHADER_UNIFORMS[L.shader] then
        -- primitives have no texcoords: run the shader on a white quad sized
        -- to the layer box; non-rectangular shapes clip it through the
        -- primitive geometry
        local sp = whiteQuad()
        if sp and bindShapeShader(rec, sp) then
            local cs = sp:getContentSize()
            if shp == "square" or shp == "" then
                rec.visual = sp
                rec:compositeNow(function(r)
                    w, h = r:evalNum("width", 50), r:evalNum("height", 50)
                    setBaseScale(r, cs.width, cs.height, w, h)
                    sp:setScaleX(r.bsx); sp:setScaleY(r.bsy)
                end, GEOM)
            else
                sp:setCascadeOpacityEnabled(true)
                local mask = cc.DrawNode:create()
                local clip = cc.ClippingNode:create()
                clip:setStencil(mask)
                clip:addChild(sp)
                rec.visual = clip
                rec:compositeNow(function(r)
                    applyBox(r)
                    sp:setScaleX(w / cs.width); sp:setScaleY(h / cs.height)
                    drawPrim(mask, cc.c4f(1, 1, 1, 1))
                end, GEOM)
            end
            return
        end
    end
    local dn = cc.DrawNode:create()
    rec.visual = dn
    rec:use("color", "sub"); rec:use("color_dim", "sub")
    rec:dimCompositeNow(function(r)
        applyBox(r)
        drawPrim(dn, c4f(r:evalSub(colAttr(r), "ffffff"), 100))
    end, { { "width", "num" }, { "height", "num" },
           { "color", "sub" }, { "color_dim", "sub" } })
    -- effect copies redraw the primitive; load() shifts the main visual by the
    -- anchor offset (rec.shapeW path), so copies derive the same shift live
    -- through base(). geomAttrs re-fires them on a live resize (their bucket
    -- runs AFTER the geometry composite above — bind order — so drawPrim
    -- already sees the fresh w/h).
    bindEffects(rec, {
        base = function() return (0.5 - ap.x) * w, (0.5 - ap.y) * h end,
        geomAttrs = GEOM,
        makeCopy = function(z)
            local c = cc.DrawNode:create()
            c:setCascadeOpacityEnabled(true)
            rec.holder:addChild(c, z)
            return c
        end,
        paint = function(n, hex, op) drawPrim(n, c4f(hex, op)) end,
    })
end

function Builders.rounded(self, rec)
    local L = rec.L
    -- w/h/r0/cor are upvalues refreshed per redraw so the box, radius and
    -- corner pick stay live
    local w, h, r0, cor
    local ap = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
    local GEOM = { { "width", "num" }, { "height", "num" },
                   { "radius", "num" }, { "corner_type", "num" } }
    local function applyBox(r)
        w, h = r:evalNum("width", 100), r:evalNum("height", 60)
        r0 = math.min(r:evalNum("radius", 0), w / 2, h / 2)
        cor = CORNERS[floor(r:evalNum("corner_type", 1))] or CORNERS[1]   -- {TL,TR,BL,BR}
        r.shapeW, r.shapeH = w, h
        r.visual:setPosition((0.5 - ap.x) * w, (0.5 - ap.y) * h)
    end
    if L.shader and SHADER_UNIFORMS[L.shader] then
        -- shader on a white quad clipped through the rounded-rect geometry
        local sp = whiteQuad()
        if sp and bindShapeShader(rec, sp) then
            local cs = sp:getContentSize()
            sp:setCascadeOpacityEnabled(true)
            local mask = cc.DrawNode:create()
            local clip = cc.ClippingNode:create()
            clip:setStencil(mask)
            clip:addChild(sp)
            rec.visual = clip
            rec:compositeNow(function(r)
                applyBox(r)
                sp:setScaleX(w / cs.width); sp:setScaleY(h / cs.height)
                mask:clear()
                fillRoundedRect(mask, -w / 2, -h / 2, w / 2, h / 2,
                    { cor[1] * r0, cor[2] * r0, cor[3] * r0, cor[4] * r0 }, cc.c4f(1, 1, 1, 1))
            end, GEOM)
            return
        end
    end
    local dn = cc.DrawNode:create()
    rec.visual = dn
    rec:use("color", "sub"); rec:use("color_dim", "sub")
    rec:dimCompositeNow(function(rr)
        applyBox(rr)
        dn:clear()
        local col = c4f(rr:evalSub(colAttr(rr), "ffffff"), 100)
        fillRoundedRect(dn, -w / 2, -h / 2, w / 2, h / 2,
            { cor[1] * r0, cor[2] * r0, cor[3] * r0, cor[4] * r0 }, col)
    end, { { "width", "num" }, { "height", "num" }, { "radius", "num" },
           { "corner_type", "num" }, { "color", "sub" }, { "color_dim", "sub" } })
end

-- Ring semantics follow the UL Ring shader (and the wftools preview), NOT the
-- old WM_FORMAT_1.0 §7.3 text: the fill is a gradient color→color2 laid over
-- the whole angle_total span and revealed by `angle`; color3 is the UNFILLED
-- track (at outside_opacity); anything beyond angle_total stays transparent.
-- Both fill and track get rounded end caps (half-thickness discs).
function Builders.ring(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local cw = (L.is_clockwise or "Y") ~= "N"
    local ap = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
    rec:compositeNow(function(r)
        local ro = r:evalNum("radius_outer", 100)
        local ri = r:evalNum("radius_inner", ro * 0.8)
        if ri > ro then ri, ro = ro, ri end
        r.shapeW, r.shapeH = ro * 2, ro * 2  -- UL layer box: 2×radius_outer
                                             -- (drives alignment shift + taps)
        r.visual:setPosition((0.5 - ap.x) * r.shapeW, (0.5 - ap.y) * r.shapeH)
        local rc, capR = (ri + ro) / 2, (ro - ri) / 2
        local outOp = clamp(r:evalNum("outside_opacity", 100), 0, 100)
        dn:clear()
        local total = r:evalNum("angle_total", 360)
        if total <= 0 then total = 360 end
        local ang = clamp(r:evalNum("angle", 0), 0, total)
        -- unfilled track [ang..total]: color3 at outside_opacity
        if L.color3 and ang < total then
            local ct3 = c4f(r:evalSub("color3", "000000"), outOp)
            fillArc(dn, ri, ro, ang, total, cw, ct3)
            ringCap(dn, rc, capR, ang, cw, ct3)
            ringCap(dn, rc, capR, total, cw, ct3)
        end
        -- fill [0..ang]: gradient color→color2, revealed by angle; caps take
        -- the flat gradient colour at their sweep (UL cap behaviour)
        if ang > 0 then
            local ca = WFColor.rgb(r:evalSub("color", "ffffff"))
            local cb = L.color2 and WFColor.rgb(r:evalSub("color2", "ffffff")) or ca
            fillArc(dn, ri, ro, 0, ang, cw,
                function(s) return lerpRGB(ca, cb, s / total) end)
            ringCap(dn, rc, capR, 0, cw, lerpRGB(ca, cb, 0))
            ringCap(dn, rc, capR, ang, cw, lerpRGB(ca, cb, ang / total))
        end
    end, { { "angle", "num" }, { "angle_total", "num" },
           { "radius_outer", "num" }, { "radius_inner", "num" },
           { "outside_opacity", "num" },
           { "color", "sub" }, { "color2", "sub" }, { "color3", "sub" } })
end

-- Progress semantics: `angle` picks the fill AXIS (wftools / real WatchMaker):
-- 0/180 = vertical bar filling bottom→top, anything else (90 = the authored
-- default) = horizontal filling left→right. The box is NOT rotated by angle
-- (`rotation` still applies normally). Track + color4 border exist ONLY when
-- margin > 0 (UL Rounded shader: margin=0 leaves the unfilled area
-- transparent). The fill is a gradient color→color2 across the FILLED span,
-- start corners round per corner_type (pill radius), the leading edge rounds
-- per end_style at the corner radius. WM defaults radius to 50 → a full pill.
function Builders.progress(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local roundEnd = (L.end_style == "round")
    local ap = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
    rec:compositeNow(function(r)
        local w, h = r:evalNum("width", 120), r:evalNum("height", 24)
        r.shapeW, r.shapeH = w, h
        r.visual:setPosition((0.5 - ap.x) * w, (0.5 - ap.y) * h)
        local a = r:evalNum("angle", 90)
        local vertical = (a == 0 or a == 180)
        local margin = math.max(0, r:evalNum("margin", 0))
        local r0 = math.min(r:evalNum("radius", 50), w / 2, h / 2)
        local cor = CORNERS[floor(r:evalNum("corner_type", 1))] or CORNERS[1]
        local outOp = clamp(r:evalNum("outside_opacity", 100), 0, 100) / 100
        dn:clear()
        local pct = clamp(r:evalNum("pct_complete", 0), 0, 100)
        local ix0, iy0 = -w / 2 + margin, -h / 2 + margin
        local ix1, iy1 = w / 2 - margin, h / 2 - margin
        local tw, th = ix1 - ix0, iy1 - iy0
        if tw <= 0 or th <= 0 then return end
        local rIn = math.min(r0, tw / 2, th / 2)
        if margin > 0 then
            -- color4 border, then the color3 track blended over it by
            -- outside_opacity (both opaque, exactly like the UL shader)
            local c4 = WFColor.rgb(r:evalSub("color4", "ffffff"))
            fillRoundedRect(dn, -w / 2, -h / 2, w / 2, h / 2,
                { cor[1] * r0, cor[2] * r0, cor[3] * r0, cor[4] * r0 },
                cc.c4f(c4.r / 255, c4.g / 255, c4.b / 255, 1))
            local c3 = WFColor.rgb(r:evalSub("color3", "000000"))
            fillRoundedRect(dn, ix0, iy0, ix1, iy1,
                { cor[1] * rIn, cor[2] * rIn, cor[3] * rIn, cor[4] * rIn },
                cc.c4f((c3.r * outOp + c4.r * (1 - outOp)) / 255,
                       (c3.g * outOp + c4.g * (1 - outOp)) / 255,
                       (c3.b * outOp + c4.b * (1 - outOp)) / 255, 1))
        end
        if pct <= 0 then return end
        local ca = WFColor.rgb(r:evalSub("color", "ffffff"))
        local cb = L.color2 and WFColor.rgb(r:evalSub("color2", "ffffff")) or ca
        local function colAt(t) return lerpRGB(ca, cb, t) end
        local capR = math.min(tw, th) / 2          -- start-cap radius (UL)
        local rLead = roundEnd and rIn or 0        -- leading edge (end_style)
        if vertical then
            local fh = th * pct / 100
            local rS, rL = math.min(capR, fh / 2), math.min(rLead, fh / 2)
            local blF, brF = cor[3] * rS, cor[4] * rS   -- start = bottom corners
            local bw, tw2 = math.max(blF, brF), rL
            local fy1 = iy0 + fh
            fillRoundedRect(dn, ix0, iy0, ix1, iy0 + bw, { 0, 0, blF, brF }, colAt(0))
            fillRoundedRect(dn, ix0, fy1 - tw2, ix1, fy1, { rL, rL, 0, 0 }, colAt(1))
            gradStrips(dn, ix0, iy0 + bw, ix1, fy1 - tw2, true, colAt,
                bw / fh, (fh - tw2) / fh)
        else
            local fw = tw * pct / 100
            local rS, rL = math.min(capR, fw / 2), math.min(rLead, fw / 2)
            local tlF, blF = cor[1] * rS, cor[3] * rS   -- start = left corners
            local lw, rw = math.max(tlF, blF), rL
            local fx1 = ix0 + fw
            fillRoundedRect(dn, ix0, iy0, ix0 + lw, iy1, { tlF, 0, blF, 0 }, colAt(0))
            fillRoundedRect(dn, fx1 - rw, iy0, fx1, iy1, { 0, rL, 0, rL }, colAt(1))
            gradStrips(dn, ix0 + lw, iy0, fx1 - rw, iy1, false, colAt,
                lw / fw, (fw - rw) / fw)
        end
    end, { { "pct_complete", "num" }, { "width", "num" }, { "height", "num" },
           { "angle", "num" }, { "margin", "num" }, { "radius", "num" },
           { "corner_type", "num" }, { "outside_opacity", "num" },
           { "color", "sub" }, { "color2", "sub" },
           { "color3", "sub" }, { "color4", "sub" } })
end

function Builders.markers(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local shp = (L.shape or "Square"):lower()
    rec:compositeNow(function(r)
        local count = math.max(1, floor(r:evalNum("m_count", 12)))
        local radius = r:evalNum("radius", 200)
        r.hitW, r.hitH = radius * 2, radius * 2
        local mw, mh = r:evalNum("m_width", 10), r:evalNum("m_height", 30)
        local sq = r:evalNum("squarify", 0)
        dn:clear()
        local col = c4f(r:evalSub("color", "ffffff"), 100)
        for i = 0, count - 1 do
            local th = rad(90 - i * 360 / count)     -- clockwise from 12
            local rr = squarifyRadius(radius - mh / 2, th, sq)
            local cx, cy = rr * cos(th), rr * sin(th)
            local ru = cc.p(cos(th), sin(th))        -- radial unit
            local tu = cc.p(-sin(th), cos(th))       -- tangential unit
            if shp == "circle" then
                dn:drawSolidCircle(cc.p(cx, cy), mw / 2, 0, 16, 1, 1, col)
            elseif shp == "triangle" then
                local tip = cc.p(cx + ru.x * mh / 2, cy + ru.y * mh / 2)
                local b1  = cc.p(cx - ru.x * mh / 2 + tu.x * mw / 2, cy - ru.y * mh / 2 + tu.y * mw / 2)
                local b2  = cc.p(cx - ru.x * mh / 2 - tu.x * mw / 2, cy - ru.y * mh / 2 - tu.y * mw / 2)
                dn:drawTriangle(tip, b1, b2, col)
            else   -- square: rect oriented radially
                local p1 = cc.p(cx + ru.x * mh / 2 + tu.x * mw / 2, cy + ru.y * mh / 2 + tu.y * mw / 2)
                local p2 = cc.p(cx + ru.x * mh / 2 - tu.x * mw / 2, cy + ru.y * mh / 2 - tu.y * mw / 2)
                local p3 = cc.p(cx - ru.x * mh / 2 - tu.x * mw / 2, cy - ru.y * mh / 2 - tu.y * mw / 2)
                local p4 = cc.p(cx - ru.x * mh / 2 + tu.x * mw / 2, cy - ru.y * mh / 2 + tu.y * mw / 2)
                fillQuad(dn, p1, p2, p3, p4, col)
            end
        end
    end, { { "m_count", "num" }, { "radius", "num" }, { "m_width", "num" },
           { "m_height", "num" }, { "squarify", "num" }, { "color", "sub" } })
end

function Builders.markers_hm(self, rec)
    local L = rec.L
    local dn = cc.DrawNode:create()
    rec.visual = dn
    local SZ = { None = 0, Tiny = 1, Small = 2, Medium = 3, Large = 4 }
    local hsz = SZ[L.m_hour or "Medium"] or 3
    local msz = SZ[L.m_minute or "Medium"] or 3
    local sq = 0   -- refreshed in the composite before each redraw (tick reads it)
    local function tick(center, th, len, wdt, col)
        local tu = cc.p(-sin(th), cos(th))
        local outer = squarifyRadius(center, th, sq)
        local inner = outer - len
        local ox, oy = outer * cos(th), outer * sin(th)
        local ix, iy = inner * cos(th), inner * sin(th)
        fillQuad(dn,
            cc.p(ox + tu.x * wdt / 2, oy + tu.y * wdt / 2),
            cc.p(ox - tu.x * wdt / 2, oy - tu.y * wdt / 2),
            cc.p(ix - tu.x * wdt / 2, iy - tu.y * wdt / 2),
            cc.p(ix + tu.x * wdt / 2, iy + tu.y * wdt / 2), col)
    end
    rec:compositeNow(function(r)
        local radius = r:evalNum("radius", 240)
        r.hitW, r.hitH = radius * 2, radius * 2
        sq = r:evalNum("squarify", 0)
        dn:clear()
        local colM = c4f(r:evalSub(L.color2 and "color2" or "color", "ffffff"), 100)
        local colH = c4f(r:evalSub("color", "ffffff"), 100)
        if msz > 0 then
            for i = 0, 59 do
                if i % 5 ~= 0 then   -- skip where hour ticks sit
                    tick(radius, rad(90 - i * 6), msz * 3, msz * 0.9, colM)
                end
            end
        end
        if hsz > 0 then
            for i = 0, 11 do
                tick(radius, rad(90 - i * 30), hsz * 6, hsz * 1.8, colH)
            end
        end
    end, { { "radius", "num" }, { "squarify", "num" },
           { "color", "sub" }, { "color2", "sub" } })
end

-- ── video + map layers ────────────────────────────────────────────────────────

-- Video playback via axmol's in-scene MediaPlayer: MediaEngine (MediaCodec on
-- Android) decodes straight into a GL texture composited in z-order like any
-- node — no frame pre-extraction like the UL/cocos2d-x path. Videos stop in
-- dim mode and on face switch (codec + AudioTrack must never outlive the
-- face — same hygiene rule as wm_sfx).
function Builders.video(self, rec)
    local L = rec.L
    if not (axui and axui.MediaPlayer) then
        print("[WFRender] video: axui.MediaPlayer unavailable in this build")
        return
    end
    local path = self.base .. "videos/" .. (L.path or "")
    if not fileExists(path) then
        print("[WFRender] video: missing " .. path)
        return
    end
    local mp = axui.MediaPlayer:create()
    rec.visual = mp
    -- width/height are deliberately build-time-only (Tier-2 decision): live-
    -- resizing a playing MediaPlayer via setContentSize mid-play drives the
    -- MediaCodec output surface and is unverified on this axmol fork — not
    -- worth the risk for a case no known face uses.
    local w, h = rec:evalNum("width", 512), rec:evalNum("height", 512)
    mp:setContentSize(cc.size(w, h))
    mp:setAnchorPoint(cc.p(0.5, 0.5))
    mp:setStyle(1)                        -- StyleType.NONE: no controls
    mp:setKeepAspectRatioEnabled(false)   -- WM stretches to width×height
    mp:setFileName(path)
    mp:setLooping(true)
    -- Mute the video's audio track when "allow sound" is off (the face keeps
    -- animating, silently). setVolume is a CloX addition to Axmol's MediaPlayer.
    if mp.setVolume then
        mp:setVolume((WF.settings and WF.settings.allowSound == false) and 0.0 or 1.0)
    end
    mp:play()
    self.videos[#self.videos + 1] = mp
end

-- Host pause/resume + dim: single owner of the videos' running state. MediaEngine
-- (MediaCodec) decodes on its own thread — pausing GL does NOT stop the codec, so
-- videos must stop whenever the face is not visible (screen off, covered by an
-- app, dim mode) or the decoder keeps burning CPU/battery. stop/play restarts the
-- loop, which is fine for ambient face videos. Never restarts into a dim face.
function WFRender:setVideosRunning(run)
    if run and self.dim then return end
    if run == (self._videosRunning ~= false) then return end
    self._videosRunning = run
    for _, mp in ipairs(self.videos or {}) do
        pcall(function()
            if run then mp:play() else mp:stop() end
        end)
    end
    self:updateNeedFrame()
end

-- Recompute needFrame from every continuous-work source and notify the scene
-- (onNeedFrame → applyFrameRate) on change. A PLAYING video is continuous work:
-- the decoder writes the GL texture every video frame, but without this term the
-- dynamic-FPS idle clamp saw a "static" face and dropped the scene to 1 FPS —
-- the video decoded at full rate yet displayed one frame per second.
function WFRender:updateNeedFrame()
    local was = self.needFrame
    self.needFrame = self.engine:eventActive("ms") or self.script:needsFrame()
        or (#(self.videos or {}) > 0 and self._videosRunning ~= false)
    if self.needFrame ~= was and self.onNeedFrame then self.onNeedFrame() end
end

-- Re-apply the current "allow sound" setting to every playing video (live toggle
-- from the config dialog). No-op if the native setVolume binding is unavailable.
function WFRender:applyVideoVolume()
    local vol = (WF.settings and WF.settings.allowSound == false) and 0.0 or 1.0
    for _, mp in ipairs(self.videos or {}) do
        if mp.setVolume then pcall(function() mp:setVolume(vol) end) end
    end
end

-- One OSM tile centred on lat/lon (UL behaviour: single tile texture scaled
-- to the layer box). lat/lon may be dynamic; a change requests the new tile
-- through the bridge and the tile file arrives as a "map" category push.
local LATLIM = 85.05112878
local function osmTile(lat, lon, zoom)
    local n = 2 ^ zoom
    local tx = floor((lon + 180) / 360 * n)
    if lat > LATLIM then lat = LATLIM elseif lat < -LATLIM then lat = -LATLIM end
    local la = rad(lat)
    local ty = floor((1 - math.log(math.tan(la) + 1 / cos(la)) / math.pi) / 2 * n)
    local max = n - 1
    return clamp(tx, 0, max), clamp(ty, 0, max)
end

function Builders.map(self, rec)
    local L = rec.L
    local sp = cc.Sprite:create()
    if not sp then return end
    rec.visual = sp
    -- tile arrives via a bridge push (no {tag} descriptor) — a baked run would
    -- never see the update and the map would freeze on the first tile
    rec._noFlatten = true
    -- zoom is LIVE: map_zoom (e.g. "var_zoom", driven by a script click_zoom)
    -- was previously captured once at build, so runtime zoom controls never
    -- took effect. Evaluate it in the composite, make it a dependency, and
    -- re-request the tile whenever the resolved zoom (or the tile) changes.
    local wantTx, wantTy, wantZoom
    rec.onMapPush = function(data)
        if not (data and data.file and data.file ~= "") then return end
        if data.zoom ~= wantZoom or data.tx ~= wantTx or data.ty ~= wantTy then return end
        local ok, err = pcall(function()
            sp:setTexture(data.file)
            local cs = sp:getTexture():getContentSize()
            sp:setTextureRect(cc.rect(0, 0, cs.width, cs.height))
            -- read live per push: a dynamic width/height/map_scale applies on
            -- the next tile arrival (the push is already the redraw path here)
            local w, h = rec:evalNum("width", 256), rec:evalNum("height", 256)
            local mscale = rec:evalNum("map_scale", 100) / 100
            if cs.width > 0 and cs.height > 0 then
                sp:setScaleX(w / cs.width * mscale)
                sp:setScaleY(h / cs.height * mscale)
            end
        end)
        if not ok then print("[WFRender] map tile apply: " .. tostring(err)) end
    end
    self:registerMapRec(rec)
    rec:use("lat", "num"); rec:use("lon", "num"); rec:use("map_zoom", "num")
    rec:composite(function(r)
        local lat = r:evalNum("lat", 0)
        local lon = r:evalNum("lon", 0)
        local zoom = clamp(floor(r:evalNum("map_zoom", 15)), 1, 19)
        local tx, ty = osmTile(lat, lon, zoom)
        if tx == wantTx and ty == wantTy and zoom == wantZoom then return end   -- unchanged, skip
        wantTx, wantTy, wantZoom = tx, ty, zoom
        if WatchfaceBridge and WatchfaceBridge.requestMapTile then
            WatchfaceBridge.requestMapTile(lat, lon, zoom)
        end
    end, { { "lat", "num" }, { "lon", "num" }, { "map_zoom", "num" } })
end

-- ── series + calendar layers (port UL WatchSkin) ─────────────────────────────

local SER_DOW3 = { "Sun","Mon","Tue","Wed","Thu","Fri","Sat" }
local SER_DOWFULL = { "Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday" }
local SER_MON3 = { "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec" }

-- Window half-size and the clock event that moves each series (UL: 7 for
-- time series, 3 for day-of-week, 5 for months).
local SERIES_CFG = {
    dmz   = { n = 7, ev = "minute" }, dm    = { n = 7, ev = "minute" },
    dhz   = { n = 7, ev = "hour"   }, dh    = { n = 7, ev = "hour"   },
    dh24z = { n = 7, ev = "hour"   }, dh23z = { n = 7, ev = "hour"   },
    dh24  = { n = 7, ev = "hour"   },
    ddw   = { n = 3, ev = "day"    }, dd_ddw = { n = 3, ev = "day"   },
    dnnn  = { n = 5, ev = "day"    },
}

-- Series value at offset k items from now (negative = past). Time-based
-- series offset real epoch time like UL, so hour/day wraps come out right.
local function seriesValue(sname, k)
    local now = os.time()
    if sname == "dmz" then
        return string.format("%02d", os.date("*t", now + k * 60).min)
    elseif sname == "dm" then
        return tostring(os.date("*t", now + k * 60).min)
    elseif sname == "dhz" then
        return os.date("%I", now + k * 3600)
    elseif sname == "dh" then
        return tostring(tonumber(os.date("%I", now + k * 3600)))
    elseif sname == "dh24z" or sname == "dh23z" then
        return string.format("%02d", os.date("*t", now + k * 3600).hour)
    elseif sname == "dh24" then
        return tostring(os.date("*t", now + k * 3600).hour)
    elseif sname == "ddw" then
        return SER_DOW3[os.date("*t", now + k * 86400).wday]
    elseif sname == "dd_ddw" then
        local d = os.date("*t", now + k * 86400)
        return string.format("%d %s", d.day, SER_DOW3[d.wday])
    elseif sname == "dnnn" then
        local mo = (os.date("*t", now).month - 1 + k) % 12 + 1
        return SER_MON3[mo]
    end
    return ""
end

-- Scrolling series (date-picker style): the current value in font/text_size/
-- color, a window of neighbours in font2/text_size2/color2 offset by
-- spacing + text_size along the orientation. current_pos: "m" = window on
-- both sides, "f"/"u"/"t" = current first (only future items), "l"/"b" =
-- current last (only past items) — UL parse-time expansion, done live here.
function Builders.series(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    -- scrolls via a raw engine:listen(cfg.ev) closure (no {tag} descriptor) —
    -- invisible to the flatten dependency scan, so a baked run would freeze it
    rec._noFlatten = true
    local sname = L.series or "dm"
    local cfg = SERIES_CFG[sname] or { n = 7, ev = "minute" }
    local vertical = (L.orientation or "v") ~= "h"
    local pos = L.current_pos or "m"
    local size1 = math.max(1, floor(rec:evalNum("text_size", 24)))
    local size2 = math.max(1, floor(rec:evalNum("text_size2", size1)))
    local step = rec:evalNum("spacing", 0) + size1     -- UL offsets use the CURRENT size
    local isIcon0 = isMaterialIconsFont(L.font or "")
    local isIcon1 = isMaterialIconsFont(L.font2 or L.font or "")
    local iconCache = (isIcon0 or isIcon1) and {} or nil
    local function caseOf(s)
        if L.transform == "uc" then return s:upper()
        elseif L.transform == "lc" then return s:lower() end
        return s
    end
    local function mkLabel(fontName, size, colorHex)
        local lab
        if (fontName or ""):sub(1, 3) == "bm:" then
            local fnt = self.base .. "fonts_bm/" .. fontName:sub(4) .. ".fnt"
            if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, " ") end
            if lab then
                local ch = lab:getContentSize().height
                if ch > 0 then lab:setScale(1.6 * size / ch) end
                lab:setColor(c3b(colorHex))
                return lab, true
            end
        elseif fontName and fontName ~= "" then
            local ttf = self.base .. "fonts/" .. fontName .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(" ", ttf, fontPx(size)) end
        end
        lab = lab or cc.Label:createWithSystemFont(" ", "sans-serif", fontPx(size))
        lab:setTextColor(c4b(colorHex, 100))
        return lab, false
    end
    local ks = {}
    if pos == "m" then
        for k = -cfg.n, cfg.n do ks[#ks + 1] = k end
    elseif pos == "l" or pos == "b" then
        for k = -cfg.n, 0 do ks[#ks + 1] = k end
    else -- "f" / "u" / "t": current first
        for k = 0, cfg.n do ks[#ks + 1] = k end
    end
    local items = {}
    for _, k in ipairs(ks) do
        local lab, isBM
        if k == 0 then
            lab, isBM = mkLabel(L.font, size1, L.color or "ffffff")
        else
            lab, isBM = mkLabel(L.font2 or L.font, size2, L.color2 or L.color or "ffffff")
        end
        -- vertical: past items sit ABOVE the current one (cocos +y), future
        -- below; horizontal: past left, future right (UL screen layout)
        if vertical then lab:setPosition(0, -k * step) else lab:setPosition(k * step, 0) end
        node:addChild(lab)
        items[#items + 1] = { lab = lab, k = k, bm = isBM, icon = (k == 0) and isIcon0 or isIcon1 }
    end
    -- live step: text_size/spacing re-lay the window out. Glyph size is
    -- creation-time only (labels are built once at size1/size2 — recreating
    -- fonts on every tag fire is a hot-path hazard, documented limitation).
    rec:compositeNow(function(r)
        local s1 = math.max(1, floor(r:evalNum("text_size", 24)))
        step = r:evalNum("spacing", 0) + s1
        for _, it in ipairs(items) do
            if vertical then it.lab:setPosition(0, -it.k * step)
            else it.lab:setPosition(it.k * step, 0) end
        end
    end, { { "text_size", "num" }, { "spacing", "num" } })
    local function update()
        for _, it in ipairs(items) do
            local raw = seriesValue(sname, it.k)
            if it.icon then raw = materialSubst(raw, iconCache) end   -- icon name -> glyph
            local s = caseOf(raw)
            -- BM atlases are usually uppercase-only (UL uppercases all bm text)
            it.lab:setString(it.bm and s:upper() or s)
        end
    end
    update()
    self.engine:listen(cfg.ev, function() update() end)
end

-- Monthly calendar grid. UL semantics (createCalendar): header letters =
-- FIRST no_letters_header letters of each weekday (it's a count, not a
-- flag); dow_start default 1 = Monday; cells are (text_size+margin) boxes;
-- days BEFORE today dim to outside_opacity; today gets a color2 square with
-- brightness-inverted numeral; header uses color, dates color2; the grid is
-- anchored per `alignment` internally.
function Builders.calendar(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    -- rebuild() (raw "day" listener) destroys and recreates every child label;
    -- inside a baked run the day flip would free nodes whose draw commands a
    -- same-frame rebake already queued (GLThread UAF), and the dependency scan
    -- can't see the listener anyway (frozen grid)
    rec._noFlatten = true
    local color1 = L.color or "ffffff"
    local color2 = L.color2 or L.color or "ffffff"
    local align = (L.alignment and L.alignment ~= "") and L.alignment or "cc"
    local size = 10   -- refreshed at the top of rebuild (mkLabel captures it)
    local font = L.font or ""
    local isBM = font:sub(1, 3) == "bm:"
    local function mkLabel(str, colorHex)
        local lab
        if isBM then
            local fnt = self.base .. "fonts_bm/" .. font:sub(4) .. ".fnt"
            if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, str:upper()) end
            if lab then
                local ch = lab:getContentSize().height
                if ch > 0 then lab:setScale(1.6 * size / ch) end
                lab:setColor(c3b(colorHex))
                return lab
            end
        elseif font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(str, ttf, fontPx(size)) end
        end
        lab = lab or cc.Label:createWithSystemFont(str, "sans-serif", size)
        lab:setTextColor(c4b(colorHex, 100))
        return lab
    end
    local function daysIn(y, m)
        local d = { 31,28,31,30,31,30,31,31,30,31,30,31 }
        if m == 2 and (y % 4 == 0 and (y % 100 ~= 0 or y % 400 == 0)) then return 29 end
        return d[m]
    end
    local function rebuild(r)
        node:removeAllChildren()
        local marginX = r:evalNum("margin_x", 5)
        local marginY = r:evalNum("margin_y", 5)
        size = math.max(1, floor(r:evalNum("text_size", 10)))
        local outOp = r:evalNum("outside_opacity", 60)
        local dowStart = floor(r:evalNum("dow_start", 1))
        local nHdr = math.max(0, floor(r:evalNum("no_letters_header", 1)))
        local t = os.date("*t")
        local year, month, day = t.year, t.month, t.day
        local dim = daysIn(year, month)
        -- Zeller's congruence (UL): weekday of the 1st, 0=Sunday, shifted
        -- by dow_start
        local mAdj, yAdj = month, year
        if mAdj < 3 then mAdj = mAdj + 12; yAdj = yAdj - 1 end
        local kk, jj = yAdj % 100, floor(yAdj / 100)
        local hh = (1 + floor((13 * (mAdj + 1)) / 5) + kk + floor(kk / 4) + floor(jj / 4) - 2 * jj) % 7
        local firstDay = (((hh + 6) % 7) - dowStart + 7) % 7

        local cw, chh = size + marginX, size + marginY
        local totalW = 7 * cw
        local rows = math.ceil((dim + firstDay) / 7) + 1   -- +1 header row
        local totalH = rows * chh
        local offX = 0
        if align == "cc" or align == "tc" or align == "bc" then offX = -totalW / 2
        elseif align == "cr" or align == "tr" or align == "br" then offX = -totalW end
        local offY = 0
        if align == "cc" or align == "cl" or align == "cr" then offY = totalH / 2
        elseif align == "tc" or align == "tl" or align == "tr" then offY = totalH end

        -- weekday header
        for i = 1, 7 do
            local name = SER_DOWFULL[((i - 1 + dowStart) % 7) + 1]
            local txt = name:sub(1, nHdr)
            if L.transform == "uc" then txt = txt:upper() end
            local lab = mkLabel(txt, color1)
            lab:setPosition((i - 1) * cw + offX, offY)
            node:addChild(lab)
        end

        -- date grid
        local y0 = -(size + marginY)
        local d = 1
        local row = 0
        while d <= dim do
            for col = 0, 6 do
                if not (row == 0 and col < firstDay) and d <= dim then
                    local x = col * cw + offX
                    local y = y0 - row * chh + offY
                    local colHex = color2
                    if d == day then
                        -- today: color2 square behind a brightness-inverted numeral
                        local dn = cc.DrawNode:create()
                        local r0 = (size + 2) / 2
                        dn:drawSolidRect(cc.p(x - r0, y - r0), cc.p(x + r0, y + r0), c4f(color2, 100))
                        node:addChild(dn)
                        local c = WFColor.rgb(color2)
                        local bright = (c.r * 299 + c.g * 587 + c.b * 114) / 1000
                        colHex = bright > 128 and "000000" or "ffffff"
                    end
                    local lab = mkLabel(tostring(d), colHex)
                    lab:setPosition(x, y)
                    if d < day then lab:setOpacity(floor(clamp(outOp, 0, 100) * 2.55)) end
                    node:addChild(lab)
                    d = d + 1
                end
            end
            row = row + 1
        end
    end
    -- the grid rebuild already recreates every child, so live layout attrs
    -- (including text_size — labels are recreated at the new size) just re-run it
    rec:compositeNow(rebuild, { { "margin_x", "num" }, { "margin_y", "num" },
        { "text_size", "num" }, { "outside_opacity", "num" },
        { "dow_start", "num" }, { "no_letters_header", "num" } })
    self.engine:listen("day", function() rebuild(rec) end)
end

-- ── chart layer (ports UL WatchSkin:createChartLayer / renderChart) ──────────
-- Import-time parsers for the chart's packed attribute strings. Patterns are
-- fine here (build time only); the redraw path below is pattern-free.

local function fmtDp(v, dp) return string.format("%." .. dp .. "f", v) end

-- "40 to 20" → {max=40, min=20} (UL: FIRST value is max). Anything else → auto.
local function parseMinMax(s)
    if not s or s == "" then return { auto = true } end
    local a, b = s:match("^%s*(-?[%d%.]+)%s*to%s*(-?[%d%.]+)%s*$")
    if a then return { auto = false, max = tonumber(a), min = tonumber(b) } end
    return { auto = true }
end

-- Split preserving empty fields (label entries carry empties: "P||3|...").
local function splitKeep(s, sep)
    local out, from = {}, 1
    while true do
        local a = s:find(sep, from, true)
        if not a then out[#out + 1] = s:sub(from); break end
        out[#out + 1] = s:sub(from, a - 1)
        from = a + 1
    end
    return out
end

-- lines="type|style|width|color|opacity|count|dashW|dashGap`..."
local function parseChartLines(s)
    local lines = {}
    for entry in (s or ""):gmatch("[^`]+") do
        local p = {}
        for f in entry:gmatch("[^|]+") do p[#p + 1] = f end
        if #p >= 8 then
            lines[#lines + 1] = {
                lineType = p[1], style = p[2],
                width = tonumber(p[3]) or 1, color = p[4] or "ffffff",
                opacity = tonumber(p[5]) or 100, numLines = tonumber(p[6]) or 1,
                dashW = tonumber(p[7]) or 5, dashG = tonumber(p[8]) or 5,
            }
        end
    end
    return lines
end

-- Label text template → segment list ({lit=} / {key=}) so the runtime redraw
-- only concatenates (no gsub). Keys: value / value0dp / value1dp / value2dp /
-- index / min / max / avg / count.
local function compileLabelText(t)
    local segs, pos = {}, 1
    while true do
        local a, b, key = (t or ""):find("{([^{}]+)}", pos)
        if not a then break end
        if a > pos then segs[#segs + 1] = { lit = t:sub(pos, a - 1) } end
        segs[#segs + 1] = { key = key }
        pos = b + 1
    end
    if t and pos <= #t then segs[#segs + 1] = { lit = t:sub(pos) } end
    return segs
end

-- labels="position|alignment|maxCount|text|font|color|size|opacity|xOff|yOff`..."
local function parseChartLabels(s)
    local labels = {}
    if not s or s == "" then return labels end
    for _, entry in ipairs(splitKeep(s, "`")) do
        local p = splitKeep(entry, "|")
        if #p >= 10 then
            labels[#labels + 1] = {
                position = p[1], alignment = p[2] or "",
                maxCount = tonumber(p[3]) or 1,
                segs = compileLabelText(p[4]),
                color = (p[6] ~= "" and p[6]) or "ffffff",
                size = tonumber(p[7]) or 12,
                opacity = tonumber(p[8]) or 100,
                xOff = tonumber(p[9]) or 0, yOff = tonumber(p[10]) or 0,
            }
        end
    end
    return labels
end

local function drawDashedLine(dn, x1, y1, x2, y2, col, width, dashW, gapW)
    local dx, dy = x2 - x1, y2 - y1
    local len = math.sqrt(dx * dx + dy * dy)
    if len <= 0 then return end
    local ux, uy = dx / len, dy / len
    local dist, isDash = 0, true
    while dist < len do
        local seg = isDash and dashW or gapW
        local nextD = math.min(dist + seg, len)
        if isDash then
            dn:drawSegment(cc.p(x1 + ux * dist, y1 + uy * dist),
                           cc.p(x1 + ux * nextD, y1 + uy * nextD), width / 4, col)
        end
        dist = nextD
        isDash = not isDash
    end
end

-- Line or bar chart. UL colour roles: LINE charts stroke with color3 and dot
-- with color4; BAR charts fill with `color` faded from bottom_opacity at the
-- base to top_opacity at the bar tip (color2 is parsed by UL but never drawn).
-- Data points are semicolon-separated expressions compiled once; the chart
-- redraws when any referenced tag changes. min_max is "MAX to MIN" (UL) —
-- absent → auto range from the data.
function Builders.chart(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    local dn    = cc.DrawNode:create()   -- grid + data
    local dots  = cc.DrawNode:create()   -- line-chart markers, above the line
    local labNode = cc.Node:create()     -- labels topmost
    node:addChild(dn, 1); node:addChild(dots, 2); node:addChild(labNode, 3)

    local isLine     = (L.chart_type or "line") ~= "bar"
    local ap         = (L.alignment and ALIGN_AP[L.alignment]) or cc.p(.5, .5)
    local minMax     = parseMinMax(L.min_max)
    local chartLines = parseChartLines(L.lines)
    local chartLabels = parseChartLabels(L.labels)

    -- compile the data-point expressions; the composite fires on any dep
    local exprs, deps, seen = {}, {}, {}
    local function mergeDeps(d)
        if d.const then return end
        for _, t in ipairs(d.deps) do
            if not seen[t] then seen[t] = true; deps[#deps + 1] = t end
        end
    end
    for e in (L.data_points or ""):gmatch("[^;]+") do
        local d = WFExpr.compile(e, "num", self.engine, self.sandbox)
        exprs[#exprs + 1] = d
        mergeDeps(d)
    end
    rec:use("color", "sub"); rec:use("color3", "sub"); rec:use("color4", "sub")
    mergeDeps(rec:desc("color", "sub"))
    mergeDeps(rec:desc("color3", "sub"))
    mergeDeps(rec:desc("color4", "sub"))
    -- geometry attrs re-fire the same redraw (their values are read inside it)
    for _, a in ipairs({ "width", "height", "line_width", "marker_size",
                         "bar_thickness", "top_opacity", "bottom_opacity",
                         "radius", "corner_type" }) do
        rec:use(a, "num")
        mergeDeps(rec:desc(a, "num"))
    end

    local function labelText(segs, ctx)
        local out = {}
        for _, s in ipairs(segs) do
            out[#out + 1] = s.lit or tostring(ctx[s.key] or "")
        end
        return table.concat(out)
    end
    local function addLabel(def, str, x, y, anchorY)
        local lab = cc.Label:createWithSystemFont(str, "sans-serif", def.size)
        lab:setPosition(x, y)
        lab:setAnchorPoint(cc.p(0.5, anchorY))
        lab:setTextColor(c4b(def.color, def.opacity))
        labNode:addChild(lab)
    end

    local function redraw(r)
        local w, h = r:evalNum("width", 100), r:evalNum("height", 100)
        r.shapeW, r.shapeH = w, h
        node:setPosition((0.5 - ap.x) * w, (0.5 - ap.y) * h)
        local lineWidth  = r:evalNum("line_width", 10)
        local markerSize = r:evalNum("marker_size", 10)
        local barThick   = r:evalNum("bar_thickness", 50)
        local topOp      = r:evalNum("top_opacity", 100)
        local botOp      = r:evalNum("bottom_opacity", 100)
        local radius     = r:evalNum("radius", 10)
        local cor        = CORNERS[floor(r:evalNum("corner_type", 1))] or CORNERS[1]
        local ox, oy = -w / 2, -h / 2   -- centre-anchored box (UL offsets)
        dn:clear(); dots:clear(); labNode:removeAllChildren()
        local V = r.engine.V
        local vals, n = {}, 0
        for i, d in ipairs(exprs) do
            local v = d.const and d.value or d.fn(V)
            vals[i] = tonumber(v) or 0
            n = i
        end
        if n == 0 then return end
        local dMin, dMax, sum = math.huge, -math.huge, 0
        for i = 1, n do
            local v = vals[i]
            if v < dMin then dMin = v end
            if v > dMax then dMax = v end
            sum = sum + v
        end
        local avg = sum / n
        local minY = minMax.auto and dMin or minMax.min
        local maxY = minMax.auto and dMax or minMax.max
        local range = maxY - minY
        if range == 0 then range = 1 end
        local spacing = w / n
        local function px(i) return (i - 0.5) * spacing + ox end
        local function py(v) return ((v - minY) / range) * h + oy end

        -- grid / reference lines first
        for _, ln in ipairs(chartLines) do
            local col = c4f(ln.color, ln.opacity)
            local function hline(y)
                if ln.style == "Dashed" then
                    drawDashedLine(dn, ox, y, ox + w, y, col, ln.width, ln.dashW, ln.dashG)
                else
                    dn:drawSegment(cc.p(ox, y), cc.p(ox + w, y), ln.width / 4, col)
                end
            end
            if ln.lineType == "H" then
                local sp = h / (ln.numLines + 1)
                for i = 1, ln.numLines do hline(i * sp + oy) end
            elseif ln.lineType == "V" then
                local sp = w / ln.numLines
                for i = 1, ln.numLines do
                    local x = (i - 0.5) * sp + ox
                    if ln.style == "Dashed" then
                        drawDashedLine(dn, x, oy, x, oy + h, col, ln.width, ln.dashW, ln.dashG)
                    else
                        dn:drawSegment(cc.p(x, oy), cc.p(x, oy + h), ln.width / 4, col)
                    end
                end
            elseif ln.lineType == "MAX" then hline(py(dMax))
            elseif ln.lineType == "MIN" then hline(py(dMin))
            elseif ln.lineType == "AVG" then hline(py(avg))
            end
        end

        if isLine then
            local lcol = c4f(r:evalSub("color3", "ffffff"), 100)
            local dcol = c4f(r:evalSub("color4", "ffffff"), 100)
            for i = 1, n - 1 do
                dn:drawSegment(cc.p(px(i), py(vals[i])), cc.p(px(i + 1), py(vals[i + 1])),
                    lineWidth / 4, lcol)
            end
            for i = 1, n do
                dots:drawDot(cc.p(px(i), py(vals[i])), markerSize / 4, dcol)
            end
        else
            -- bars: single colour, opacity gradient bottom→top, rounded per
            -- corner via per-slice width adjustment (UL drawBarChart)
            local barW = math.min(barThick, spacing * 0.8)
            local cr = math.min(radius, barW / 2)
            local cHex = r:evalSub("color", "ffffff")
            local cRGB = WFColor.rgb(cHex)
            for i = 1, n do
                local x0 = px(i) - barW / 2
                local bh = ((vals[i] - minY) / range) * h
                if bh > 0 then
                    local slices = math.max(10, floor(bh / 2))
                    local sh = bh / slices
                    for s = 0, slices - 1 do
                        local t = s / math.max(1, slices - 1)
                        local op = botOp + (topOp - botOp) * t
                        local sy = oy + s * sh
                        local sx, sw = x0, barW
                        local function arcInset(dist)
                            local yc = cr - dist
                            if yc >= 0 and yc <= cr then
                                return cr - math.sqrt(math.max(0, cr * cr - yc * yc))
                            end
                            return 0
                        end
                        if bh > cr * 2 then
                            local dTop = (oy + bh) - sy
                            local dBot = sy - oy
                            if dTop <= cr then
                                local ins = arcInset(dTop)
                                if cor[1] == 1 then sx = sx + ins; sw = sw - ins end
                                if cor[2] == 1 then sw = sw - ins end
                            end
                            if dBot <= cr then
                                local ins = arcInset(dBot)
                                if cor[3] == 1 then sx = sx + ins; sw = sw - ins end
                                if cor[4] == 1 then sw = sw - ins end
                            end
                        elseif bh > cr then
                            local dTop = (oy + bh) - sy
                            if dTop <= cr then
                                local ins = arcInset(dTop)
                                if cor[1] == 1 then sx = sx + ins; sw = sw - ins end
                                if cor[2] == 1 then sw = sw - ins end
                            end
                        end
                        if sw > 0 then
                            dn:drawSolidRect(cc.p(sx, sy), cc.p(sx + sw, sy + sh),
                                cc.c4f(cRGB.r / 255, cRGB.g / 255, cRGB.b / 255, op / 100))
                        end
                    end
                end
            end
        end

        -- labels last (UL: system font; per-point anchor is bottom-centre)
        for _, def in ipairs(chartLabels) do
            if def.position == "P" then
                local step = math.max(1, math.ceil(n / def.maxCount))
                for i = 1, n, step do
                    local v = vals[i]
                    local ctx = {
                        value = v, value0dp = fmtDp(v, 0), value1dp = fmtDp(v, 1),
                        value2dp = fmtDp(v, 2), index = i - 1,
                        min = dMin, max = dMax, avg = avg, count = n,
                    }
                    addLabel(def, labelText(def.segs, ctx),
                        px(i) + def.xOff,
                        py(v) + def.yOff + (isLine and markerSize or 0) / 2 + 5, 0)
                end
            elseif def.position == "A" then
                local vert = (def.alignment == "L" or def.alignment == "R")
                for i = 0, def.maxCount - 1 do
                    local pct = i / math.max(1, def.maxCount - 1)
                    local v = vert and (minY + pct * (maxY - minY)) or i
                    local ctx = {
                        value = v, value0dp = fmtDp(v, 0), value1dp = fmtDp(v, 1),
                        value2dp = fmtDp(v, 2), min = minY, max = maxY,
                        avg = avg, count = n,
                    }
                    local x, y
                    if def.alignment == "L" then x = ox + def.xOff; y = pct * h + oy
                    elseif def.alignment == "R" then x = ox + w + def.xOff; y = pct * h + oy
                    elseif def.alignment == "T" then x = pct * w + ox; y = oy + h + def.yOff
                    else x = pct * w + ox; y = oy + def.yOff end
                    addLabel(def, labelText(def.segs, ctx), x, y, 0.5)
                end
            elseif def.position == "S" then
                local a = def.alignment
                local y = (a:find("T", 1, true) and oy + h)
                       or (a:find("B", 1, true) and oy) or 0
                local x = (a:find("L", 1, true) and ox)
                       or (a:find("R", 1, true) and ox + w) or 0
                local ctx = { min = dMin, max = dMax, avg = avg, count = n }
                addLabel(def, labelText(def.segs, ctx), x + def.xOff, y + def.yOff, 0.5)
            end
        end
    end

    if #deps > 0 then
        self.engine:bind(WFCompositeBinding.new(rec, redraw), deps)
    end
    -- same first-frame convention as compositeNow: draw once immediately
    -- (also sets rec.shapeW before the main loop's anchor shift reads it)
    local ok, err = pcall(redraw, rec)
    if not ok then print("[WFRender] chart draw: " .. tostring(err)) end
end

-- Ring of text labels (clock numerals). The label SET is static; geometry
-- (radius/text_size/squarify/show_every/angle span) and color are live —
-- a dependency change rebuilds the ring. Semantics port UL
-- WatchSkin:createTextRing:
--   • label sets: named (I-XII with "IIII", Mo-Su / Mon-Sun starting at Sun,
--     Jan-Dec) plus generic numeric ranges "A-B" and "A to B" (reversible);
--   • item k sits k steps PAST 12 o'clock, so the LAST value lands at 12 for
--     a full ring (classic "12 at top" clocks, "60 at top" minute rings);
--   • show_every keeps values that are multiples of it (5,10,…,60 on 1-60);
--     hide_text compares against the numeral (numeric) / 1-based position
--     (named sets);
--   • rotated_text: "y" = along the ring, "ru"/"ri" = flipped upright on the
--     bottom/top half respectively.
function Builders.text_ring(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    local font = L.font or ""
    local isBM = font:sub(1, 3) == "bm:"
    local isIcon = isMaterialIconsFont(font)
    local iconCache = isIcon and {} or nil
    local rotMode = L.rotated_text
    local hide = {}
    for h in (L.hide_text or ""):gmatch("-?%d+") do hide[tonumber(h)] = true end

    -- label set: labels[k] = text, vals[k] = value for show_every / hide_text
    local labels, vals = {}, {}
    local function addNamed(list)
        for k, s in ipairs(list) do labels[k] = s; vals[k] = k end
    end
    local rt = L.ring_type or "1-12"
    if rt == "I-XII" then
        addNamed({ "I","II","III","IIII","V","VI","VII","VIII","IX","X","XI","XII" })
    elseif rt == "Mo-Su" then
        addNamed({ "Su","Mo","Tu","We","Th","Fr","Sa" })
    elseif rt == "Mon-Sun" then
        addNamed({ "Sun","Mon","Tue","Wed","Thu","Fri","Sat" })
    elseif rt == "Jan-Dec" then
        addNamed({ "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec" })
    else
        local ra, rb = rt:match("^%s*(-?%d+)%s+to%s+(-?%d+)%s*$")
        if not ra then ra, rb = rt:match("^%s*(-?%d+)%s*%-%s*(-?%d+)%s*$") end
        ra, rb = tonumber(ra), tonumber(rb)
        if not ra or not rb then ra, rb = 1, 12 end
        for v = ra, rb, (ra <= rb) and 1 or -1 do
            labels[#labels + 1] = tostring(v)
            vals[#vals + 1] = v
        end
    end
    local n = #labels
    if n == 0 then return end

    if L.transform == "uc" then
        for k = 1, n do labels[k] = labels[k]:upper() end
    elseif L.transform == "lc" then
        for k = 1, n do labels[k] = labels[k]:lower() end
    end

    rec:compositeNow(function(r)
        node:removeAllChildren()
        local radius = r:evalNum("radius", 200)
        local size = math.max(1, floor(r:evalNum("text_size", 24)))
        local sq = r:evalNum("squarify", 0)
        local showEvery = math.max(1, floor(r:evalNum("show_every", 1)))
        local aStart, aEnd = r:evalNum("angle_start", 0), r:evalNum("angle_end", 360)
        local stepA = (aEnd - aStart) / n
        local rot = -aStart
        local colHex = r:evalSub("color", "ffffff")
        local function mkLabel(str)
            if isIcon then str = materialSubst(str, iconCache) end   -- icon name -> glyph
            local lab
            if isBM then
                local fnt = self.base .. "fonts_bm/" .. font:sub(4) .. ".fnt"
                if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, str:upper()) end
                if lab then
                    local ch = lab:getContentSize().height
                    if ch > 0 then lab:setScale(1.6 * size / ch) end
                    lab:setColor(c3b(colHex))
                    return lab
                end
            elseif font ~= "" then
                local ttf = self.base .. "fonts/" .. font .. ".ttf"
                if fileExists(ttf) then lab = cc.Label:createWithTTF(str, ttf, fontPx(size)) end
            end
            lab = lab or cc.Label:createWithSystemFont(str, "sans-serif", fontPx(size))
            lab:setTextColor(c4b(colHex, 100))
            return lab
        end
        -- UL quirk kept: bitmap-font rings place labels at radius/1.6
        local placeR = isBM and radius / 1.6 or radius

        for k = 1, n do
            if vals[k] % showEvery == 0 and not hide[vals[k]] then
                local sweep = rot - stepA * k + 90        -- polar degrees
                local th = rad(sweep)
                local rr = squarifyRadius(placeR, th, sq)
                local lab = mkLabel(labels[k])
                lab:setPosition(rr * cos(th), rr * sin(th))
                if rotMode == "y" or rotMode == "ru" or rotMode == "ri" then
                    local r0 = stepA * k - rot            -- tangent rotation
                    local pol = (rot - stepA * k) % 360
                    local flip = (pol > 90 and pol < 270)
                    if (rotMode == "ru" and flip) or (rotMode == "ri" and not flip) then
                        r0 = r0 + 180
                    end
                    lab:setRotation(r0)
                end
                node:addChild(lab)
            end
        end
    end, { { "radius", "num" }, { "text_size", "num" }, { "squarify", "num" },
           { "show_every", "num" }, { "angle_start", "num" }, { "angle_end", "num" },
           { "color", "sub" } })
end

-- Split a UTF-8 string into characters. Byte walk, no patterns — this runs
-- at rebuild time (curved text with tags rebuilds when the value changes).
local function utf8chars(s)
    local out, i, n = {}, 1, #s
    while i <= n do
        local b = s:byte(i)
        local len = (b >= 0xF0 and 4) or (b >= 0xE0 and 3) or (b >= 0xC0 and 2) or 1
        out[#out + 1] = s:sub(i, i + len - 1)
        i = i + len
    end
    return out
end

-- Text along a circular arc. Geometry matches WatchMaker v2 Studio (r0):
-- per-glyph widths measured off a whole-string label (getLetter), each char
-- advances by ITS OWN width as an arc slot (width*60deg/radius; spaces = 0.3*
-- fontSize), curve_dir "Up" arcs over the TOP of the circle centred on the
-- layer (x,y), "Down" arcs under the BOTTOM; glyphs rotate to the local
-- tangent. (Was ported from UL createTextCurved, which used the widest glyph's
-- atan(w/(radius+2h)) for every char and packed the string ~25% too tight.)
-- Rebuilt whenever the text value changes (a static build went stale on faces
-- curving "{dh23z}:{dmz}").
function Builders.text_curved(self, rec)
    local L = rec.L
    local node = cc.Node:create()
    rec.visual = node
    -- radius/size are upvalues refreshed per rebuild (mkLabel and the arc
    -- geometry read them live)
    local radius, size
    local function refreshGeom(r)
        radius = r:evalNum("radius", 200)
        size = math.max(1, floor(r:evalNum("text_size", 30)))
        -- container node reports a zero box (glyphs are children) — declare the
        -- arc's real extents for tap sizing AND the flatten canvas bound
        r.hitW = 2 * (radius + 2 * size)
        r.hitH = r.hitW
    end
    refreshGeom(rec)
    local font = L.font or ""
    local up = (L.curve_dir or "Up") ~= "Down"
    local isBM = font:sub(1, 3) == "bm:"
    local isIcon = isMaterialIconsFont(font)
    local iconCache = isIcon and {} or nil
    local function mkLabel(s)
        local lab
        if isBM then
            local fnt = self.base .. "fonts_bm/" .. font:sub(4) .. ".fnt"
            if fileExists(fnt) then lab = cc.Label:createWithBMFont(fnt, s:upper()) end
        elseif font ~= "" then
            local ttf = self.base .. "fonts/" .. font .. ".ttf"
            if fileExists(ttf) then lab = cc.Label:createWithTTF(s, ttf, fontPx(size)) end
        end
        return lab or cc.Label:createWithSystemFont(s, "sans-serif", fontPx(size))
    end
    local function rebuild(str)
        node:removeAllChildren()
        -- Material Icons -> PUA glyph on the WHOLE string, before the per-glyph
        -- split below (a ligature can't survive being cut into single chars).
        if isIcon then str = materialSubst(str, iconCache) end
        if L.transform == "uc" then str = str:upper()
        elseif L.transform == "lc" then str = str:lower() end
        local chars = utf8chars(str)
        local n = #chars
        if n == 0 then return end
        -- measure per-glyph widths off one whole-string label
        local meas = mkLabel(str)
        local bmScale = 1
        if isBM then
            local ch = meas:getContentSize().height
            if ch > 0 then bmScale = 1.6 * size / ch end
        end
        -- WatchMaker v2 Studio geometry (r0): each glyph subtends an arc slot of
        -- its OWN width -> slot = width_px * 60deg / radius = width * (pi/3)/radius rad.
        -- UL/older CloX used the WIDEST glyph's atan(w/(radius+2*h)) for EVERY glyph;
        -- the +2*h term plus atan (57.3 vs WM's flat 60 deg/px) packed the string
        -- ~25% too tightly ("closer than intended"). This ports WM's real per-glyph
        -- sweep. Radius, rotation, direction, and centering are unchanged.
        local DEG60 = math.pi / 3                 -- WM's 60-deg-per-pixel arc factor
        local spaceW = 0.3 * fontPx(size)         -- WM: space width = 0.3 * fontSize
        local a = {}
        local allAng = 0
        for i = 1, n do
            local w
            if chars[i] == " " then
                w = spaceW
            else
                local gt = meas.getLetter and meas:getLetter(i - 1)
                w = gt and (gt:getBoundingBox().width * bmScale) or (size * 0.6)
            end
            a[i] = w * DEG60 / radius
            allAng = allAng + a[i]
        end
        local coef = up and 1 or -1
        local rot0 = math.pi / 2 + coef * allAng / 2
        -- shader mode bakes WHITE glyphs — the layer color arrives as tint
        local col = rec.shaderBake and cc.c4b(255, 255, 255, 255)
                    or c4b(rec:evalSub("color", "ffffff"), 100)
        local ang = 0
        for i = 1, n do
            ang = ang + a[i] / 2
            if chars[i] ~= " " then
                local lab = mkLabel(chars[i])
                if isBM then lab:setScale(bmScale) else lab:setTextColor(col) end
                local th = rot0 - coef * ang
                lab:setPosition(coef * radius * cos(th), coef * radius * sin(th))
                lab:setRotation(90 - math.deg(th))
                node:addChild(lab)
            end
            ang = ang + a[i] / 2
        end
    end
    if L.shader and SHADER_UNIFORMS[L.shader] then
        -- per-glyph labels carry font-ATLAS texcoords: bake the arc into an
        -- RT covering the full circle bounds and shader THAT sprite
        rec.shaderBake = true
        -- lastText mirrors what the text binding applied (fmtnum-formatted),
        -- so a geometry redraw never re-derives a differently-formatted string
        local lastText = rec:evalText("text", "")
        rebuild(lastText)
        local sp, rebake = bakedSprite(self, node, function()
            local D = 2 * (radius + 2 * size) + 4   -- live: tracks radius/text_size
            return D, D
        end)
        rec.visual = sp
        bindShapeShader(rec, sp)
        rec:bindText("text", function(_, v)
            lastText = v
            rebuild(v)
            rebake()
        end)
        rec:compositeNow(function(r)
            refreshGeom(r)
            rebuild(lastText)
            rebake()
        end, { { "radius", "num" }, { "text_size", "num" } })
        return
    end
    rec:use("color", "sub")
    local lastText = ""
    rec:bindText("text", function(_, v) lastText = v; rebuild(v) end)
    rec:compositeNow(function(r)
        refreshGeom(r)
        rebuild(lastText)
    end, { { "radius", "num" }, { "text_size", "num" }, { "color", "sub" } })
end

-- ── tap handling ──────────────────────────────────────────────────────────────
-- Touches arrive from Android (GestureInterceptorLayout → LuaBridge.passTouch
-- → WatchfaceOnTouch → scene → tapAt), NOT from cocos touch listeners — the
-- Java gesture layer consumes raw events before the GL surface sees them.
-- Dispatch mirrors the UL reference renderer (WatchSkin:touch): test every
-- tappable, visible layer against its bounds with a generous 50-canvas-unit
-- radius fallback, and fire only the layer whose centre is closest.

-- UL's colour-switch palette (WatchSkinUtils.lua ucolor_map) for the
-- color_switch_* tap actions on faces without <ThemeSets>. Index 0 = the
-- face's own default colour.
local UCOLOR_MAP = {
    "5987b2","20b2aa","a10d09","0bf9ab","ffa419","e6f0fa","f0e6fa","faecf3",
    "f6f5f0","e6effa","e6e6fa","ffcccc","306a9f","fef65b","ffcccc","e6e6fa",
    "a1090d","a10900","12e6a0","660066","cce5cc","004c64","20b2aa","40e0d0",
    "7fffd4","fff8e7","f04b23","d8b2d8","008aa0","3b444b","ac8141","1e90ff",
    "838da6","8b0000","ffcccc","e5f2e5","cce5cc","fbe6fb","ffffcc","000000",
    "ffffff",
}

-- Run a tap-action string. `script:` bodies execute in the face sandbox — the
-- SAME environment as the global script, so functions/variables defined there
-- are callable and their writes propagate through the tag buckets. The
-- colour-switch family is renderer-level (WM_TAP_ACTIONS.md §6: no Java
-- handler); everything else routes to Android as a shortcut.
function WFRender:runAction(action)
    if not action or action == "" then return end
    if action:sub(1, 7) == "script:" then
        if self.script then
            self.script:runSnippet(WFExpr.unescape(action:sub(8)))
        end
    elseif action == "color_switch_next" or action == "color_switch_select" then
        self:colorSwitch(1)
    elseif action == "color_switch_prev" then
        self:colorSwitch(-1)
    elseif action == "sw_start_stop" then
        WFStopwatch.startStop(self.engine.clock.elapsed)
        self.engine:fire("ms")   -- repaint {sw*} layers immediately
    elseif action == "sw_reset" then
        WFStopwatch.reset()
        self.engine:fire("ms")
    elseif WatchfaceBridge and WatchfaceBridge.tapAction then
        -- "Allow tap action" gates only actions that invoke Android (app launch,
        -- URL, media keys…). The face-internal actions above (script:, color
        -- switch, stopwatch) are unaffected — they're the face's own logic.
        if not (WF.settings and WF.settings.allowTap == false) then
            WatchfaceBridge.tapAction(action)
        end
    end
end

-- Cycle the accent colour: through <ThemeSets> when the face defines them,
-- otherwise through UL's palette over {ucolor} (index 0 = face default).
function WFRender:colorSwitch(dir)
    if self.themes and #self.themes > 1 then
        self._themeIdx = ((self._themeIdx or 1) - 1 + dir) % #self.themes + 1
        self:setTheme(self._themeIdx)
        -- persisted with the face's script vars (reserved key) so the chosen
        -- theme survives face switches and app restarts
        if self.script then self.script.vars.__theme_idx = self._themeIdx end
        return
    end
    self:applyColorIdx((self._colorIdx or 0) + dir)
    if self.script then self.script.vars.__color_idx = self._colorIdx end
    print("[WFRender] color_switch → " .. self._colorIdx .. " (" .. self.theme.ucolor .. ")")
end

-- Apply a palette index (0 = face default) — shared by colorSwitch and the
-- load-time restore of a persisted selection.
function WFRender:applyColorIdx(idx)
    local n = #UCOLOR_MAP
    self._colorIdx = idx % (n + 1)   -- 0..n, 0 = default
    local u = (self._colorIdx == 0)
        and ((self.watch and self.watch.ucolor_default) or "ffffff")
        or UCOLOR_MAP[self._colorIdx]
    self.theme = { ucolor = u, ucolor2 = self.theme.ucolor2, ucolor3 = self.theme.ucolor3 }
    self.engine:publish("ucolor", u)
    self.engine:publish("ucolor_b", u)
end

-- Config-driven accent override (settings push: colorOverride = "rrggbb" hex
-- resolved by Java from the preset/picker/Material-You theme, or false for
-- the face's own color). Applying is just a {ucolor} publish, so dependent
-- layers repaint and baked runs re-capture through their normal slow deps —
-- no reload. Clearing falls back to the persisted tap selection / default.
function WFRender:setColorOverride(hex)
    if type(hex) ~= "string" or hex == "" then hex = nil
    else hex = hex:gsub("^#", ""):lower() end
    if hex == self._colorOverride then return end
    self._colorOverride = hex
    if not self.engine then return end
    if hex then
        self.theme = { ucolor = hex, ucolor2 = self.theme.ucolor2, ucolor3 = self.theme.ucolor3 }
        self.engine:publish("ucolor", hex)
        self.engine:publish("ucolor_b", hex)
    else
        self:applyColorIdx(self._colorIdx or 0)
    end
end

function WFRender:dispatchTap(rec)
    print("[WFRender] tap layer '" .. tostring(rec.type) .. "' action=" ..
          tostring(rec.L.tap_action))
    if rec.tap then rec.tap() end          -- image_tap frame advance
    self:runAction(rec.L.tap_action)
end

-- A layer is tappable when its whole holder chain is display-visible.
-- Opacity NEVER affects the touch zone (UL semantics): opacity-0 shapes are
-- the standard WM invisible-hotspot pattern, so transparent targets must stay
-- tappable — even ones a script faded out.
local function chainVisible(node, stop)
    while node and node ~= stop do
        if not node:isVisible() then return false end
        node = node:getParent()
    end
    return true
end

-- Half-extents of a layer's tap target, in holder-local (canvas) units.
function WFRender:hitExtent(rec)
    if rec.hitW then return rec.hitW / 2, (rec.hitH or rec.hitW) / 2 end
    if rec.shapeW then return rec.shapeW / 2, rec.shapeH / 2 end
    local v = rec.visual
    if v then
        local cs = v:getContentSize()
        if cs.width > 0 then
            return cs.width * rec.bsx / 2, cs.height * rec.bsy / 2
        end
    end
    local w, h = rec:evalNum("width", 0), rec:evalNum("height", 0)
    return w / 2, h / 2      -- 0 extents still get the 50-unit radius below
end

-- Canvas-space tap dispatch. (cx, cy) are 512-canvas coordinates (origin
-- centre, cocos Y-up) — the scene converts from screen pixels. Returns true
-- when a layer consumed the tap.
-- UL's tap-eligibility rule: evaluateDrawable coerces opacity with
-- `tonumber(op) or 0`, and WatchSkin:touch skips a zone only when that
-- number is -1. So multi-screen faces gate off-page zones with expressions
-- ending in `or -1`, while an ACTIVE page's zones often evaluate to FALSE
-- ("var_g==1 and 0 or var_j==0 and -1" on the open calculator) → 0 →
-- tappable. Opacity 0 hot spots are the norm; only -1 excludes.
local function tapEligible(rec)
    if rec.L.opacity == nil then return true end
    local d = rec:desc("opacity", "num")
    if d.const then return d.value ~= -1 end
    local ok, v = pcall(d.fn, rec.engine.V)
    v = (ok and tonumber(v)) or 0
    return v ~= -1
end

function WFRender:tapAt(cx, cy)
    local world = self.stage:convertToWorldSpace(cc.p(cx, cy))
    local best, bestDist
    for _, rec in ipairs(self.taps) do
        if chainVisible(rec.holder, self.stage) and tapEligible(rec) then
            local ok, lp = pcall(function() return rec.holder:convertToNodeSpace(world) end)
            if ok and lp then
                local hw, hh = self:hitExtent(rec)
                local dist = math.sqrt(lp.x * lp.x + lp.y * lp.y)
                -- inside the bounds, or within UL's 50-unit grace radius
                if (math.abs(lp.x) <= hw and math.abs(lp.y) <= hh) or dist < 50 then
                    if not best or dist < bestDist then best, bestDist = rec, dist end
                end
            end
        end
    end
    if best then
        local ok, err = pcall(function() self:dispatchTap(best) end)
        if not ok then print("[WFRender] tap dispatch: " .. tostring(err)) end
        return true
    end
    return false
end

-- ── generic holder transform bindings ─────────────────────────────────────────
-- One setter binding per dynamic transform attribute. Static values apply once
-- here and are never revisited; absent values match the cocos node defaults.

-- Holder position = authored (x, Y_SIGN*y) plus the gyro parallax offset.
-- UL (screen coords, y-down): x += sgy*gyro, y += sgx*(-gyro); in cocos y-up
-- both offsets come out positive: (base + sgy*g, base + sgx*g).
local function applyPos(r)
    local ox, oy = 0, 0
    if r.gyro and r.gyro ~= 0 then
        local V = r.engine.V
        ox = (tonumber(V.sgy) or 0) * r.gyro
        oy = (tonumber(V.sgx) or 0) * r.gyro
    end
    r.holder:setPosition((r.baseX or 0) + ox, (r.baseY or 0) + oy)
end

local function bindTransform(rec)
    -- gyro parallax: activating sgx/sgy switches the gyroscope stream on via
    -- the normal feature pipeline; the composite re-places the holder on
    -- every sensor push. A DYNAMIC gyro expression activates the stream too —
    -- it may be 0 now and become nonzero later (the stream stays on either
    -- way; that is the price of a live gyro=expression) — and rec.gyro itself
    -- is bound so applyPos always multiplies the current value.
    local gd = rec:desc("gyro", "num")
    if not gd.const or (tonumber(gd.value) or 0) ~= 0 then
        local e = rec.engine
        e:activate("sgx"); e:activate("sgy")
        e:bind(WFCompositeBinding.new(rec, applyPos), { "sgx", "sgy" })
    end
    rec.gyro = rec:evalNum("gyro", 0)
    rec:bindNum("gyro", 0, function(r, v) r.gyro = v; applyPos(r) end)
    rec:bindNum("x", 0, function(r, v) r.baseX = v; applyPos(r) end)
    -- authored y is screen-down; Y_SIGN flips into cocos Y-up (see top)
    rec:bindNum("y", 0, function(r, v) r.baseY = Y_SIGN * v; applyPos(r) end)
    rec:bindNum("rotation", 0, function(r, v) r.holder:setRotation(v) end)
    rec:bindNum("skew_x", 0, function(r, v) r.holder:setSkewX(v) end)
    rec:bindNum("skew_y", 0, function(r, v) r.holder:setSkewY(v) end)
    rec:bindNum("anim_scale_x", 100, function(r, v) r.holder:setScaleX(v / 100) end)
    rec:bindNum("anim_scale_y", 100, function(r, v) r.holder:setScaleY(v / 100) end)
    -- A NON-NUMERIC opacity result hides the layer (UL: tonumber(op) or 0).
    -- Multi-screen faces gate pages with "var_g==1 and 0 or var_l==1 and 100",
    -- which evaluates to FALSE when the page is inactive — defaulting to 100
    -- stacked every page on screen. A missing opacity attr stays opaque (the
    -- const-nil path never calls the setter).
    rec:bindNum("opacity", 0, function(r, v)
        r.holder:setOpacity(floor(clamp(v, 0, 100) * 2.55))
        r._opVis = v > 0
        r:applyVis()
    end)
end

-- ── load & build ──────────────────────────────────────────────────────────────

-- ── static-layer flattening ────────────────────────────────────────────────────
-- A run of consecutive frame-static layers is baked once into a cached
-- RenderTexture and drawn as ONE sprite, so per frame only the dynamic layers
-- (hands, live text) plus the composites are visited/drawn. The composite
-- rebakes on the union of its layers' SLOW deps (minute/hour/day/vars/battery/
-- weather/…) and on dim flips; a layer with a per-frame (ms/second) dep is never
-- merged. Multi-screen faces come out right for free: their page gate is a slow
-- dep (var_pantalla), so a page switch just re-bakes with the new visibility.
-- Every run is pcall-guarded, and nothing leaves the stage until the run's RT
-- exists — any failure falls back to normal drawing.
--
-- SAFETY CONTRACT (the crash class this design exists to avoid): an RT capture
-- enqueues render commands that only flush at END of frame, so (a) captures
-- run ONLY from the director's after-update hook (markBakeDirty), never
-- mid-update where a later event could free queued nodes; (b) RTs / off-graph
-- groups are released ONLY through deferRelease(); (c) layers that update
-- outside the descriptor system, or that carry a ClippingNode, set
-- rec._noFlatten in their builder.

local FLATTEN_MIN    = 2       -- fewer layers than this: not worth an RT
local FLATTEN_MAX_PX = 2048    -- skip pathological run bounds

-- Tag names that change every rendered frame (or ~every frame): a layer that
-- reads any of them cannot be baked. Sensor streams (accelerometer/gyro/
-- compass tags under sensor_* events) push at frame-ish rate — baking a layer
-- that reads them turns every push into a full run re-capture AND stutters
-- the motion; treat them as animation too.
local function fastTagSet(engine)
    local fast = {}
    for evn, ev in pairs(engine.events) do
        if evn == "ms" or evn == "second" or evn:sub(1, 7) == "sensor_" then
            for _, t in ipairs(ev.tags) do fast[t.name] = true end
        end
    end
    return fast
end

local function recHasFastDep(rec, fast)
    -- sensor-driven position: literal nonzero gyro, or a DYNAMIC gyro
    -- expression (may become nonzero later — its sgx/sgy composite is live,
    -- so the layer must never bake)
    local gd = rec._descs.gyro
    if (tonumber(rec.L.gyro) or 0) ~= 0 or (gd and not gd.const) then return true end
    for _, d in pairs(rec._descs) do
        if type(d) == "table" and not d.const and d.deps then
            for _, t in ipairs(d.deps) do
                -- tweens.* publish every frame while a script tween runs
                -- (beat pulses, screen slides) — that IS animation, even
                -- though the tag lives outside the ms/second events.
                if fast[t] or t:sub(1, 7) == "tweens." then return true end
            end
        end
    end
    return false
end

-- A layer is a direct stage child (vs. nested under a group holder) when it
-- declares no parentId — Pass 2 parents those straight onto the stage. Can't
-- compare holder:getParent() to stage: the Lua binding hands back a fresh
-- wrapper each call, so identity never matches (that returned 0 flattenable).
local function isStageChild(rec)
    local pid = rec.L.parentId
    return not (pid and pid ~= "")
end

local function recFlattenable(rec, fast)
    local L = rec.L
    if not rec.holder or not isStageChild(rec) then return false end
    if rec.type == "group" or rec.type == "video" then return false end
    if L.shader and L.shader ~= "" then return false end       -- shader may read time/uniforms
    if rec:isTapZone() then return false end                   -- must stay hit-testable
    if rec._noFlatten then return false end                    -- self-updating outside the descriptor system, or a ClippingNode
    if recHasFastDep(rec, fast) then return false end
    return true
end

-- Union of a run's slow (non-fast) dependency tags — the events that re-bake it.
local function runSlowDeps(run, fast)
    local set, list = {}, {}
    for _, rec in ipairs(run) do
        for _, d in pairs(rec._descs) do
            if type(d) == "table" and not d.const and d.deps then
                for _, t in ipairs(d.deps) do
                    if not fast[t] and not set[t] then set[t] = true; list[#list + 1] = t end
                end
            end
        end
    end
    return list
end

-- Square canvas (side, centred on the design origin) covering ONE run's drawn
-- content without clipping. Node AABBs alone are NOT enough: DrawNode layers
-- (shape/ring/chart) and container visuals (curved-text glyphs are children)
-- report a zero box — use hitExtent (shapeW / contentSize×baseScale / declared
-- width+height) radially, plus the visual's box (anchor offsets, rotation-safe
-- via the radial norm), scaled by the holder, offset by the holder position.
-- Floor at the design half-diagonal so anything drawn within the 512 canvas is
-- covered even when a layer under-reports its extents (the pre-per-run shared
-- canvas gave the same guarantee).
local DESIGN_HALF_DIAG = math.ceil(512 * math.sqrt(2) / 2)   -- 363
local function runCanvas(self, run)
    local reach = DESIGN_HALF_DIAG
    for _, rec in ipairs(run) do
        local hw, hh = 0, 0
        local ok, w, h = pcall(function() return self:hitExtent(rec) end)
        if ok and w then hw, hh = w, h or w end
        local rl = math.sqrt(hw * hw + hh * hh)
        local v = rec.visual
        if v then
            local b = v:getBoundingBox()
            local mx = math.max(math.abs(b.x), math.abs(b.x + b.width))
            local my = math.max(math.abs(b.y), math.abs(b.y + b.height))
            rl = math.max(rl, math.sqrt(mx * mx + my * my))
        end
        local hs = math.max(rec.holder:getScaleX(), rec.holder:getScaleY(), 1)
        reach = math.max(reach,
            math.abs(rec.holder:getPositionX()) + rl * hs,
            math.abs(rec.holder:getPositionY()) + rl * hs)
    end
    return math.ceil(reach * 2) + 16      -- pad swallows effect-copy offsets
end

-- Bake one run (holders currently on stage, in z order) into a cached RT sprite.
-- `canvas` is a square whose centre is the design origin, so the baked sprite
-- sits at stage-local (0,0) and inherits the stage transform. Everything that
-- can fail (RT allocation) happens BEFORE any holder leaves the stage, so a
-- failure leaves the run rendering normally.
function WFRender:bakeRun(run, zLo, fast, canvas)
    local stage = self.stage
    local rt = cc.RenderTexture:create(canvas, canvas)
    if not rt then
        print("[WFRender] bakeRun: RT create failed (" .. canvas .. "px) — run left live")
        return false
    end
    local rtSlot = #self.retained + 1
    rt:retain(); self.retained[rtSlot] = rt
    -- Coverage-accumulating alpha: a run flattens several layers, so an opaque
    -- layer under a semi-transparent one must keep alpha 1 in the RT — otherwise
    -- the baked sprite composites semi-transparent and the background bleeds
    -- through (only visible with flatten on). See RenderTexture::setAlphaAccumulate.
    if rt.setAlphaAccumulate then rt:setAlphaAccumulate(true) end
    -- Off-graph container: design origin (0,0) maps to the RT centre.
    local grp = cc.Node:create()
    grp:setPosition(canvas / 2, canvas / 2)
    grp:retain(); self.retained[#self.retained + 1] = grp
    for _, rec in ipairs(run) do
        rec.holder:retain()
        rec.holder:removeFromParent()
        grp:addChild(rec.holder)
        rec.holder:release()
        rec._flattened = true
    end

    local sp = cc.Sprite:create()
    local function rebake()
        if not rt then return end
        rt:beginWithClear(0, 0, 0, 0)
        grp:visit()
        rt:endToLua()
        sp:setTexture(rt:getSprite():getTexture())
        sp:setTextureRect(cc.rect(0, 0, canvas, canvas))
        sp:setFlippedY(true)                    -- RT textures are y-flipped
        local vis = 0
        for _, rec in ipairs(run) do if rec.holder:isVisible() then vis = vis + 1 end end
        print(string.format("[WFRender] bake exec z%d: %d/%d holders visible, tex=%s grpVis=%s",
            zLo, vis, #run, tostring(rt:getSprite():getTexture()), tostring(grp:isVisible())))
        -- Debug: `touch files/dump_rt` (run-as) saves every run capture as a
        -- PNG in the writable path for pixel-level inspection.
        if fileExists(cc.FileUtils:getInstance():getWritablePath() .. "dump_rt") then
            rt:saveToFile("bake_z" .. zLo .. ".png", true)
            for _, mrec in ipairs(run) do
                local v = mrec.visual
                if mrec.type == "text" and v then
                    local bf = v.getBlendFunc and v:getBlendFunc() or nil
                    local tc = v.getTextColor and v:getTextColor() or nil
                    print(string.format(
                        "[WFRender]   dump z%d %d:text dispOp=%s ownOp=%s holderOp=%s blend=%s/%s textA=%s",
                        zLo, mrec.index,
                        tostring(v.getDisplayedOpacity and v:getDisplayedOpacity()),
                        tostring(v:getOpacity()), tostring(mrec.holder:getOpacity()),
                        tostring(bf and bf.src), tostring(bf and bf.dst),
                        tostring(tc and tc.a)))
                end
            end
        end
        if vis == 0 then
            -- all-hidden capture = black sprite: name each member's gate so the
            -- mechanism is identifiable from one logcat line per layer
            for _, rec in ipairs(run) do
                print(string.format("[WFRender]   z%d gate %d:%s disp=%s op=%s holderVis=%s",
                    zLo, rec.index, tostring(rec.type), tostring(rec._dispVis),
                    tostring(rec._opVis), tostring(rec.holder:isVisible())))
            end
        end
    end
    sp:setPosition(0, 0)                         -- design origin = stage-local origin
    stage:addChild(sp, zLo)

    -- NEVER capture mid-update: another event later in the same scheduler pass
    -- can destroy/mutate member nodes whose queued draw commands would then
    -- dangle at the end-of-frame flush (text_curved/chart rebuilds — GLThread
    -- SIGSEGV). Mark dirty instead; the after-update listener performs one
    -- capture per frame against settled state — including this initial one,
    -- later this same frame.
    local function requestBake() self:markBakeDirty(rebake) end
    requestBake()
    -- EGL context loss: the RT's backend texture/FBO names belong to the DEAD
    -- context — re-rendering into the old object only works when the new
    -- context reuses the same names (that's the unreliability). RECREATE the
    -- RT, then re-capture.
    self._ctxRestore[#self._ctxRestore + 1] = function()
        local nrt = cc.RenderTexture:create(canvas, canvas)
        if nrt then
            if rt then self:deferRelease(rt) end
            rt = nrt
            rt:retain(); self.retained[rtSlot] = rt   -- replaces the old slot: no double release
            if rt.setAlphaAccumulate then rt:setAlphaAccumulate(true) end   -- carry over to the recreated RT
            requestBake()
        else
            -- old (stale) RT stays in retained for face-clear release; baking
            -- into a dead-context FBO is what crashes drivers, so just no-op
            rt = nil
            print("[WFRender] ctx restore: run RT recreate failed (" .. canvas .. "px)")
        end
    end

    -- Re-bake on any slow dep (page switch, minute/hour tick, battery,
    -- weather…) and on dim flips. The dirty set dedups the dim case (repaint
    -- fires the binding AND dimRedraws marks it) to a single capture, taken
    -- after the static color_dim members redrew.
    local deps = runSlowDeps(run, fast)
    if #deps > 0 then
        self.engine:bind(WFCompositeBinding.new(run[1], requestBake), deps)
    end
    self.dimRedraws[#self.dimRedraws + 1] = requestBake
    return true
end

-- Post-load pass: merge runs of consecutive frame-static stage layers.
function WFRender:flattenStatics()
    local stage = self.stage
    if not stage then return end
    local fast = fastTagSet(self.engine)

    -- Give every stage child an explicit z = document order, so a baked sprite
    -- inserted at a run's start z keeps the exact layering (holders were added
    -- at z 0 in order, i.e. insertion order).
    local zOf = {}
    for i, rec in ipairs(self.recs) do
        if rec.holder and isStageChild(rec) then
            rec.holder:setLocalZOrder(i)
            zOf[rec] = i
        end
    end

    -- Maximal runs of consecutive flattenable stage children (a dynamic/group/
    -- tap/video layer between two statics breaks the run, preserving z-order).
    local runs, cur = {}, nil
    for _, rec in ipairs(self.recs) do
        if rec.holder and isStageChild(rec) then
            if recFlattenable(rec, fast) then
                if not cur then cur = {}; runs[#runs + 1] = cur end
                cur[#cur + 1] = rec
            else
                cur = nil
            end
        end
    end

    local baked, merged, cand, skipped = 0, 0, 0, 0
    for _, run in ipairs(runs) do
        if #run >= FLATTEN_MIN then
            cand = cand + 1
            -- Size the RT to THIS run; a run whose bounds exceed the cap is
            -- skipped outright (clamping would clip its content, and a watch
            -- GPU can't afford stray 2048² RGBA allocations).
            local canvas = runCanvas(self, run)
            if canvas > FLATTEN_MAX_PX then
                skipped = skipped + 1
                print(string.format("[WFRender] flatten: run of %d layers skipped (%dpx > %dpx cap)",
                    #run, canvas, FLATTEN_MAX_PX))
            else
                local members = {}
                for _, rec in ipairs(run) do members[#members + 1] = rec.index .. ":" .. tostring(rec.type) end
                print(string.format("[WFRender] flatten run z%d canvas=%d [%s]",
                    zOf[run[1]], canvas, table.concat(members, " ")))
                local ok, res = pcall(function() return self:bakeRun(run, zOf[run[1]], fast, canvas) end)
                if ok and res then baked = baked + 1; merged = merged + #run
                elseif not ok then print("[WFRender] bakeRun error: " .. tostring(res)) end
            end
        end
    end
    print(string.format("[WFRender] flatten: %d/%d run(s) baked (%d candidate runs total, %d oversize-skipped), %d layers merged",
        baked, cand, #runs, skipped, merged))
    self._flatMerged, self._flatRuns = merged, baked
end

-- xml: watch.xml content. baseDir: extract dir ending in "/" (has images/ etc).
function WFRender:load(xml, baseDir)
    -- the new face's engine clock restarts elapsed at 0; a running stopwatch
    -- keeps its accumulated time across the switch (UL: one global stopwatch).
    -- The very first load of a process instead restores the persisted state
    -- (wall-clock anchored, so a running stopwatch kept counting while dead).
    if self.engine then WFStopwatch.rebase(self.engine.clock.elapsed)
    else WFStopwatch.restoreOnce(0) end
    self:clear()
    self:attachListeners()
    self.base = baseDir
    local watch, themes, layers = parseXml(xml)
    self.watch = watch
    self.bgColor = watch.bg_color or "000000"

    -- diagnostics: raw tag occurrences vs successfully parsed, plus xml head
    local rawLayers = select(2, xml:gsub("<[Ll]ayer", ""))
    local nWatchAttrs = 0; for _ in pairs(watch) do nWatchAttrs = nWatchAttrs + 1 end
    print(string.format("[WFRender] xml=%d bytes, raw<Layer>=%d, parsed=%d, watchAttrs=%d, head=%s",
        #xml, rawLayers, #layers, nWatchAttrs, (xml:gsub("[\r\n]", " ")):sub(1, 110)))

    -- theme: first ThemeSet, or fall back to ucolor_default
    if themes[1] then
        self.theme = { ucolor = themes[1].ucolor, ucolor2 = themes[1].ucolor2 or themes[1].ucolor,
                       ucolor3 = themes[1].ucolor3 or themes[1].ucolor }
    else
        local u = watch.ucolor_default or "ffffff"
        self.theme = { ucolor = u, ucolor2 = u, ucolor3 = u }
    end
    self.themes = themes

    -- engine + sandbox + global script — BEFORE any layer compiles, so script
    -- variables resolve inside attribute expressions and {var} tags activate
    -- with real initial values.
    self.engine  = WFEngine.new()
    self.sandbox = WFSandbox.new()
    self.script  = WFScript.new(self.engine, self.sandbox,
                                function(a) self:runAction(a) end)
    WFBinding.onError = function(context, err)
        if WatchfaceBridge and WatchfaceBridge.reportError then
            WatchfaceBridge.reportError(context, err)
        end
    end
    -- Attribute compile failures surface through the same dialog as runtime
    -- errors (first one per face load; the rest are in logcat). The face
    -- still renders via the template/literal fallback.
    WFExpr.onError = function(context, err)
        if WFBinding.errorReported then return end
        WFBinding.errorReported = true
        if WatchfaceBridge and WatchfaceBridge.reportError then
            WatchfaceBridge.reportError("Expression compile failed (" .. context .. ")", err)
        end
    end
    self:publishTheme()
    -- Persist key = md5(xml + global script). Content-addressed so a face keeps
    -- its saved variables regardless of package path. Restore BEFORE the script
    -- runs so it can resume prior state.
    do
        local scriptSrc = ""
        local sp = baseDir .. "scripts/script.txt"
        local fu = cc.FileUtils:getInstance()
        if fu:isFileExist(sp) then scriptSrc = fu:getStringFromFile(sp) or "" end
        -- getStringMD5Hash is a method binding — must be called with ':' so the
        -- utils table is arg #1 (dot-calling passes the string as arg #1 → error).
        local utils = (ax and ax.utils) or (cc and cc.utils)
        if utils and utils.getStringMD5Hash then
            local ok, key = pcall(function() return utils:getStringMD5Hash(xml .. scriptSrc) end)
            self._persistKey = ok and key or nil
        else
            self._persistKey = nil
        end
        self.script._persistKey = self._persistKey
        print("[WFPersist] load key=" .. tostring(self._persistKey) ..
              " scriptBytes=" .. tostring(#scriptSrc))
    end
    self.script:load(baseDir)
    -- Restore persisted variables AFTER the script's top-level chunk runs, so a
    -- face that assigns its variables unconditionally on load (`x = 0`) doesn't
    -- clobber the saved state. Runs before layers compile, so bindings pick up
    -- the restored values.
    pcall(function() self.script:restoreVars() end)
    -- Re-apply a persisted colour/theme selection. color_switch state lives on
    -- the RENDERER (not in the face's own script variables), so it rides along
    -- in the same per-face store under reserved __color_idx / __theme_idx keys
    -- — written by colorSwitch, applied here before any layer compiles.
    do
        local v  = self.script.vars
        local ti = tonumber(v.__theme_idx)
        local ci = tonumber(v.__color_idx)
        if ti and self.themes and #self.themes > 1 and self.themes[ti] then
            self._themeIdx = ti
            self:setTheme(ti)
        elseif ci and ci > 0 then
            self:applyColorIdx(ci)
        end
        -- config accent override wins over the persisted tap selection
        self._colorOverride = nil
        local ov = WF and WF.settings and WF.settings.colorOverride
        if type(ov) == "string" and ov ~= "" then self:setColorOverride(ov) end
    end
    self.script:attach()
    self.script:onNeedFrameChanged(function()
        self:updateNeedFrame()
    end)

    -- Pass 1: create a holder per layer, build its visual + content bindings.
    local holders = {}            -- key → holder node
    for i, L in ipairs(layers) do
        local key = (L.id and L.id ~= "" and L.id) or ("__" .. i)
        local holder = cc.Node:create()
        holder:setCascadeOpacityEnabled(true)
        local rec = WFLayerRec.new(self, L, holder, key, i)
        local builder = Builders[L.type]
        if builder then
            local ok, err = pcall(builder, self, rec)
            if not ok then print("[WFRender] build " .. tostring(L.type) .. ": " .. tostring(err)) end
        end
        if rec.visual then
            rec.visual:setCascadeOpacityEnabled(true)
            local al = L.alignment
            local ap = (al and ALIGN_AP[al]) or cc.p(.5, .5)
            if rec.shapeW then
                -- DrawNode shapes draw centred; shift them so `ap` maps to the
                -- anchor point. Lets shape hands pivot at their base (e.g. bc).
                rec.visual:setPosition((0.5 - ap.x) * rec.shapeW, (0.5 - ap.y) * rec.shapeH)
            elseif al and al ~= "" and ALIGN_AP[al] then
                rec.visual:setAnchorPoint(ALIGN_AP[al])
            end
            rec.visual:setScaleX(rec.bsx); rec.visual:setScaleY(rec.bsy)
            holder:addChild(rec.visual)
        end
        -- transform attributes → setter bindings (constants apply immediately;
        -- skew_x/skew_y ride along in bindTransform like rotation)
        local ok, err = pcall(bindTransform, rec)
        if not ok then print("[WFRender] transform " .. tostring(L.type) .. ": " .. tostring(err)) end

        holders[key] = holder
        self.recs[#self.recs + 1] = rec
    end

    -- Pass 2: parent holders (handles arbitrary 2.0 ordering) in document order.
    for _, rec in ipairs(self.recs) do
        local pid = rec.L.parentId
        local parent = (pid and pid ~= "" and holders[pid]) or self.stage
        parent:addChild(rec.holder)
    end

    -- Pass 3: visibility + tap-target index, now that holders are parented.
    for _, rec in ipairs(self.recs) do
        rec._dispVis = visibleIn(rec.L.display, self.dim)
        rec:applyVis()
        if (rec.L.tap_action and rec.L.tap_action ~= "") or rec.tap then
            self.taps[#self.taps + 1] = rec
        end
    end

    -- Initial paint: run every binding once against the freshly-computed tag
    -- values, then wire the world.
    self.engine:setClock(os.date("*t"), 0, 0)
    self.engine:repaint()

    self.needFrame  = self.engine:eventActive("ms") or self.script:needsFrame()
        or (#self.videos > 0 and self._videosRunning ~= false)   -- playing video = continuous work
    self.needSecond = self.engine:eventActive("second")
    self:buildFeatures()
    self:registerAsyncListeners()

    local nBind = #self.engine.bindings
    print(string.format("[WFRender] loaded %d layers (%d bindings, frame=%s sec=%s), theme=%s",
        #self.recs, nBind, tostring(self.needFrame), tostring(self.needSecond),
        tostring(self.theme.ucolor)))
    -- needFrame/needSecond forensics: name what sits in the fast buckets, so a
    -- face that "should be static" but renders hot can be diagnosed from logcat.
    for _, evName in ipairs({ "ms", "second" }) do
        local ev = self.engine.events[evName]
        if ev and not ev:isEmpty() then
            local names = {}
            for _, tg in ipairs(ev.tags) do names[#names + 1] = tg.name end
            print(string.format("[WFRender] fast bucket '%s': tags=[%s] listeners=%d",
                evName, table.concat(names, ","), #ev.listeners))
        end
    end
    if self.script and self.script.needsFrame and self.script:needsFrame() then
        print("[WFRender] script:needsFrame()=true (on_millisecond or live tween)")
    end
    -- Layer-merge optimization is a per-face config toggle (settings push:
    -- optimizeLayers, default on). `touch files/no_flatten` (run-as) remains as
    -- a device-wide debug kill switch. Disabled either way: every layer renders
    -- live; the opacity cull still applies.
    self._flatMerged, self._flatRuns = 0, 0
    if WF and WF.settings and WF.settings.optimizeLayers == false then
        print("[WFRender] flatten disabled by face config — all layers live")
    elseif fileExists(cc.FileUtils:getInstance():getWritablePath() .. "no_flatten") then
        print("[WFRender] flatten disabled by no_flatten flag — all layers live")
    else
        pcall(function() self:flattenStatics() end)
    end
    return true
end

-- Push the active theme colours through the engine — buckets of every layer
-- referencing {ucolor}/{ucolor2}/{ucolor3} run immediately.
function WFRender:publishTheme()
    local e = self.engine
    e:publish("ucolor",   self.theme.ucolor or "ffffff")
    e:publish("ucolor2",  self.theme.ucolor2 or "ffffff")
    e:publish("ucolor3",  self.theme.ucolor3 or "ffffff")
    e:publish("ucolor_b", self.theme.ucolor or "ffffff")
end

-- Which async data sources this face uses (drives the Java feature toggles).
function WFRender:buildFeatures()
    local e = self.engine
    self.features = {
        weather = e:eventActive("weather"),
        health = {
            steps     = e:eventActive("health_steps"),
            heartRate = e:eventActive("health_heartRate"),
            spo2      = e:eventActive("health_spo2"),
            sleep     = e:eventActive("health_sleep"),
            stress    = e:eventActive("health_stress"),
        },
        sensor = {
            accelerometer = e:eventActive("sensor_accelerometer"),
            gyroscope     = e:eventActive("sensor_gyroscope"),
            compass       = e:eventActive("sensor_compass"),
            barometer     = e:eventActive("sensor_barometer"),
        },
    }
end

-- Subscribe to the bridge categories this face reads: engine event names that
-- aren't clock events ARE bridge categories (wf_tagdefs uses the WF.* names).
-- A push fires that category's event — its tags recompute and only the layers
-- indexed under the changed tags re-apply. Torn down in clear() on switch.
function WFRender:registerAsyncListeners()
    self._asyncHandlers = self._asyncHandlers or {}
    if not (WatchfaceBridge and WatchfaceBridge.onChange) then return end
    local engine = self.engine
    for _, name in ipairs(engine:activeEventNames()) do
        if not CLOCK_EVENTS[name] and not self._asyncHandlers[name] then
            local handler = function() engine:fire(name) end
            WatchfaceBridge.onChange(name, handler)
            self._asyncHandlers[name] = handler
        end
    end
end

-- ── per-tick driving (called by the scene) ────────────────────────────────────

-- ticks = {frame,second,minute,hour,day} booleans. Slow events fire first so
-- the ms pass sees fresh minute/second values on boundary frames.
function WFRender:tick(t, subsec, elapsed, ticks)
    local e = self.engine
    if not e then return end
    -- Watchdog: the after-update listener drains _dirtyBakes at the end of
    -- EVERY frame, so any mark still pending when the next tick starts was
    -- missed (listener dead / event not dispatched that frame — whatever the
    -- cause, a baked run would stay stale or black forever). Drain here and
    -- leave a loud trace so the failing leg is identifiable from logcat.
    -- Marks pending at tick time are NOT proof the listener is dead: touch
    -- input runs on the GL thread BETWEEN frames (queueEvent), so a tap that
    -- marks rebakes lands after frame N's after-update and is seen here at
    -- tick N+1 — this frame's after-update will drain it normally. The real
    -- liveness signal is the beacon: after-update fires EVERY frame, so if it
    -- hasn't fired since the previous tick, the dispatcher lost the listener —
    -- and the renderer-recreated listener died WITH it. Any EGL recreation in
    -- that dead window was missed: rebakes keep "succeeding" into dead-context
    -- FBOs (valid texture pointer, healthy log, black pixels — permanently).
    -- Re-register both listeners and force one full RT restore pass.
    if not self._afterUpdateSeen and (#self._dirtyBakes > 0 or self._needCtxRestore) then
        print("[WFRender] bake watchdog: after-update listener dead (" ..
              #self._dirtyBakes .. " mark(s) pending) — re-attaching and restoring")
        self:attachListeners()
        self._needCtxRestore = true
        self:_serviceBakes()
    end
    self._afterUpdateSeen = false
    require("wf_dimstate").tick(elapsed)
    e:setClock(t, subsec, elapsed)
    if ticks.day    then e:fire("day")    end
    if ticks.hour   then e:fire("hour")   end
    if ticks.minute then e:fire("minute") end
    if ticks.second then e:fire("second") end
    if ticks.frame  then e:fire("ms")     end
end

function WFRender:setDim(dim)
    if self.dim == dim then return end
    self.dim = dim
    require("wf_dimstate").setDim(dim)
    for _, rec in ipairs(self.recs) do
        rec._dispVis = visibleIn(rec.L.display, dim)
        rec:applyVis()
    end
    -- dim-aware content repaints from live values; static color_dim layers
    -- aren't in any bucket, so re-run them explicitly
    if self.engine then self.engine:repaint() end
    for _, fn in ipairs(self.dimRedraws) do fn() end
    -- videos are power hogs: decode only in bright mode (routed through
    -- setVideosRunning so the running-state flag stays consistent with the
    -- host pause/resume path)
    self:setVideosRunning(not dim)
end

-- Map layers share ONE bridge listener for the "map" tile pushes; it lives in
-- _asyncHandlers so clear() tears it down with the rest.
function WFRender:registerMapRec(rec)
    self._mapRecs[#self._mapRecs + 1] = rec
    self._asyncHandlers = self._asyncHandlers or {}
    if not self._asyncHandlers["map"] and WatchfaceBridge and WatchfaceBridge.onChange then
        local recs = self._mapRecs
        local handler = function(data)
            for _, r in ipairs(recs) do
                if r.onMapPush then r.onMapPush(data) end
            end
        end
        WatchfaceBridge.onChange("map", handler)
        self._asyncHandlers["map"] = handler
    end
end

-- Switch to the Nth theme (1-based); {ucolor*} buckets re-apply immediately.
function WFRender:setTheme(idx)
    local th = self.themes and self.themes[idx]
    if not th then return end
    self._themeIdx = idx
    self.theme = { ucolor = th.ucolor, ucolor2 = th.ucolor2 or th.ucolor,
                   ucolor3 = th.ucolor3 or th.ucolor }
    if self.engine then self:publishTheme() end
end

return WFRender
