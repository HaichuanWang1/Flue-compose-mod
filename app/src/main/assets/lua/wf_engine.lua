-- wf_engine.lua — the tag-indexed update engine: WFClock, WFTag, WFEvent,
-- WFEngine.
--
-- Data flow (the whole point of the architecture):
--
--   event fires (ms every frame / second / minute / hour / day on boundary /
--   a WF.* bridge category when Java pushes / "var:…" when the global script
--   writes a variable)
--        │
--        ▼
--   the event's ACTIVE tags recompute        (only tags some layer actually
--        │  value changed?                    uses are ever active)
--        ▼
--   the tag's update bucket fires            (bucket = the (layer, property)
--        │                                    bindings indexed at import)
--        ▼
--   each binding re-evaluates its compiled expression and applies the value
--   to its cocos node
--
-- Nothing in this path allocates per frame beyond Lua's own value churn, and
-- nothing pattern-matches — expressions were compiled at import (wf_expr) and
-- tag names resolved at activation (wf_tagdefs).
--
-- A generation counter dedups bindings that depend on several tags of the same
-- event (e.g. text "{dh23z}:{dmz}" fired by either tag changing): a binding
-- runs at most once per fire().

local WFTagDefs = require "wf_tagdefs"

-- ── WFClock ───────────────────────────────────────────────────────────────────
-- The per-tick time context handed to tag compute functions. One instance per
-- engine, mutated in place; epoch/UTC are memoised per tick because only a few
-- tags need them and os.time/os.date are not free.

local WFClock = {}
WFClock.__index = WFClock

function WFClock.new()
    return setmetatable({ t = os.date("*t"), subsec = 0, elapsed = 0 }, WFClock)
end

function WFClock:set(t, subsec, elapsed)
    self.t       = t
    self.subsec  = subsec or 0
    self.elapsed = elapsed or 0
    self._epoch, self._utc = nil, nil
end

function WFClock:epoch()
    local e = self._epoch
    if not e then e = os.time(self.t); self._epoch = e end
    return e
end

function WFClock:utc()
    local u = self._utc
    if not u then u = os.date("!*t", self:epoch()); self._utc = u end
    return u
end

-- ── WFTag ─────────────────────────────────────────────────────────────────────
-- One live tag: its compute def, current value, and the bucket of property
-- bindings to run when the value changes.

local WFTag = {}
WFTag.__index = WFTag

function WFTag.new(name, def, engine)
    return setmetatable({
        name   = name,
        def    = def,
        engine = engine,
        value  = nil,
        bucket = {},          -- WFBinding list, indexed at import
    }, WFTag)
end

function WFTag:addBinding(binding)
    self.bucket[#self.bucket + 1] = binding
end

-- Recompute from the clock; publish + return true when the value moved.
function WFTag:recompute(clock)
    local ok, v = pcall(self.def.fn, clock)
    if not ok then
        if not self._warned then
            self._warned = true
            print("[WFEngine] tag '" .. self.name .. "' compute: " .. tostring(v))
        end
        return false
    end
    if v == self.value then return false end
    self.value = v
    self.engine.V[self.name] = v
    return true
end

function WFTag:fireBucket(gen)
    local bucket = self.bucket
    for i = 1, #bucket do bucket[i]:fire(gen) end
end

-- ── WFEvent ───────────────────────────────────────────────────────────────────
-- A named update source. Owns the active tags it drives plus raw listeners
-- (global-script callbacks, gif frame steppers, the tween driver) that want
-- the event itself rather than a tag value.

local WFEvent = {}
WFEvent.__index = WFEvent

function WFEvent.new(name)
    return setmetatable({ name = name, tags = {}, listeners = {} }, WFEvent)
end

function WFEvent:addTag(tag)      self.tags[#self.tags + 1] = tag end
function WFEvent:addListener(fn)  self.listeners[#self.listeners + 1] = fn end

function WFEvent:isEmpty()
    return #self.tags == 0 and #self.listeners == 0
end

function WFEvent:fire(clock, gen)
    -- TWO PHASES: recompute EVERY tag first, then run the changed tags'
    -- buckets. Interleaving (recompute→fire per tag) ran a shared binding —
    -- e.g. a "{aalh23z}:{aalmz}" template sitting in BOTH tags' buckets —
    -- right after the FIRST tag recomputed, so it read the second tag's STALE
    -- value; the gen dedup then skipped the second bucket and the stale half
    -- froze until some unrelated change (the "alarm hour updates, minute
    -- doesn't" bug). Tags still recompute before listeners, so script
    -- callbacks read fresh values.
    local tags = self.tags
    local changed = self._changed
    if not changed then changed = {}; self._changed = changed end
    local n = 0
    for i = 1, #tags do
        local tag = tags[i]
        if tag:recompute(clock) then n = n + 1; changed[n] = tag end
    end
    for i = 1, n do
        changed[i]:fireBucket(gen)
        changed[i] = nil
    end
    local ls = self.listeners
    for i = 1, #ls do
        local ok, err = pcall(ls[i], clock)
        if not ok then print("[WFEngine] listener (" .. self.name .. "): " .. tostring(err)) end
    end
end

-- ── WFEngine ──────────────────────────────────────────────────────────────────

local WFEngine = {}
WFEngine.__index = WFEngine

function WFEngine.new()
    local self = setmetatable({}, WFEngine)
    self.V         = {}       -- live tag values (compiled expressions read this)
    self.tags      = {}       -- name → WFTag (active only)
    self.events    = {}       -- name → WFEvent
    self.extraDefs = {}       -- name → def, registered by wf_script (vars, tweens)
    self.prefixDefs = {}      -- { prefix, make(name)→def } families (tweens.*)
    self.bindings  = {}       -- every binding, for full repaints
    self.clock     = WFClock.new()
    self.gen       = 0
    self._warnedUnknown = {}
    return self
end

function WFEngine:event(name)
    local ev = self.events[name]
    if not ev then ev = WFEvent.new(name); self.events[name] = ev end
    return ev
end

-- Register an out-of-catalogue tag def (script variables, tweens.*). Must run
-- before any expression referencing the name is compiled/activated; wf_script
-- guarantees that by loading the global script before the layers are built.
function WFEngine:define(name, ev, fn)
    self.extraDefs[name] = { ev = ev, fn = fn }
end

-- Register a def family for a name prefix ("tweens." → wm_schedule values).
-- Matched at activation only (import time / first reference).
function WFEngine:definePrefix(prefix, make)
    self.prefixDefs[#self.prefixDefs + 1] = { prefix = prefix, make = make }
end

-- Unknown tags resolve to the NUMBER 0, matching UL (evalString substitutes
-- '0' for any nil global). A "" default type-errors any relational compare
-- like `{dtp} >= {unknowntag}` — seen in the wild on faces comparing against
-- tags we don't provide.
local STATIC_ZERO = { ev = "static", fn = function() return 0 end }

-- Activate a tag by name: resolve its def (script defs shadow the catalogue),
-- create the WFTag, hook it into its event(s) and compute its initial value.
-- Unknown names become static 0 (UL parity), reported once for diagnostics.
function WFEngine:activate(name)
    local tag = self.tags[name]
    if tag then return tag end

    local def = self.extraDefs[name] or WFTagDefs.lookup(name)
    if not def then
        for _, p in ipairs(self.prefixDefs) do
            if name:sub(1, #p.prefix) == p.prefix then def = p.make(name); break end
        end
    end
    if not def then
        if not self._warnedUnknown[name] then
            self._warnedUnknown[name] = true
            print("[WFEngine] unknown tag '" .. tostring(name) .. "' → 0")
        end
        def = STATIC_ZERO
    end

    tag = WFTag.new(name, def, self)
    self.tags[name] = tag

    local ev = def.ev
    if type(ev) == "table" then
        for _, e in ipairs(ev) do self:event(e):addTag(tag) end
    elseif ev ~= "static" then
        self:event(ev):addTag(tag)
    end

    tag:recompute(self.clock)      -- initial value; static tags live off this
    if self.V[name] == nil then self.V[name] = "" end
    return tag
end

-- Index a binding into the bucket of every tag it depends on.
function WFEngine:bind(binding, deps)
    self.bindings[#self.bindings + 1] = binding
    for _, name in ipairs(deps) do
        self:activate(name):addBinding(binding)
    end
end

-- Attach a raw listener (fn(clock)) to an event.
function WFEngine:listen(eventName, fn)
    self:event(eventName):addListener(fn)
end

function WFEngine:setClock(t, subsec, elapsed)
    self.clock:set(t, subsec, elapsed)
end

-- Fire one event: recompute its active tags, run changed tags' buckets.
function WFEngine:fire(eventName)
    local ev = self.events[eventName]
    if not ev then return end
    self.gen = self.gen + 1
    ev:fire(self.clock, self.gen)
end

-- External value write (script variable, theme colour, tween step): publish a
-- new value for a tag and run its bucket immediately when it changed.
--
-- Batching (script entries): a script callback that assigns a table variable
-- as `t = {}` then fills it element-by-element publishes the EMPTY table first,
-- which — fired immediately — would run dependent bindings against the not-yet-
-- populated table (e.g. `t[1] > 0` → compare-nil errors). While a batch is open
-- publishes only mark the tag dirty; endBatch fires each dirty tag ONCE, after
-- the callback (and its in-place fills) have settled. See WFScript:guarded.
function WFEngine:publish(name, value)
    local tag = self.tags[name] or self:activate(name)
    if tag.value == value then return end
    tag.value = value
    self.V[name] = value
    if self._batchDepth and self._batchDepth > 0 then
        self._batchDirty[name] = true
        return
    end
    self.gen = self.gen + 1
    tag:fireBucket(self.gen)
end

-- Force a tag's bucket to re-run at endBatch (or now, if no batch is open) even
-- though its VALUE object is unchanged. Table variables mutated in place
-- (`t[i] = v`) don't go through publish, so WFScript marks them dirty each entry.
function WFEngine:markDirty(name)
    local tag = self.tags[name]
    if not tag then return end
    if self._batchDepth and self._batchDepth > 0 then
        self._batchDirty[name] = true
        return
    end
    self.gen = self.gen + 1
    tag:fireBucket(self.gen)
end

-- Open a publish batch: dependent bindings don't fire until the matching
-- endBatch, coalescing many writes (and in-place table fills) into one fire.
-- Re-entrant (tap snippet during a callback) via depth counting.
function WFEngine:beginBatch()
    self._batchDepth = (self._batchDepth or 0) + 1
    if not self._batchDirty then self._batchDirty = {} end
end

-- Close the batch; when the outermost one closes, fire every dirty tag once.
function WFEngine:endBatch()
    if not self._batchDepth or self._batchDepth == 0 then return end
    self._batchDepth = self._batchDepth - 1
    if self._batchDepth > 0 then return end
    local dirty = self._batchDirty
    self._batchDirty = {}
    if next(dirty) == nil then return end
    self.gen = self.gen + 1
    local g = self.gen
    for name in pairs(dirty) do
        local tag = self.tags[name]
        if tag then tag:fireBucket(g) end
    end
end

-- True when a name is resolvable as a live value: a script variable/tween
-- (extraDefs), an already-active tag, or a catalogue tag. wf_expr uses this to
-- recognise BARE identifiers in expressions ("var_img == 1 and 100 or 0" — no
-- braces) as dependencies, so writes to them re-fire the right buckets.
function WFEngine:knowsName(name)
    if self.extraDefs[name] or self.tags[name] then return true end
    return WFTagDefs.lookup(name) ~= nil
end

-- Current value of a tag; activates on first use so wm_tag() and script reads
-- of clock tags stay live from then on.
function WFEngine:resolve(name)
    local tag = self.tags[name]
    if not tag then
        if not (self.extraDefs[name] or WFTagDefs.lookup(name)) then return nil end
        tag = self:activate(name)
    end
    return tag.value
end

-- Re-apply every binding unconditionally (initial paint, dim toggle, theme
-- reset). Uses a fresh generation so per-event dedup keeps working afterwards.
function WFEngine:repaint()
    self.gen = self.gen + 1
    local bs = self.bindings
    for i = 1, #bs do bs[i]:fire(self.gen) end
end

-- True when an event has anything to do — drives needFrame/needSecond and the
-- bridge feature toggles in the renderer.
function WFEngine:eventActive(name)
    local ev = self.events[name]
    return ev ~= nil and not ev:isEmpty()
end

-- Iterate active event names (for bridge listener registration / features).
function WFEngine:activeEventNames()
    local names = {}
    for name, ev in pairs(self.events) do
        if not ev:isEmpty() then names[#names + 1] = name end
    end
    return names
end

return WFEngine
