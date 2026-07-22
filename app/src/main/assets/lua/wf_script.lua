-- wf_script.lua — WFScript: hosts the face's global script
-- (scripts/script.txt) inside the sandbox and wires it into the tag engine.
--
-- Contract (from watchface_format_old.md §12 and the UL reference renderer):
--   • the top-level chunk runs ONCE at face load (defines variables/functions)
--   • on_millisecond(dt)          — every rendered frame
--   • on_second(minute, second)   — on the second boundary
--   • on_minute() / on_hour()     — on their boundaries
--   • every {tag} readable as a bare global (dh, blp, shr, …)
--   • helpers: wm_tag(t) · wm_action(a) · wm_set_alarm(t) · wm_sfx(s) · wm_schedule{...}
--   • tweens via wm_schedule are referenced as {tweens.NAME}
--   • script variables are referenced from attributes as {var} tags AND as
--     bare identifiers inside expressions
--
-- Variable propagation — the load-bearing part: the script runs against a
-- PROXY environment whose __newindex always fires (variables live in a shadow
-- store, never raw keys of the env). Every write
--   1. stores the value,
--   2. registers a tag def for the name (so later-compiled attributes can
--      activate it), and
--   3. engine:publish()es it — which immediately runs the update bucket of
--      every layer property whose expression uses that variable.
-- Reads fall through: shadow store → sandbox libraries/helpers → live tag
-- values (engine:resolve). No pattern matching anywhere on this path.

local WFTagDefs = require "wf_tagdefs"

-- cjson for persisting script variables. watchface_bridge.lua keeps `json` as a
-- module local, so it is NOT a global here — require our own reference.
local ok_json, json = pcall(require, "cjson")
if not ok_json then json = nil end

local WFScript = {}
WFScript.__index = WFScript

-- ── {tag} splicing in script SOURCE (load time — patterns are fine here) ─────
-- Real faces write tags straight into their scripts:
--     if var_chime == 1 and {dm} == 30 then …
--     local condition = "{wct}"
-- In plain Lua `{dm}` is a table constructor and "{wct}" a literal string, so
-- neither ever works. Rewrite KNOWN catalogue tags to live bare reads (the env
-- proxy resolves them through the engine at call time):
--     {dm}     → (dm)            outside strings
--     "{wct}"  → "" .. wct .. "" inside strings (same splice as wf_expr)
-- Unknown names are left untouched — `{ 'DOM', 'LUN' }` table constructors and
-- author-local names keep their Lua meaning. Comments are skipped so an
-- apostrophe in "-- it's" can't derail quote tracking.
local function knownName(name)
    if WFTagDefs.lookup(name) then return true end
    return name:sub(1, 7) == "tweens."
end

local function spliceTags(src)
    local out, i, n = {}, 1, #src
    local quote = nil
    while i <= n do
        local c = src:sub(i, i)
        if quote then
            if c == "\\" then
                out[#out + 1] = src:sub(i, i + 1); i = i + 2
            elseif c == "{" then
                local _, e, name = src:find("^{([%w_%.]+)}", i)
                if e and knownName(name) then
                    out[#out + 1] = quote .. " .. " .. name .. " .. " .. quote
                    i = e + 1
                else
                    out[#out + 1] = c; i = i + 1
                end
            else
                out[#out + 1] = c
                if c == quote then quote = nil end
                i = i + 1
            end
        elseif c == "-" and src:sub(i, i + 1) == "--" then
            local a, e = src:find("^%-%-%[%[.-%]%]", i)     -- long comment
            if not a then e = (src:find("\n", i) or n + 1) - 1 end
            out[#out + 1] = src:sub(i, e)
            i = e + 1
        elseif c == "'" or c == '"' then
            quote = c; out[#out + 1] = c; i = i + 1
        elseif c == "{" then
            local _, e, name = src:find("^{([%w_%.]+)}", i)
            if e and knownName(name) then
                out[#out + 1] = "(" .. name .. ")"
                i = e + 1
            else
                out[#out + 1] = c; i = i + 1
            end
        else
            out[#out + 1] = c; i = i + 1
        end
    end
    return table.concat(out)
end
WFScript.spliceTags = spliceTags   -- exposed for tap-action snippets + tests

-- ── easing catalogue ──────────────────────────────────────────────────────────
-- Penner-style f(t, b, c, d[, s]) → value, with b/c/d defaulting to 0/1/1 so
-- f(p) also works as a normalized 0..1 curve (wm_schedule tweens call it that
-- way). Exposed under BOTH naming schemes real faces use in attribute
-- expressions and scripts: WatchMaker's short names (outElastic, inQuad,
-- outInBack, …) and the easeXxx aliases UL's WatchSkinUtils also defines.

local sin, cos, pi, sqrt = math.sin, math.cos, math.pi, math.sqrt
local EASING = {}

local function reg(short, f)
    local fn = function(t, b, c, d, s)
        return f(t or 0, b or 0, c or 1, d or 1, s)
    end
    EASING[short] = fn
    EASING["ease" .. short:sub(1, 1):upper() .. short:sub(2)] = fn
    return fn
end

reg("linear", function(t, b, c, d) return c * t / d + b end)
EASING.linearTween = EASING.linear

reg("inQuad",    function(t,b,c,d) t = t/d; return c*t*t + b end)
reg("outQuad",   function(t,b,c,d) t = t/d; return -c*t*(t - 2) + b end)
reg("inOutQuad", function(t,b,c,d)
    t = t/d*2
    if t < 1 then return c/2*t*t + b end
    t = t - 1
    return -c/2*(t*(t - 2) - 1) + b
end)
reg("inCubic",  function(t,b,c,d) t = t/d; return c*t^3 + b end)
reg("outCubic", function(t,b,c,d) t = t/d - 1; return c*(t^3 + 1) + b end)
reg("inOutCubic", function(t,b,c,d)
    t = t/d*2
    if t < 1 then return c/2*t^3 + b end
    t = t - 2
    return c/2*(t^3 + 2) + b
end)
reg("inQuart",  function(t,b,c,d) t = t/d; return c*t^4 + b end)
reg("outQuart", function(t,b,c,d) t = t/d - 1; return -c*(t^4 - 1) + b end)
reg("inOutQuart", function(t,b,c,d)
    t = t/d*2
    if t < 1 then return c/2*t^4 + b end
    t = t - 2
    return -c/2*(t^4 - 2) + b
end)
reg("inQuint",  function(t,b,c,d) t = t/d; return c*t^5 + b end)
reg("outQuint", function(t,b,c,d) t = t/d - 1; return c*(t^5 + 1) + b end)
reg("inOutQuint", function(t,b,c,d)
    t = t/d*2
    if t < 1 then return c/2*t^5 + b end
    t = t - 2
    return c/2*(t^5 + 2) + b
end)
reg("inSine",    function(t,b,c,d) return -c*cos(t/d*(pi/2)) + c + b end)
reg("outSine",   function(t,b,c,d) return c*sin(t/d*(pi/2)) + b end)
reg("inOutSine", function(t,b,c,d) return -c/2*(cos(pi*t/d) - 1) + b end)
reg("inExpo",  function(t,b,c,d) if t == 0 then return b end return c*2^(10*(t/d - 1)) + b end)
reg("outExpo", function(t,b,c,d) if t == d then return b + c end return c*(1 - 2^(-10*t/d)) + b end)
reg("inOutExpo", function(t,b,c,d)
    if t == 0 then return b end
    if t == d then return b + c end
    t = t/d*2
    if t < 1 then return c/2*2^(10*(t - 1)) + b end
    t = t - 1
    return c/2*(2 - 2^(-10*t)) + b
end)
reg("inCirc",  function(t,b,c,d) t = t/d; return -c*(sqrt(1 - t*t) - 1) + b end)
reg("outCirc", function(t,b,c,d) t = t/d - 1; return c*sqrt(1 - t*t) + b end)
reg("inOutCirc", function(t,b,c,d)
    t = t/d*2
    if t < 1 then return -c/2*(sqrt(1 - t*t) - 1) + b end
    t = t - 2
    return c/2*(sqrt(1 - t*t) + 1) + b
end)
reg("inElastic", function(t,b,c,d)
    if t == 0 then return b end
    t = t/d
    if t == 1 then return b + c end
    local p = d*0.3
    local s = p/4
    t = t - 1
    return -(c*2^(10*t)*sin((t*d - s)*(2*pi)/p)) + b
end)
reg("outElastic", function(t,b,c,d)
    if t == 0 then return b end
    t = t/d
    if t == 1 then return b + c end
    local p = d*0.3
    local s = p/4
    return c*2^(-10*t)*sin((t*d - s)*(2*pi)/p) + c + b
end)
reg("inOutElastic", function(t,b,c,d)
    if t == 0 then return b end
    t = t/d*2
    if t == 2 then return b + c end
    local p = d*(0.3*1.5)
    local s = p/4
    if t < 1 then
        t = t - 1
        return -0.5*(c*2^(10*t)*sin((t*d - s)*(2*pi)/p)) + b
    end
    t = t - 1
    return c*2^(-10*t)*sin((t*d - s)*(2*pi)/p)*0.5 + c + b
end)
reg("inBack", function(t,b,c,d,s)
    s = s or 1.70158
    t = t/d
    return c*t*t*((s + 1)*t - s) + b
end)
reg("outBack", function(t,b,c,d,s)
    s = s or 1.70158
    t = t/d - 1
    return c*(t*t*((s + 1)*t + s) + 1) + b
end)
reg("inOutBack", function(t,b,c,d,s)
    s = (s or 1.70158)*1.525
    t = t/d*2
    if t < 1 then return c/2*(t*t*((s + 1)*t - s)) + b end
    t = t - 2
    return c/2*(t*t*((s + 1)*t + s) + 2) + b
end)
local function bounceOut(t, b, c, d)
    t = t/d
    if t < 1/2.75 then return c*(7.5625*t*t) + b end
    if t < 2/2.75 then t = t - 1.5/2.75; return c*(7.5625*t*t + 0.75) + b end
    if t < 2.5/2.75 then t = t - 2.25/2.75; return c*(7.5625*t*t + 0.9375) + b end
    t = t - 2.625/2.75
    return c*(7.5625*t*t + 0.984375) + b
end
local function bounceIn(t, b, c, d) return c - bounceOut(d - t, 0, c, d) + b end
reg("outBounce", bounceOut)
reg("inBounce", bounceIn)
reg("inOutBounce", function(t,b,c,d)
    if t < d/2 then return bounceIn(t*2, 0, c, d)*0.5 + b end
    return bounceOut(t*2 - d, 0, c, d)*0.5 + c*0.5 + b
end)
-- outIn variants (UL defines outInExpo/outInBack explicitly; generic here)
for _, fam in ipairs({ "Quad","Cubic","Quart","Quint","Sine","Expo","Circ",
                       "Elastic","Back","Bounce" }) do
    local fin, fout = EASING["in" .. fam], EASING["out" .. fam]
    reg("outIn" .. fam, function(t, b, c, d)
        if t < d/2 then return fout(t*2, b, c/2, d) end
        return fin(t*2 - d, b + c/2, c/2, d)
    end)
end

-- ── construction ──────────────────────────────────────────────────────────────

-- engine: WFEngine · sandbox: WFSandbox · actionHandler: fn(actionString)
-- (renderer's tap-action dispatch, so wm_action supports every tap action).
function WFScript.new(engine, sandbox, actionHandler)
    local self = setmetatable({}, WFScript)
    self.engine   = engine
    self.sandbox  = sandbox
    self.vars     = {}        -- shadow store: script globals / variables
    self.tweens   = {}        -- array of live tween entries (see scheduleTween)
    self.hasScript = false
    self._lastMsElapsed = nil
    self._needFrameChanged = nil   -- scene hook: tween scheduled post-load

    local base   = sandbox:env()
    local vars   = self.vars
    local eng    = engine

    -- helpers visible to the script (installed on the raw sandbox env so the
    -- proxy read path finds them after the shadow store)
    -- Faces call wm_tag both ways: wm_tag('drss') and wm_tag('{c1t}') —
    -- strip braces before resolving. Unknown tags return 0 (the engine's
    -- unknown-attribute-tag rule / UL's nil-global substitution), never nil:
    -- scripts feed the result straight into string.len()/comparisons.
    base.wm_tag = function(t)
        local name = tostring(t or ""):gsub("[{}]", "")
        local v = eng:resolve(name)
        if v == nil then return 0 end
        return v
    end
    base.wm_action = function(a)
        if type(a) == "string" and actionHandler then actionHandler(a) end
    end
    -- WatchMaker wm_set_alarm('HH:MM') — routes through the shortcut pipeline
    -- as set_alarm_HH:MM (TapActionHandler → AlarmClock.ACTION_SET_ALARM)
    base.wm_set_alarm = function(t)
        if type(t) == "string" and t ~= "" and actionHandler then
            actionHandler("set_alarm_" .. t)
        end
    end
    -- Sound playback with STRICT audio-device hygiene. Axmol's AudioEngine
    -- lazily opens the OpenAL Soft device on the first play2d and NEVER
    -- closes it — the alsoft-mixer thread then renders 24/7, audioserver
    -- holds an AudioMix wakelock charged to our uid, doze never engages and
    -- the watch cooks (observed: ~25% CPU, 48–59°C at "idle"). So: when the
    -- last sound finishes, tear the engine down (endToLua) and let the next
    -- wm_sfx re-initialise it; the few-ms open cost is nothing next to a
    -- permanently awake SoC.
    base.wm_sfx = function(s)
        if WF and WF.settings and WF.settings.allowSound == false then return end
        local dir = self.base
        if not dir then return end
        local path = dir .. "sfx/" .. tostring(s) .. ".mp3"
        local ok, err = pcall(function()
            if not cc.FileUtils:getInstance():isFileExist(path) then
                print("[WFScript] wm_sfx: no such file " .. path)
                return
            end
            local AE = ax.AudioEngine
            local id = AE:play2d(path, false, 1.0)
            if not id or id < 0 then
                print("[WFScript] wm_sfx: play2d failed for " .. path)
                self:releaseAudio()          -- don't leave a half-open device
                return
            end
            self._activeSfx = (self._activeSfx or 0) + 1
            AE:setFinishCallback(id, function()
                self._activeSfx = math.max(0, (self._activeSfx or 1) - 1)
                if self._activeSfx == 0 then
                    -- Deferred one tick: end() from inside the finish
                    -- callback would destroy the engine while it iterates
                    -- its own callback list.
                    self:_releaseAudioNextTick()
                end
            end)
        end)
        if not ok then print("[WFScript] wm_sfx: " .. tostring(err)) end
    end
    -- UL semantics (WatchSkinUtils.wm_vibrate): duration in ms, repeat ignored
    base.wm_vibrate = function(durationMs)
        if WF and WF.settings and WF.settings.allowVibrate == false then return end
        local ok = pcall(function()
            cc.Device:vibrate((tonumber(durationMs) or 100) / 1000)
        end)
        if not ok then print("[WFScript] wm_vibrate failed") end
    end
    -- present-but-inert in UL too; stubs keep faces that call them alive
    base.wm_transition = function() end
    base.wm_unschedule_all = function() self.tweens = {} end
    base.wm_schedule = function(args) self:schedule(args) end
    for name, fn in pairs(EASING) do base[name] = fn end

    -- `tweens.NAME` readable from script code (attributes use {tweens.NAME})
    base.tweens = setmetatable({}, {
        __index    = function(_, k) return eng.V["tweens." .. tostring(k)] end,
        __newindex = function(_, k, v) eng:publish("tweens." .. tostring(k), v) end,
    })
    -- {tweens.NAME} attributes may compile before the script ever schedules
    -- that tween — give them a 0-valued def instead of the unknown-tag path
    eng:definePrefix("tweens.", function(name)
        return { ev = "static", fn = function() return eng.V[name] or 0 end }
    end)

    -- the var-tracking proxy the script (and every attribute expression) runs in
    self.envProxy = setmetatable({}, {
        __index = function(_, k)
            local v = vars[k]
            if v ~= nil then return v end
            v = base[k]
            if v ~= nil then return v end
            return eng:resolve(k)      -- {tag} values as bare globals
        end,
        __newindex = function(_, k, v)
            vars[k] = v
            -- Table variables mutate in place (`t[i] = x`) without ever going
            -- through publish, so the binding layer never sees the fill. Track
            -- them; guarded()/runSnippet() re-fire their buckets after each
            -- script entry so in-place writes propagate (and the initial `t={}`
            -- empty-publish is coalesced past — see WFEngine:publish batching).
            if type(v) == "table" then
                self._tableVars = self._tableVars or {}
                self._tableVars[k] = true
            elseif self._tableVars then
                self._tableVars[k] = nil
            end
            -- first write registers the def so later activations resolve it;
            -- publish() runs the update bucket when the tag is already live
            if not eng.extraDefs[k] then
                eng:define(k, "static", function() return vars[k] end)
            end
            eng:publish(k, v)
        end,
    })
    sandbox:setEnv(self.envProxy)
    return self
end

-- ── loading ───────────────────────────────────────────────────────────────────

-- Read and run scripts/script.txt from the face package. Runs the top-level
-- chunk once; variables it sets flow through the proxy into the engine BEFORE
-- the renderer compiles any layer expression (call order in wf_render:load).
function WFScript:load(baseDir)
    self.base = baseDir            -- wm_sfx resolves sfx/ against this
    local path = baseDir .. "scripts/script.txt"
    local fu = cc.FileUtils:getInstance()
    if not fu:isFileExist(path) then return false end
    local src = fu:getStringFromFile(path)
    if not src or #src == 0 then return false end
    print("[WFScript] loading scripts/script.txt (" .. #src .. " bytes)")
    if src:find("{", 1, true) then
        local spliced = spliceTags(src)
        if spliced ~= src then
            print("[WFScript] spliced {tag} references in script source")
            src = spliced
        end
    end
    local ok, err = self.sandbox:run(src, "=wf_script")
    if not ok then
        self:reportError("Global script failed", err)
        return false
    end
    self.hasScript = true
    return true
end

-- ── persistent script variables ───────────────────────────────────────────────
-- The face's script variables (self.vars) are backed up to a per-face JSON file
-- so state (counters, selected mode, offsets, …) survives a face switch or the
-- activity being destroyed. Keyed by _persistKey = md5(xml + global script), set
-- by wf_render on load — content-addressed, so identical faces share their store
-- regardless of package path. Only primitives are persisted (functions/tables are
-- transient). Restore runs BEFORE the script's top-level chunk, so a face that
-- guards its defaults (`if x == nil then x = … end`) resumes where it left off.

local function wfVarsDir()
    return cc.FileUtils:getInstance():getWritablePath() .. "wf_vars/"
end

function WFScript:varsFile()
    if not self._persistKey or self._persistKey == "" then return nil end
    return wfVarsDir() .. self._persistKey .. ".json"
end

-- Only number / string / boolean survive serialization.
function WFScript:serializeVars()
    local out = {}
    for k, v in pairs(self.vars) do
        local t = type(v)
        if t == "number" or t == "string" or t == "boolean" then out[k] = v end
    end
    return out
end

function WFScript:saveVars()
    local path = self:varsFile()
    local n = 0; for _ in pairs(self.vars) do n = n + 1 end
    print(string.format("[WFPersist] saveVars key=%s vars=%d json=%s path=%s",
        tostring(self._persistKey), n, tostring(json ~= nil), tostring(path)))
    if not path or not json then return 0 end
    local fu = cc.FileUtils:getInstance()
    local data = self:serializeVars()
    local sn = 0; for _ in pairs(data) do sn = sn + 1 end
    print("[WFPersist] serializable=" .. sn)
    if next(data) == nil then
        if fu:isFileExist(path) then fu:removeFile(path) end   -- nothing to keep
        return 0
    end
    local ok, enc = pcall(json.encode, data)
    if not ok or type(enc) ~= "string" then
        print("[WFPersist] encode failed: " .. tostring(enc)); return 0
    end
    if not fu:isDirectoryExist(wfVarsDir()) then fu:createDirectories(wfVarsDir()) end
    local wok = fu:writeStringToFile(enc, path)
    print(string.format("[WFPersist] wrote %d bytes -> %s (ok=%s)", #enc, path, tostring(wok)))
    return #enc
end

function WFScript:restoreVars()
    local path = self:varsFile()
    if not path or not json then return end
    local fu = cc.FileUtils:getInstance()
    if not fu:isFileExist(path) then return end
    local src = fu:getStringFromFile(path)
    if not src or #src == 0 then return end
    local ok, data = pcall(json.decode, src)
    if not ok or type(data) ~= "table" then return end
    for k, v in pairs(data) do
        local t = type(v)
        if t == "number" or t == "string" or t == "boolean" then
            self.vars[k] = v
            if not self.engine.extraDefs[k] then
                self.engine:define(k, "static", function() return self.vars[k] end)
            end
            self.engine:publish(k, v)
        end
    end
    print("[WFScript] restored " .. tostring(#src) .. " bytes of persisted vars")
end

-- Size on disk of the persisted backup (bytes), for the config Info tab.
function WFScript:persistedSize()
    local path = self:varsFile()
    if not path then return 0 end
    local fu = cc.FileUtils:getInstance()
    if not fu:isFileExist(path) then return 0 end
    local src = fu:getStringFromFile(path)
    return src and #src or 0
end

-- Delete the backup AND wipe the in-memory vars (the caller reloads the face
-- right after, so clearing the live table stops the reload's teardown from
-- re-saving the values we just cleared). Clear in place — the sandbox proxy
-- captured this exact table.
function WFScript:clearPersisted()
    local path = self:varsFile()
    if path then
        local fu = cc.FileUtils:getInstance()
        if fu:isFileExist(path) then fu:removeFile(path) end
    end
    for k in pairs(self.vars) do self.vars[k] = nil end
end

-- Surface a script runtime failure in the Android error dialog (same pipeline
-- as global-script load and attribute-compile failures — a silently broken
-- face is never OK). `once` dedups per context per face load: on_* callbacks
-- fire every frame/second and must not open a dialog per tick; logcat keeps
-- every occurrence either way.
function WFScript:reportError(context, err, once)
    print("[WFScript] " .. context .. ": " .. tostring(err))
    if once then
        self._reported = self._reported or {}
        if self._reported[context] then return end
        self._reported[context] = true
    end
    if WatchfaceBridge and WatchfaceBridge.reportError then
        WatchfaceBridge.reportError(context, err)
    end
end

-- Run a `script:` tap-action body in the same environment (variables persist
-- and propagate exactly like global-script writes). Tag references get the
-- same splice as the global script.
function WFScript:runSnippet(src, label)
    if src:find("{", 1, true) then src = spliceTags(src) end
    local eng = self.engine
    eng:beginBatch()
    local ok, err = self.sandbox:run(src, label or "=wf_tap")
    self:_markTableVarsDirty()
    eng:endBatch()
    if not ok then self:reportError("Tap script failed", err) end
    -- the snippet may have defined on_millisecond/on_second after load —
    -- attach the drivers and let the scene re-derive the render cadence
    -- (the hook no-ops when nothing flipped)
    self:ensureFastListeners()
    if self._needFrameChanged then self._needFrameChanged() end
    return ok
end

-- ── engine wiring ─────────────────────────────────────────────────────────────

-- Re-fire every table variable's bucket: in-place element writes (`t[i]=v`)
-- made during a script entry never publish, so their dependent bindings would
-- otherwise stay stale. Called at the close of each entry, inside the batch.
function WFScript:_markTableVarsDirty()
    local tv = self._tableVars
    if not tv then return end
    local eng = self.engine
    for name in pairs(tv) do eng:markDirty(name) end
end

-- callbacks run guarded so a broken face surfaces the error dialog
-- (once per callback per face load) instead of dying in the engine's
-- listener pcall with only a logcat line.
-- Wrapped in an engine batch: dependent bindings fire ONCE after the callback
-- settles (past the empty-then-filled `t={}` window), and table vars are
-- re-fired so in-place writes propagate. See WFEngine:publish/endBatch.
function WFScript:guarded(name, cb, ...)
    local eng = self.engine
    eng:beginBatch()
    local ok, err = self.sandbox:pcallLimited(cb, ...)
    self:_markTableVarsDirty()
    eng:endBatch()
    if not ok then self:reportError("Script " .. name .. " failed", err, true) end
end

-- Attach the ms / second drivers ONLY when the script actually has work for
-- them: eventActive("ms")/("second") is bucket-non-empty, so a hollow listener
-- registered "just in case" flips needFrame/needSecond and forces every face —
-- scripted or not — onto the per-frame render path at full FPS. Idempotent;
-- re-run whenever the need may have appeared (first tween, tap snippet
-- defining on_millisecond/on_second). Listeners can't be detached, so a face
-- stays on the fast path once it genuinely used it (until the next load).
function WFScript:ensureFastListeners()
    local eng, vars = self.engine, self.vars
    if not self._msAttached and (vars.on_millisecond ~= nil or next(self.tweens) ~= nil) then
        self._msAttached = true
        eng:listen("ms", function(clock)
            -- tween driver first so on_millisecond sees this frame's tween values
            if next(self.tweens) ~= nil then
                self:guarded("tween", self.stepTweens, self, clock)
            end
            local cb = vars.on_millisecond
            if cb then
                local last = self._lastMsElapsed
                self._lastMsElapsed = clock.elapsed
                self:guarded("on_millisecond", cb, last and (clock.elapsed - last) or 0)
            end
        end)
    end
    if not self._secAttached and vars.on_second ~= nil then
        self._secAttached = true
        eng:listen("second", function(clock)
            local cb = vars.on_second
            if cb then self:guarded("on_second", cb, clock.t.min, clock.t.sec) end
        end)
    end
end

-- Register the on_* callbacks and the tween driver as event listeners. Called
-- by the renderer after the script ran (callbacks exist in the shadow store).
function WFScript:attach()
    local eng, vars = self.engine, self.vars

    self:ensureFastListeners()
    -- minute/hour buckets don't drive the render cadence, so these stay
    -- unconditional — which also picks up an on_minute/on_hour a tap snippet
    -- defines after load.
    eng:listen("minute", function()
        local cb = vars.on_minute
        if cb then self:guarded("on_minute", cb) end
    end)
    eng:listen("hour", function()
        local cb = vars.on_hour
        if cb then self:guarded("on_hour", cb) end
    end)
end

-- The frame loop must run when the script animates: an on_millisecond
-- callback or any live tween.
function WFScript:needsFrame()
    return self.vars.on_millisecond ~= nil or next(self.tweens) ~= nil
end

-- Scene hook: called when needsFrame() may have flipped after load (a tap
-- script scheduling the first tween while the frame loop is suspended).
function WFScript:onNeedFrameChanged(fn)
    self._needFrameChanged = fn
end

-- ── audio-device teardown ─────────────────────────────────────────────────────

-- Close the OpenAL device so its mixer thread and the audioserver wakelock go
-- away. Safe to call when audio was never initialised. Called when the last
-- sound finishes and on face switch (renderer clear()).
function WFScript:releaseAudio()
    self._activeSfx = 0
    local ok, err = pcall(function()
        ax.AudioEngine:stopAll()
        ax.AudioEngine:uncacheAll()
        ax.AudioEngine:endToLua()
    end)
    if ok then
        print("[WFScript] audio engine released")
    else
        print("[WFScript] releaseAudio: " .. tostring(err))
    end
end

function WFScript:_releaseAudioNextTick()
    if self._releasePending then return end
    self._releasePending = true
    local sched = cc.Director:getInstance():getScheduler()
    local entry
    entry = sched:scheduleScriptFunc(function()
        sched:unscheduleScriptEntry(entry)
        self._releasePending = false
        -- a new sound may have started in the meantime — keep the device then
        if (self._activeSfx or 0) == 0 then self:releaseAudio() end
    end, 0, false)
end

-- ── tweens (wm_schedule) ──────────────────────────────────────────────────────

-- Tween values land in the script VARIABLE itself (WatchMaker semantics:
-- wm_schedule{action='tween', tween='var_x', …} animates var_x, and layers
-- read {var_x}) — plus our engine's {tweens.NAME} alias for faces using it.
function WFScript:publishTween(name, v)
    self.vars[name] = v
    if not self.engine.extraDefs[name] then
        local vars = self.vars
        self.engine:define(name, "static", function() return vars[name] end)
    end
    self.engine:publish(name, v)
    self.engine:publish("tweens." .. name, v)
end

-- One tween entry. `delay` is absolute seconds from now; a pending (delayed)
-- tween writes nothing until its window starts, so overlapping tweens on the
-- SAME variable queue correctly (the tween list is an array, not name-keyed).
function WFScript:scheduleTween(t, delay)
    local name = tostring(t.tween)
    local from, to = tonumber(t.from) or 0, tonumber(t.to) or 0
    local dur = tonumber(t.duration) or 1
    if dur <= 0 then dur = 0.001 end
    local wasIdle = next(self.tweens) == nil
    self.tweens[#self.tweens + 1] = {
        name = name, from = from, to = to, dur = dur,
        delay  = delay,
        loop   = t.loop and true or false,
        easing = type(t.easing) == "function" and t.easing or EASING.linearTween,
        start  = self.engine.clock.elapsed,
    }
    if delay <= 0 then self:publishTween(name, from) end
    if wasIdle then
        self:ensureFastListeners()   -- ms driver attaches on the first tween
        if self._needFrameChanged then self._needFrameChanged() end
    end
end

-- Accepts BOTH forms real faces use:
--   single:   wm_schedule{ tween="name", from=, to=, duration=, easing?, delay?, loop? }
--   sequence: wm_schedule{ {action='sleep', sleep=3},
--                          {action='tween', tween='var_x', from=, to=, duration=, easing=}, … }
--             (WM's documented form: steps run in order — sleeps and tween
--              durations accumulate into the following steps' start time)
function WFScript:schedule(args)
    if type(args) ~= "table" then return end
    if args.tween then
        self:scheduleTween(args, tonumber(args.delay) or 0)
        return
    end
    local at = 0
    for _, step in ipairs(args) do
        if type(step) == "table" then
            if step.action == "sleep" then
                at = at + (tonumber(step.sleep) or 0)
            elseif step.action == "tween" and step.tween then
                self:scheduleTween(step, at + (tonumber(step.delay) or 0))
                at = at + (tonumber(step.duration) or 0)
            end
        end
    end
end

function WFScript:stepTweens(clock)
    local tws = self.tweens
    for i = #tws, 1, -1 do
        local tw = tws[i]
        local e = clock.elapsed - tw.start - tw.delay
        if e >= 0 then
            local p = e / tw.dur
            local last = false
            if tw.loop then
                p = p % 1
            elseif p >= 1 then
                p = 1
                last = true
            end
            self:publishTween(tw.name, tw.from + (tw.to - tw.from) * tw.easing(p))
            if last then table.remove(tws, i) end
        end
    end
end

return WFScript
