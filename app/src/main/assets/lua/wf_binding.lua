-- wf_binding.lua — the bucket entries: WFBinding, WFSetterBinding,
-- WFCompositeBinding, plus the no-pattern colour helpers the render path uses.
--
-- A binding is one (layer, property) pair living in the update bucket of every
-- tag its compiled expression depends on. When a tag changes, its bucket fires
-- and each binding re-evaluates and applies:
--
--   WFSetterBinding    one node property (position x, rotation, text string,
--                      sprite tint, …). Evaluates its compiled expression,
--                      coerces per kind, and skips the cocos call when the
--                      result didn't move (a binding with two deps fires when
--                      EITHER changes; the other may leave the result equal).
--
--   WFCompositeBinding a full re-render of a procedural layer (DrawNode rings,
--                      progress bars, markers, cond-image frame selection).
--                      One instance sits in EVERY dep tag's bucket; the
--                      generation counter in fire() makes it run at most once
--                      per event even when several of its tags moved together.
--
-- Failures are isolated exactly like the old _evalList: warn once per binding,
-- surface the first failure per face through WFBinding.onError, never let one
-- bad layer take the render loop down.

local WFBinding = {}
WFBinding.__index = WFBinding

-- Set by the renderer per face load: fn(context, err) → surfaced to Android.
WFBinding.onError = nil
WFBinding.errorReported = false

function WFBinding.newBase(cls, rec)
    return setmetatable({ rec = rec, gen = -1 }, cls)
end

function WFBinding:fire(gen)
    if self.gen == gen then return end   -- already ran during this event
    self.gen = gen
    local ok, err = pcall(self.apply, self)
    if not ok and not self._warned then
        self._warned = true
        local what = tostring(self.rec and self.rec.type or "?")
        print("[WFBinding] apply '" .. what .. "': " .. tostring(err))
        if WFBinding.onError and not WFBinding.errorReported then
            WFBinding.errorReported = true
            WFBinding.onError("Layer '" .. what .. "' update", err)
        end
    end
end

-- ── WFSetterBinding ───────────────────────────────────────────────────────────

local WFSetterBinding = setmetatable({}, { __index = WFBinding })
WFSetterBinding.__index = WFSetterBinding

-- engine: WFEngine (for V) · rec: layer record · expr: WFExpr descriptor
-- kind: "num" (tonumber coercion + default) | "text" (tostring) | "raw"
-- setter: function(rec, value)
function WFSetterBinding.new(engine, rec, expr, kind, default, setter)
    local self = WFBinding.newBase(WFSetterBinding, rec)
    self.V       = engine.V
    self.fn      = expr.fn
    self.kind    = kind
    self.default = default
    self.setter  = setter
    self.last    = nil
    return self
end

function WFSetterBinding:apply()
    local v = self.fn(self.V)
    if self.kind == "num" then
        v = tonumber(v) or self.default
    elseif self.kind == "text" then
        -- WM-style number display (see WFExpr.fmtnum): "263", not "263.0"
        v = require("wf_expr").fmtnum(v)
    end
    if v == self.last then return end
    self.last = v
    self.setter(self.rec, v)
end

-- ── WFCompositeBinding ────────────────────────────────────────────────────────

local WFCompositeBinding = setmetatable({}, { __index = WFBinding })
WFCompositeBinding.__index = WFCompositeBinding

-- redraw: function(rec) — re-renders the layer from its live evaluators.
function WFCompositeBinding.new(rec, redraw)
    local self = WFBinding.newBase(WFCompositeBinding, rec)
    self.redraw = redraw
    return self
end

function WFCompositeBinding:apply()
    self.redraw(self.rec)
end

-- ── colour helpers (runtime-safe: no gsub/find, memoised parse) ───────────────
-- Colour attributes are strings ("ff8800", "#ff8800", "{ucolor}" already
-- resolved by the compiled expression). Parsing walks bytes and slices — no
-- patterns — and memoises per distinct string, so a face cycling two theme
-- colours costs two parses total. The cache is capped in case a script
-- generates unbounded distinct colour strings.

local WFColor = {}
local cache, cacheSize, CACHE_MAX = {}, 0, 512

local function parseHex(hex)
    local s = tostring(hex or "ffffff")
    if s:byte(1) == 35 then s = s:sub(2) end          -- leading '#'
    if #s < 6 then s = "ffffff" end
    local r = tonumber(s:sub(1, 2), 16) or 255
    local g = tonumber(s:sub(3, 4), 16) or 255
    local b = tonumber(s:sub(5, 6), 16) or 255
    return { r = r, g = g, b = b }
end

function WFColor.rgb(hex)
    local c = cache[hex]
    if not c then
        c = parseHex(hex)
        if cacheSize >= CACHE_MAX then cache = {}; cacheSize = 0 end
        cache[hex] = c
        cacheSize = cacheSize + 1
    end
    return c
end

function WFColor.c3b(hex)
    local c = WFColor.rgb(hex)
    return cc.c3b(c.r, c.g, c.b)
end

function WFColor.c4b(hex, opacity)
    local c = WFColor.rgb(hex)
    return cc.c4b(c.r, c.g, c.b, math.floor((opacity or 100) * 2.55))
end

function WFColor.c4f(hex, opacity)
    local c = WFColor.rgb(hex)
    return cc.c4f(c.r / 255, c.g / 255, c.b / 255, (opacity or 100) / 100)
end

return {
    WFBinding          = WFBinding,
    WFSetterBinding    = WFSetterBinding,
    WFCompositeBinding = WFCompositeBinding,
    WFColor            = WFColor,
}
