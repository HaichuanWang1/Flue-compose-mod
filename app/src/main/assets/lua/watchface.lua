-- CloX Watchface — real Watchmaker (.watch) renderer.
--
-- Loads the .watch package pointed to by WF.settings.watchfacePath, extracts it,
-- parses watch.xml and renders it live through wf_render. Reacts to the Android
-- bridge: setting a new watchface, reload, reset, and theme/dim changes. When no
-- watchface is configured it shows a built-in vector face so the screen is never
-- blank.
--
-- Update model (see wf_render / wf_engine): the scene owns the clock and, each
-- tick, fires the engine events that ticked (ms every frame, second/minute/
-- hour/day on their boundaries). Each event recomputes only its active tags;
-- changed tags run the update buckets of exactly the layer properties indexed
-- under them at import. If a face has no sub-second work (no ms tags, no
-- on_millisecond, no tweens) the per-frame loop is suspended and a 1s poll
-- drives the boundaries instead. Async data (weather/health/sensors/battery/…)
-- updates via bridge listeners the renderer registers per face; the scene
-- enables only the data sources a face actually uses (§ feature toggles) and
-- caps FPS (normal max, 1 FPS in AOD).
--
--   bridge.lua            — JNI ↔ Lua event bus
--   watchface_bridge.lua  — structured WF.* live data + WatchfaceBridge actions
--   wf_engine.lua         — WFTag/WFEvent/WFEngine: tag buckets + event firing
--   wf_tagdefs.lua        — per-tag compute catalogue (value + owning event)
--   wf_expr.lua           — import-time attribute → compiled-function compiler
--   wf_binding.lua        — (layer, property) bucket entries
--   wf_sandbox.lua        — restricted env for face scripts/expressions
--   wf_script.lua         — global script host (on_*, vars, wm_*, tweens)
--   wf_render.lua         — watch.xml parsing + cocos layer rendering

require "bridge"
require "watchface_bridge"
local WFRender  = require "wf_render"
local WFProtect = require "wf_protect"

local WatchfaceScene = class("WatchfaceScene", cc.Scene)

-- Reference canvas of the Watchmaker format (see WM_FORMAT_1.0.md §4).
local CANVAS = 512

-- Built-in fallback face — a minimal theme over the app's launcher-background
-- asset: subtle hour ticks, rounded-tip hands, and small digital time / date /
-- battery. Accent #82abd1 on the date, second hand and centre cap. Authored in
-- the format's screen-down Y convention (positive Y = toward the bottom), which
-- the renderer flips into cocos Y-up — so it stays consistent with real faces.
local DEFAULT_FACE = [[
<Watch bg_color="0d0d0d" ucolor_default="82abd1">
  <Layer type="text" x="0" y="-120" alignment="cc" text="{dh23z}:{dmz}" text_size="34" color="ffffff"/>
  <Layer type="text" x="0" y="-84" alignment="cc" text="{dnnn} {dd}" transform="uc" text_size="20" color="{ucolor}"/>
  <Layer type="text" x="0" y="150" alignment="cc" text="{blp}" text_size="20" color="aaaaaa"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="12" height="130" radius="6" corner_type="1" color="ffffff" rotation="{drh}"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="8" height="200" radius="4" corner_type="1" color="ffffff" rotation="{drm}"/>
  <Layer type="rounded" alignment="bc" x="0" y="0" width="4" height="210" radius="2" corner_type="1" color="{ucolor}" rotation="{drss}"/>
  <Layer type="shape" shape="Circle" x="0" y="0" width="16" height="16" color="{ucolor}"/>
</Watch>
]]

-- ── extraction ────────────────────────────────────────────────────────────────

local extractSourcePath = nil    -- last extracted .watch (dedup guard)
local extractDir        = nil

-- Resolve a watchface path (either a .watch ZIP or a pre-extracted directory) into
-- a writable working directory containing watch.xml + assets. Returns the base dir
-- (trailing slash), or nil on failure.
local function extractWatchface(watchPath)
    if not watchPath or watchPath == false or watchPath == "" then return nil end
    local dest = cc.FileUtils:getInstance():getWritablePath() .. "wf_active/"
    if watchPath == extractSourcePath and extractDir then return extractDir end

    -- If watchPath is already an extracted directory containing watch.xml,
    -- use it directly — no unzip needed. This is the path the Kotlin-side
    -- JbWatchFaceStorage.importArchive() copies into /sdcard/jbwatch/<id>/.
    -- Ensure trailing slash so downstream path concatenation works.
    -- NOTE: Use cc.FileUtils (not io.open) for Android external storage compat.
    local _fu = cc.FileUtils:getInstance()
    local function hasWatchXml(dir)
        return _fu:isFileExist(dir .. "/watch.xml")
    end
    local normalizedPath = watchPath
    if not normalizedPath:match("/$") then normalizedPath = normalizedPath .. "/" end
    if hasWatchXml(normalizedPath:sub(1, -2)) then
        -- Purge stale texture cache entries from the previous face before
        -- the renderer reads from a new directory.
        cc.Director:getInstance():getTextureCache():removeAllTextures()
        cc.Director:getInstance():purgeCachedData()
        -- Remove the ZIP-extract working dir (not used for directory-sourced faces).
        axRemoveDir(dest)
        print("[Watchface] using pre-extracted directory: " .. normalizedPath)
        extractSourcePath = watchPath
        extractDir = normalizedPath
        return normalizedPath
    end

    -- Fallback: treat watchPath as a .watch ZIP file and unzip into dest.
    -- Every face extracts into this SAME directory, and the texture cache keys
    -- by absolute path: whatever it holds for wf_active/ paths is about to
    -- describe DIFFERENT files (or none). Stale entries poison later loads via
    -- cache hits (cross-face name collisions), and after an EGL context loss
    -- reloadAllTextures() re-reads these paths from whichever face is on disk
    -- THEN — killing the entries for good (observed live: black sprites/labels
    -- on every previously-loaded face until the process dies). Purge the whole
    -- cache before the files change: the outgoing face's nodes keep their own
    -- texture refs until released, and the incoming face re-reads from disk.
    cc.Director:getInstance():getTextureCache():removeAllTextures()
    cc.Director:getInstance():purgeCachedData()   -- font atlases + sprite frames (same path collisions)
    axRemoveDir(dest)
    if not axUnzipToDir(watchPath, dest) then
        print("[Watchface] axUnzipToDir failed: " .. tostring(watchPath))
        return nil
    end
    extractSourcePath = watchPath
    extractDir = dest
    return dest
end

-- ── scene lifecycle ───────────────────────────────────────────────────────────

function WatchfaceScene:ctor()
    cc.Scene.ctor(self)
    self._elapsed = 0
    self._subsec  = 0
    self._lastSec, self._lastMin, self._lastHour, self._lastDay = -1, -1, -1, -1
    self._currentPath = nil   -- path of the face on screen (false = built-in default)
    self._failedPath  = nil   -- a real path we tried and bounced off (avoid retry thrash)
    self._activeFeatures = { weather = false, health = {}, sensor = {} }
    self._pendingLoad = nil   -- a load request deferred to the GL-safe scheduler pump
    -- reused per-tick event flags — the 60fps path must not allocate
    self._ticks = { frame = false, second = false, minute = false, hour = false, day = false }
end

function WatchfaceScene:onEnter()
    local director = cc.Director:getInstance()
    local sz = director:getWinSize()
    self._W, self._H = sz.width, sz.height

    -- background fill (recoloured per-face from bg_color)
    self._bg = cc.LayerColor:create(cc.c4b(0, 0, 0, 255))
    self:addChild(self._bg, -1)

    -- stage node maps the 512 canvas (origin centre) to the screen
    self._stage = cc.Node:create()
    self:addChild(self._stage, 0)

    -- status overlay (screen space) for load messages
    self._status = cc.Label:createWithSystemFont("", "sans-serif",
        math.max(14, math.floor(self._W / 28)))
    self._status:setTextColor(cc.c4b(200, 200, 200, 255))
    self._status:setAnchorPoint(cc.p(0.5, 0))
    self._status:setPosition(self._W / 2, math.floor(self._H * 0.04))
    self:addChild(self._status, 10)

    self._renderer = WFRender.new(self._stage)

    -- Load now only if a settings push already arrived (WF.settings always
    -- exists as a stub — WF._settingsReceived is the real signal). On a cold
    -- start it hasn't: stay black and wait for the settings push instead of
    -- flashing the built-in default — that default build would announce
    -- wf_loaded and dismiss the Android loading overlay before the real face
    -- even starts loading. The default face is strictly a fallback: no face
    -- configured, or a load failure (both via syncWatchface).
    if WF._settingsReceived then
        self:syncWatchface(WF.settings.watchfacePath)
    end

    self:wireBridge()

    -- Permanent load pump. All face loading (which creates TTF Labels → compiles
    -- GL shaders) must run from a Director scheduler callback, where the GL
    -- context is guaranteed current. Bridge dispatches (which run on the GL thread
    -- via queueEvent, possibly before onSurfaceCreated or during context churn)
    -- only *request* a load; this pump performs it on the next frame. One boolean
    -- check per frame when idle — negligible, and it ticks at the FPS cap (1 in AOD).
    self._loadTimer = cc.Director:getInstance():getScheduler()
        :scheduleScriptFunc(function() self:serviceLoad() end, 0, false)

    -- Touch entry point from Android: GestureInterceptorLayout forwards single
    -- taps via LuaBridge.passTouch → passTouchNative → this global (already on
    -- the GL thread).
    WatchfaceOnTouch = function(x, y, action) self:onTouch(x, y, action) end

    -- Called by a NEW Kotlin bridge attaching to this LIVE engine (activity
    -- recreation reusing the GL view). The fresh manager's sensor/health/
    -- weather state is blank while our feature differ remembers everything as
    -- already-enabled — nothing would be re-sent and e.g. the compass stays
    -- OFF until a face reload. Re-announce features from scratch and re-run
    -- the ready handshake so the new bridge also pushes a full data snapshot.
    WatchfaceOnBridgeRestart = function()
        self._activeFeatures = { weather = false, health = {}, sensor = {} }
        if self._renderer and self._renderer.features then
            self:applyFeatures(self:maskedFeatures())
        end
        Bridge.sendToAndroid("wf_lua_ready", "")
    end

    Bridge.sendToAndroid("wf_lua_ready", "")
end

function WatchfaceScene:onExit()
    WatchfaceOnTouch = nil
    WatchfaceOnBridgeRestart = nil
    local sched = cc.Director:getInstance():getScheduler()
    if self._frameTimer then sched:unscheduleScriptEntry(self._frameTimer); self._frameTimer = nil end
    if self._clockTimer then sched:unscheduleScriptEntry(self._clockTimer); self._clockTimer = nil end
    if self._loadTimer then sched:unscheduleScriptEntry(self._loadTimer); self._loadTimer = nil end
    -- Back up the face's script variables before the scene is torn down (the
    -- activity-destroy case; face-switch is saved in WFRender:clear).
    if self._renderer and self._renderer.script and self._renderer.script.saveVars then
        pcall(function() self._renderer.script:saveVars() end)
    end
    -- Detach the renderer's after-update bake listener — a recreated scene
    -- builds a fresh renderer and the stale closure must not keep firing.
    if self._renderer and self._renderer.shutdown then
        pcall(function() self._renderer:shutdown() end)
    end
    -- release any async sensor streams the face had running
    self:applyFeatures({ weather = false, health = {}, sensor = {} })
end

-- Apply zoom / pan settings and the 512→screen scale to the stage. Scale fits
-- the smaller screen dimension (never overflows), then the per-face zoom/offset
-- nudge for pixel-perfect placement. Positive yOffset moves the face down.
-- Per-axis size multipliers for an alignment mode (Lua mirror of Kotlin
-- WatchfaceConfigPrefs.effectiveSizes — keep the two in lockstep). Reference is
-- the VERTICAL dimension: at size 1 the canvas fills the height.
local function effectiveSizes(mode, sx, sy, W, H)
    if H == 0 then return sx, sy end
    local minRatio = math.min(W, H) / H
    if mode == "vertical" then return 1.0, 1.0
    elseif mode == "horizontal" then return minRatio, minRatio
    elseif mode == "stretch" then return W / H, 1.0
    else return sx, sy end                       -- custom
end

function WatchfaceScene:applyStageTransform()
    local s = WF.settings or {}
    local mode = s.alignMode or "vertical"
    local mx, my = effectiveSizes(mode, tonumber(s.sizeX) or 1.0, tonumber(s.sizeY) or 1.0,
                                  self._W, self._H)
    local base = self._H / CANVAS                 -- size 1 fills the vertical dimension
    self._stage:setScaleX(base * mx)
    self._stage:setScaleY(base * my)
    self._stage:setPosition(self._W / 2 + (tonumber(s.xOffset) or 0),
                            self._H / 2 - (tonumber(s.yOffset) or 0))
    print(string.format("[WFStage] win=%.0fx%.0f mode=%s mx=%.3f my=%.3f base=%.4f",
        self._W, self._H, tostring(mode), mx, my, base))
end

-- Cap the render rate: 1 FPS in AOD, otherwise the per-face max (default 25,
-- unlockable to 60). The heavy per-frame Lua work is already gated by the
-- cadence scheduler; this bounds the GL draw / smooth-hand rate.
function WatchfaceScene:applyFrameRate()
    local s = WF.settings or {}
    local fps
    if s.aodEnabled then
        fps = 1
    elseif s.maxFpsUnlimited then
        fps = 60
    else
        fps = tonumber(s.maxFps) or tonumber(s.frameRate) or 25
    end
    -- A face with no sub-second work (needFrame false) never changes between
    -- clock-poll ticks, but with RENDERMODE_CONTINUOUSLY the GL thread still
    -- redraws the whole scene every interval. Clamp such faces to the poll
    -- rate: 2 FPS keeps a seconds display ticking promptly, 1 FPS suffices
    -- when nothing faster than the minute/sensors changes. Gated by the
    -- "Dynamic FPS" launcher setting (default on).
    local r = self._renderer
    if s.dynamicFps ~= false and r and not r.needFrame then
        fps = math.min(fps, r.needSecond and 2 or 1)
    end
    fps = math.max(1, math.min(fps, 60))
    cc.Director:getInstance():setAnimationInterval(1.0 / fps)
end

-- Enable exactly the async data sources this face uses; disable ones the
-- previous face used but this one doesn't. Diffed against _activeFeatures so a
-- settings push (which arrives often) never re-sends unchanged toggles.
function WatchfaceScene:applyFeatures(f)
    f = f or { weather = false, health = {}, sensor = {} }
    f.health = f.health or {}
    f.sensor = f.sensor or {}
    local prev = self._activeFeatures
    if (f.weather or false) ~= (prev.weather or false) then
        WatchfaceBridge.setWeatherEnabled(f.weather or false)
    end
    for _, m in ipairs({ "steps", "heartRate", "spo2", "sleep", "stress" }) do
        if (f.health[m] or false) ~= (prev.health[m] or false) then
            WatchfaceBridge.setHealthEnabled(m, f.health[m] or false)
        end
    end
    for _, sn in ipairs({ "accelerometer", "compass", "barometer", "gyroscope" }) do
        if (f.sensor[sn] or false) ~= (prev.sensor[sn] or false) then
            WatchfaceBridge.setSensorEnabled(sn, f.sensor[sn] or false)
        end
    end
    self._activeFeatures = f
end

-- Mask the face's declared features by the per-face "allow" toggles so the user
-- can suppress sensors / weather / health without editing the face. (sound,
-- vibrate, tap and video are gated at their own call sites in the renderer/script.)
function WatchfaceScene:maskedFeatures()
    local f = (self._renderer and self._renderer.features)
              or { weather = false, health = {}, sensor = {} }
    local s = WF.settings or {}
    local allowSensors = s.allowSensors ~= false
    local allowWeather = s.allowWeather ~= false
    local allowHealth  = s.allowHealth  ~= false
    local h, sn = f.health or {}, f.sensor or {}
    return {
        weather = allowWeather and (f.weather or false),
        health = {
            steps     = allowHealth and (h.steps or false),
            heartRate = allowHealth and (h.heartRate or false),
            spo2      = allowHealth and (h.spo2 or false),
            sleep     = allowHealth and (h.sleep or false),
            stress    = allowHealth and (h.stress or false),
        },
        sensor = {
            accelerometer = allowSensors and (sn.accelerometer or false),
            gyroscope     = allowSensors and (sn.gyroscope or false),
            compass       = allowSensors and (sn.compass or false),
            barometer     = allowSensors and (sn.barometer or false),
        },
    }
end

-- Start (or restart) the clock driver for the loaded face. A face with any
-- sub-second work (ms tags, on_millisecond, live tweens) runs the per-frame
-- scheduler for smooth motion; otherwise a 1s poll drives the boundaries and
-- the per-frame loop stays suspended.
function WatchfaceScene:restartClock()
    local sched = cc.Director:getInstance():getScheduler()
    if self._frameTimer then sched:unscheduleScriptEntry(self._frameTimer); self._frameTimer = nil end
    if self._clockTimer then sched:unscheduleScriptEntry(self._clockTimer); self._clockTimer = nil end
    self._lastSec, self._lastMin, self._lastHour, self._lastDay = -1, -1, -1, -1
    self._subsec = 0
    self._tickCount = 0   -- re-arm the [WFTick] driver-started log
    if self._renderer.needFrame then
        self._frameTimer = sched:scheduleScriptFunc(function()
            self:tick(cc.Director:getInstance():getDeltaTime())
        end, 0, false)
    else
        self._clockTimer = sched:scheduleScriptFunc(function() self:tick(1.0) end, 1.0, false)
    end
end

-- Common post-load wiring: stage transform, feature toggles, clock driver, FPS.
function WatchfaceScene:afterLoad()
    self:applyStageTransform()
    self:applyFeatures(self:maskedFeatures())
    self:restartClock()
    self:applyFrameRate()
    if self._renderer and self._renderer.recs and WatchfaceBridge.reportLayerCount then
        WatchfaceBridge.reportLayerCount(#self._renderer.recs,
            self._renderer._flatMerged or 0, self._renderer._flatRuns or 0)
    end
    -- Persisted-variable backup size on disk (config Info tab "Data stored").
    if self._renderer and self._renderer.script and self._renderer.script.persistedSize
       and WatchfaceBridge.reportStorage then
        WatchfaceBridge.reportStorage(self._renderer.script:persistedSize())
    end
    -- a tap script scheduling the first tween can flip the frame-loop need
    -- while the per-frame scheduler is suspended — restart the driver and
    -- lift/restore the cadence FPS clamp then
    self._renderer.onNeedFrame = function()
        self:restartClock()
        self:applyFrameRate()
    end
end

-- ── loading ───────────────────────────────────────────────────────────────────

function WatchfaceScene:loadWatchface(path)
    local base = extractWatchface(path)
    if not base then return false end
    -- Protected faces (protection="y") keep the real definition in watch.pxml
    -- (obfuscated) and a stub watch.xml; decode the pxml when present.
    local xml
    if WFProtect.isProtected(base) then
        xml = WFProtect.readPxml(base)
        print("[Watchface] protected face — decoded watch.pxml (" .. #xml .. " bytes)")
    else
        xml = cc.FileUtils:getInstance():getStringFromFile(base .. "watch.xml")
    end
    if not xml or #xml == 0 then
        print("[Watchface] watch definition missing/empty in " .. base)
        return false
    end
    local ok, err = xpcall(function() self._renderer:load(xml, base) end, debug.traceback)
    if not ok then
        print("[Watchface] render failed: " .. tostring(err))
        WatchfaceBridge.reportError("Watchface load failed", err)
        return false
    end
    -- A face that parsed to nothing renderable is a failure → fall back, never
    -- leave a black screen.
    if #self._renderer.recs == 0 then
        print("[Watchface] no renderable layers in " .. tostring(path))
        return false
    end
    self:setBg(self._renderer.bgColor)
    self._status:setString("")
    self._currentPath = path
    self:afterLoad()
    Bridge.sendToAndroid("wf_loaded", self._renderer.watch.name or "")
    return true
end

function WatchfaceScene:loadDefault(msg)
    local base = cc.FileUtils:getInstance():getWritablePath()   -- no packaged assets needed
    local ok, err = xpcall(function() self._renderer:load(DEFAULT_FACE, base) end, debug.traceback)
    if not ok then
        print("[Watchface] default render failed: " .. tostring(err))
        WatchfaceBridge.reportError("Default face render failed", err)
    end
    self:setBg(self._renderer.bgColor)
    self._currentPath = false   -- false = built-in default is on screen
    self:afterLoad()
    -- The built-in face counts as a successful build: Kotlin keys the loading
    -- placeholder AND the renderer watchdog on a wf_loaded after every load
    -- request (watchface change / reload / reset), including fallbacks.
    Bridge.sendToAndroid("wf_loaded", "default")
    if msg then self:flashStatus(msg) end
end

function WatchfaceScene:setBg(hex)
    hex = tostring(hex or "000000")
    local r = tonumber(hex:sub(1, 2), 16) or 0
    local g = tonumber(hex:sub(3, 4), 16) or 0
    local b = tonumber(hex:sub(5, 6), 16) or 0
    self._bg:setColor(cc.c3b(r, g, b))
end

function WatchfaceScene:flashStatus(msg)
    self._status:setString(msg)
    self._status:setOpacity(255)
    self._status:stopAllActions()
    self._status:runAction(cc.Sequence:create(
        cc.DelayTime:create(2.5), cc.FadeOut:create(0.6),
        cc.CallFunc:create(function() self._status:setString("") end)))
end

-- ── bridge wiring ─────────────────────────────────────────────────────────────

-- Queue a load request; the GL-safe pump (serviceLoad) performs it on the next
-- frame. `path` is a real path string, or false/"" for the built-in default.
-- Last write wins, so a burst of settings pushes coalesces into a single load.
function WatchfaceScene:requestLoad(path)
    self._pendingLoad = { path = path, defer = 1 }
end

-- Perform any queued load. Called only from the scheduler pump (GL context
-- current), so this is the ONLY place face loading — and thus TTF Label / shader
-- creation — runs. Loading from a raw bridge dispatch instead would risk a native
-- shader-compile abort when the GL context isn't current (see onEnter).
function WatchfaceScene:serviceLoad()
    local req = self._pendingLoad
    if not req then return end
    -- Let one frame present before the blocking load. After a surface
    -- (re)creation — returning home from the selector — GLSurfaceView's
    -- surfaceChanged BLOCKS the Android main thread until the GL thread swaps
    -- a frame; loading inside that first frame freezes the whole app UI
    -- (stuck selector zoom, loading overlay never drawn) for the entire
    -- extract+build. One presented frame releases the main thread and lets
    -- the loading overlay appear and animate while the load runs.
    if req.defer > 0 then
        req.defer = req.defer - 1
        return
    end
    self._pendingLoad = nil
    self:syncWatchface(req.path)
end

-- Drive the display to match the requested path: load a real face, or fall back
-- to the built-in default when the path is empty/false or the face won't load.
-- Tracks _currentPath (what's shown; false = default) and _failedPath (a real
-- path we already tried and bounced off) so repeated settings pushes — which
-- arrive on every zoom/AOD tick — don't reload or thrash the screen.
function WatchfaceScene:syncWatchface(path)
    if type(path) == "string" and path ~= "" then
        if path == self._currentPath or path == self._failedPath then
            self:applyStageTransform()          -- same target, only zoom/pan moved
        elseif self:loadWatchface(path) then
            self._failedPath = nil
        else
            self._failedPath = path
            self:loadDefault("Couldn't load watchface — showing default")
        end
    elseif WF._settingsReceived then
        if self._currentPath ~= false then
            self:loadDefault()                  -- switched to no/default watchface
        else
            self:applyStageTransform()
        end
        self._failedPath = nil
    end
    -- else: no settings push yet (cold start) — keep waiting under the loading
    -- overlay; the settings push queues another load when it lands.
end

function WatchfaceScene:wireBridge()
    -- Java pushes a full settings snapshot whenever the watchface path, zoom,
    -- offsets, FPS or AOD state changes. Resolve the requested face, follow dim
    -- mode, and re-apply the stage transform + frame-rate cap.
    WatchfaceBridge.onChange("settings", function(data)
        if not data then return end
        if data.aodEnabled ~= nil then
            self._renderer:setDim(data.aodEnabled and true or false)
        end
        -- accent override is a plain {ucolor} publish — live, no reload
        if self._renderer and self._renderer.setColorOverride then
            self._renderer:setColorOverride(
                type(data.colorOverride) == "string" and data.colorOverride or nil)
        end
        -- The layer-merge toggle only takes effect at build time: when it flips
        -- for the face already on screen, force the (otherwise same-path) load
        -- through so the config dialog previews it live.
        local optimize = data.optimizeLayers ~= false
        if self._optimizeApplied ~= nil and self._optimizeApplied ~= optimize
           and data.watchfacePath == self._currentPath then
            self._currentPath = nil   -- else syncWatchface skips the reload (same path)
        end
        self._optimizeApplied = optimize
        -- Loading is deferred to the scheduler pump (GL-safe); the transform and
        -- frame-rate touch no GL resources, so apply them immediately for a
        -- responsive zoom/AOD change.
        self:requestLoad(data.watchfacePath)
        self:applyStageTransform()
        self:applyFrameRate()
        -- Behaviour toggles can change live (config dialog) — re-mask the face's
        -- features so allow-sensors/weather/health take effect without a reload.
        if self._renderer then
            self:applyFeatures(self:maskedFeatures())
            if self._renderer.applyVideoVolume then self._renderer:applyVideoVolume() end
        end
    end)

    -- explicit lifecycle commands (watchface_bridge.lua §ANDROID → LUA). These
    -- only queue a load; the extract-cache side effects below touch no GL state.
    -- (Path switching lives in the "settings" handler above — a pref change
    -- pushes watchfacePath; there is no separate set-active command.)
    WatchfaceBridge.onChange("cmd_reload", function()
        extractSourcePath = nil   -- force re-extract
        self._failedPath = nil
        self._currentPath = nil   -- else syncWatchface skips the reload (same path)
        self:requestLoad(WF.settings and WF.settings.watchfacePath)
    end)
    WatchfaceBridge.onChange("cmd_reset", function()
        axRemoveDir(cc.FileUtils:getInstance():getWritablePath() .. "wf_active/")
        extractSourcePath, extractDir = nil, nil
        self._failedPath = nil
        self._currentPath = nil   -- else syncWatchface skips the reload (same path)
        self:requestLoad(WF.settings and WF.settings.watchfacePath)
    end)
    -- App backgrounding: flush the current face's variables to disk now, so a
    -- kill while backgrounded doesn't lose them (onExit only fires on a real
    -- scene teardown, not a plain background).
    WatchfaceBridge.onChange("cmd_savevars", function()
        if self._renderer and self._renderer.script and self._renderer.script.saveVars then
            pcall(function() self._renderer.script:saveVars() end)
        end
    end)
    -- Host pause/resume (screen off, or covered by a launched app): stop/restart
    -- face videos. MediaEngine (MediaCodec) decodes on its own thread — pausing GL
    -- does NOT stop the codec, so a playing face video kept the decoder at full
    -- rate with the display dark (~35% CPU while asleep).
    WatchfaceBridge.onChange("cmd_hostvis", function(s)
        if self._renderer and self._renderer.setVideosRunning then
            pcall(function() self._renderer:setVideosRunning(s and s.visible ~= false) end)
        end
    end)
    -- "Clear persistent storage" from the config dialog: delete the current
    -- face's variable backup + wipe its live vars, then reload (fresh state).
    WatchfaceBridge.onChange("cmd_clearvars", function()
        if self._renderer and self._renderer.script and self._renderer.script.clearPersisted then
            pcall(function() self._renderer.script:clearPersisted() end)
        end
        self._currentPath = nil   -- else syncWatchface skips the reload (same path)
        self:requestLoad(WF.settings and WF.settings.watchfacePath)
    end)
end

-- ── touch → tap dispatch ──────────────────────────────────────────────────────

-- (x, y) are Android view pixels (origin top-left, Y down). Undo the stage
-- transform (position + scale, set in applyStageTransform) to get 512-canvas
-- coordinates, then let the renderer pick the tapped layer.
function WatchfaceScene:onTouch(x, y, action)
    if action ~= 1 then return end              -- MotionEvent.ACTION_UP only
    if self._currentPath == nil then return end -- nothing loaded yet (cold-start wait)
    local glY = self._H - y                     -- android Y-down → cocos Y-up
    local sx, sy = self._stage:getPosition()
    local scx = self._stage:getScaleX()         -- stage may be deformed (scaleX ≠ scaleY)
    local scy = self._stage:getScaleY()
    if not scx or scx == 0 or not scy or scy == 0 then return end
    local cx = (x - sx) / scx
    local cy = (glY - sy) / scy
    print(string.format("[Watchface] tap screen(%.0f,%.0f) canvas(%.1f,%.1f)", x, y, cx, cy))
    self._renderer:tapAt(cx, cy)
end

-- ── per-frame / per-tick update ───────────────────────────────────────────────

function WatchfaceScene:tick(dt)
    local t = os.date("*t")
    -- clock-driver liveness: one line when a driver starts ticking, then a
    -- rare heartbeat. A frozen face with no [WFTick] after restartClock means
    -- the scheduler is starved (see the _AX_DEBUG delta clamp in Director.cpp,
    -- raised 0.2s→2s for the 1 FPS static-face cadence — 2026-07-09).
    self._tickCount = (self._tickCount or 0) + 1
    if self._tickCount == 1 or self._tickCount % 3600 == 0 then
        print(string.format("[WFTick] n=%d dt=%.2f sec=%d frameTimer=%s clockTimer=%s",
            self._tickCount, dt, t.sec, tostring(self._frameTimer), tostring(self._clockTimer)))
    end
    -- phase-lock the sub-second clock to real seconds for clean hand ticks
    if t.sec ~= self._lastSec then
        self._subsec = 0
    else
        self._subsec = math.min(1, self._subsec + dt)
    end
    self._elapsed = self._elapsed + dt

    local r = self._renderer
    local ticks = self._ticks
    ticks.frame  = r.needFrame
    ticks.second = t.sec  ~= self._lastSec
    ticks.minute = t.min  ~= self._lastMin
    ticks.hour   = t.hour ~= self._lastHour
    ticks.day    = t.day  ~= self._lastDay

    -- The engine does work proportional to what changed: each fired event
    -- recomputes only its active tags, and only changed tags run their layer
    -- buckets. No snapshot rebuilds, no per-layer re-scans.
    r:tick(t, self._subsec, self._elapsed, ticks)

    self._lastSec, self._lastMin  = t.sec, t.min
    self._lastHour, self._lastDay = t.hour, t.day
end

return WatchfaceScene
