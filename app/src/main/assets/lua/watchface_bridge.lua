-- watchface_bridge.lua
-- Structured data bridge between Android (Java/Kotlin) and the watchface engine (Lua).
--
-- ── DATA ACCESS (per-frame safe, zero overhead) ───────────────────────────────
--   WF.battery.level          -- int 0-100
--   WF.battery.charging       -- bool
--   WF.weather.currentTemp    -- int °C
--   WF.calendar.events[1].title
--   etc.
--
--   IMPORTANT: always read WF.category.field directly. Do NOT cache WF.battery in a
--   local variable — when Java pushes a new snapshot, WF.battery is replaced entirely.
--
-- ── REACTIVE UPDATES ──────────────────────────────────────────────────────────
--   WatchfaceBridge.onChange("battery", function(data) ... end)
--   WatchfaceBridge.offChange("battery", handler)
--
-- ── ENABLE OPTIONAL SOURCES (default: all off) ────────────────────────────────
--   WatchfaceBridge.setWeatherEnabled(true)
--   WatchfaceBridge.setHealthEnabled("steps", true)     -- "steps"|"heartRate"|"spo2"|"sleep"|"stress"
--   WatchfaceBridge.setSensorEnabled("compass", true)   -- "accelerometer"|"compass"|"barometer"
--
-- ── LUA → ANDROID ACTIONS ────────────────────────────────────────────────────
--   WatchfaceBridge.tapAction(shortcut)  -- trigger a watchface shortcut on Android
--
-- ── ANDROID → LUA COMMANDS (Java calls these, engine responds via onChange) ──
--   WatchfaceOnReload()          -- Java tells Lua to reload the current watchface
--   WatchfaceOnReset()           -- Java tells Lua to clear storage and reload
--   Use WatchfaceBridge.onChange("cmd_reload"|"cmd_reset", fn)
--   (Path switching has no command — a settings push carries watchfacePath.)
--
-- ── DATA CATEGORIES & FIELDS ─────────────────────────────────────────────────
--   device:       apiLevel, language, deviceLabel, deviceModel, manufacturer,
--                 productName, osType, appVersion, isRound, lastReboot
--   settings:     watchfacePath, zoom, xOffset, yOffset, fullscreen, frameRate, aodEnabled
--   system:       volume (0-100), brightness (0-255), darkMode, lowPowerMode
--   location:     names[1..5], timezones[1..4]
--   timezones:    tz1/tz2/tz3: {locationShort, locationLong, utcOffset, utcOffsetMinutes, dstActive}
--                 tz1=device local, tz2=location_timezone_1, tz3=location_timezone_2
--                 Compute current time in Lua: os.time() + utcOffsetMinutes*60
--   battery:      level (0-100), charging (bool), temperature (°C), voltage (V), current (mA)
--   network:      wifiEnabled, wifiConnected, wifiSsid, wifiFrequency, wifiSignal (0-4), wifiIp,
--                 dataEnabled, dataConnected, dataType ("4G"…), dataSignal (0-4), dataOperator
--   connectivity: bluetoothEnabled, bluetoothDevice, gpsEnabled
--   notification: count, list[1..10]: {appName, title, content, time (unix ms)},
--                 last: {appName, title, content, time}
--   alarm:        hasAlarm (bool), alarmTime ("HH:mm"), alarmTimestamp (unix ms, 0 if none)
--   storage:      totalStorage, usedStorage, totalRam, usedRam  (bytes)
--   calendar:     hasEvents (bool), events[1..10] each:
--                   exists, id, title, beginTime, endTime, beginDate, endDate,
--                   beginTimeMs, endTimeMs (unix ms), allDay, location, color ("RRGGBB"), calendar
--   weather:      currentTemp, highTemp, lowTemp, conditionText, conditionIcon,
--                 humidity, pressure, windSpeed, windDirection (degrees),
--                 cloudiness, rainVolume, isDaytime, sunrise, sunset ("HH:mm"),
--                 moonPhase (0-1), lastUpdate (unix ms), locationName, tempUnit ("C"),
--                 hourly[1..12]: {temp, conditionText, conditionIcon, hourOfDay (0-23)}
--                 daily[1..5]:  {avgTemp, high, low, conditionText, conditionIcon}
--   health_steps:     steps, distance (km), calories (kcal), stepGoal (from BHT)
--   health_heartRate: bpm, timestamp (unix ms), maxHeartRate (peak from last 24h),
--                     history[1..10]: {bpm, timestamp} (1=oldest, 10=newest)
--   health_spo2:      percent, timestamp (unix ms)
--   health_sleep:     hasData, sleepStart/wake (unix sec),
--                     awakeSec, remSec, lightSec, deepSec, totalSec, asleepSec
--   health_stress:    score (0-100)
--   thirdParty:       slots[1..10]: {data (String), key (String), timestamp (unix ms)}
--   sensor_accelerometer: x, y, z  (m/s²)
--   sensor_gyroscope:     x, y, z  (rad/s)
--   sensor_compass:       azimuth, pitch, roll  (degrees; azimuth -180..180)
--   sensor_barometer:     pressure  (hPa)

local ok_json, json = pcall(require, "cjson")
if not ok_json then
    print("[WatchfaceBridge] cjson load FAILED: " .. tostring(json))
    json = nil
else
    print("[WatchfaceBridge] cjson loaded OK")
end

-- ── Global live-data table ────────────────────────────────────────────────────
-- Pre-initialised with safe defaults so watchface code never reads nil.
-- Java pushes a full snapshot per category; WF[category] is replaced atomically.

local function emptyTz()
    return { locationShort = "", locationLong = "", utcOffset = "", utcOffsetMinutes = 0, dstActive = false }
end

WF = {
    device = {
        apiLevel = 0, language = "", deviceLabel = "", deviceModel = "",
        manufacturer = "", productName = "CloX Launcher", osType = "Full Android", appVersion = "",
        isRound = false, lastReboot = "",
    },
    settings = {
        watchfacePath = false,  -- false = not yet received; engine must gate loading on this
        alignMode = "vertical", sizeX = 1.0, sizeY = 1.0, xOffset = 0, yOffset = 0,
        fullscreen = 0, frameRate = 60, aodEnabled = false,
        -- behaviour toggles (enforced Lua-side); mirror WatchfaceConfigPrefs defaults
        allowSound = false, allowVibrate = true, allowSensors = true,
        allowWeather = true, allowHealth = true, allowTap = true,
    },
    system   = { volume = 0, brightness = 128, darkMode = false, lowPowerMode = false, is24h = false },
    location = { names = { "", "", "", "", "" }, timezones = { "", "", "", "" },
                 latitude = 0, longitude = 0, elevation = 0 },
    timezones = { tz1 = emptyTz(), tz2 = emptyTz(), tz3 = emptyTz() },
    battery  = { level = 0, charging = false, temperature = 0.0, voltage = 0.0, current = 0.0 },
    network  = {
        wifiEnabled = false, wifiConnected = false, wifiSsid = "", wifiFrequency = "", wifiSignal = 0, wifiIp = "",
        dataEnabled = false, dataConnected = false, dataType = "", dataSignal = 0, dataOperator = "",
    },
    connectivity = { bluetoothEnabled = false, bluetoothDevice = "", gpsEnabled = false },
    notification = {
        count = 0,
        list  = (function() local l = {} for i=1,10 do l[i] = {appName="",title="",content="",time=0} end return l end)(),
        last  = { appName = "", title = "", content = "", time = 0 },
    },
    alarm   = { hasAlarm = false, alarmTime = "", alarmTimestamp = 0 },
    storage = { totalStorage = 0, usedStorage = 0, totalRam = 0, usedRam = 0 },
    calendar = { hasEvents = false, events = {} },
    weather  = {
        currentTemp = 0, highTemp = 0, lowTemp = 0, conditionText = "", conditionIcon = 0,
        humidity = 0, pressure = 0, windSpeed = 0, windDirection = 0,
        cloudiness = 0, rainVolume = 0,
        isDaytime = true, sunrise = "", sunset = "",
        moonPhase = 0.0, lastUpdate = 0, locationName = "", tempUnit = "C",
        hourly = {}, daily = {},
    },
    health_steps     = { steps = 0, distance = 0.0, calories = 0.0, stepGoal = 10000 },
    health_heartRate = { bpm = 0, timestamp = 0, maxHeartRate = 200, history = (function()
        local h = {}
        for i = 1, 10 do h[i] = { bpm = 0, timestamp = 0 } end
        return h
    end)() },
    health_spo2 = { percent = 0, timestamp = 0 },
    health_sleep = { hasData = false, sleepStart = 0, wake = 0, awakeSec = 0,
                     remSec = 0, lightSec = 0, deepSec = 0, totalSec = 0, asleepSec = 0 },
    health_stress = { score = 0 },
    -- 10 persistent slots written by third-party apps via broadcast API
    thirdParty = { slots = (function()
        local s = {}
        for i = 1, 10 do s[i] = { data = "", key = "", timestamp = 0 } end
        return s
    end)() },
    sensor_accelerometer = { x = 0.0, y = 0.0, z = 0.0 },
    sensor_gyroscope     = { x = 0.0, y = 0.0, z = 0.0 },
    sensor_compass       = { azimuth = 0.0, pitch = 0.0, roll = 0.0 },
    sensor_barometer     = { pressure = 0.0 },
}

-- ── Internal bridge state ─────────────────────────────────────────────────────

WatchfaceBridge = {}
WatchfaceBridge._handlers = {}

-- ── Transport entry point ─────────────────────────────────────────────────────
-- Called by Kotlin: LuaBridge.callLuaFunction("WatchfaceBridgeDispatch", jsonStr)
-- jsonStr = { "category": "battery", "data": { "level": 85, ... } }

function WatchfaceBridgeDispatch(jsonStr)
    if not json then return end
    local ok, payload = pcall(json.decode, jsonStr)
    if not ok or type(payload) ~= "table" then return end
    local category = payload.category
    local data     = payload.data
    if not category or type(data) ~= "table" then return end

    WF[category] = data  -- replace snapshot; always read WF.x.y, never cache WF.x

    -- The pre-initialised WF.settings stub has watchfacePath = false, which is
    -- ALSO what Java sends when no face is configured — the value alone cannot
    -- tell "not yet received" from "default face requested". This flag can:
    -- face loading is gated on it so a cold start waits for the real settings
    -- push instead of flashing (and announcing) the built-in default.
    if category == "settings" then WF._settingsReceived = true end

    local handlers = WatchfaceBridge._handlers[category]
    if handlers then
        for _, h in ipairs(handlers) do
            local ok2, err = pcall(h, data)
            if not ok2 then
                print("[WatchfaceBridge] handler error [" .. category .. "]: " .. tostring(err))
            end
        end
    end
end

-- ── Reactive subscriptions ────────────────────────────────────────────────────

function WatchfaceBridge.onChange(category, handler)
    if not WatchfaceBridge._handlers[category] then
        WatchfaceBridge._handlers[category] = {}
    end
    table.insert(WatchfaceBridge._handlers[category], handler)
end

function WatchfaceBridge.offChange(category, handler)
    local list = WatchfaceBridge._handlers[category]
    if not list then return end
    for i = #list, 1, -1 do
        if list[i] == handler then table.remove(list, i) end
    end
end

-- ── Enable / disable optional async data sources ─────────────────────────────

-- Ask Android to fetch (or serve from cache) the OSM tile covering lat/lon at
-- `zoom`; the tile comes back as a "map" category push {file, zoom, tx, ty}.
function WatchfaceBridge.requestMapTile(lat, lon, zoom)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({
        action = "requestMapTile", lat = lat, lon = lon, zoom = zoom }))
end

function WatchfaceBridge.setWeatherEnabled(enabled)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setWeatherEnabled", enabled = enabled }))
end

-- metric: "steps" | "heartRate" | "spo2"
function WatchfaceBridge.setHealthEnabled(metric, enabled)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setHealthEnabled", metric = metric, enabled = enabled }))
end

-- sensor: "accelerometer" | "compass" | "barometer" | "gyroscope"
function WatchfaceBridge.setSensorEnabled(sensor, enabled)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "setSensorEnabled", sensor = sensor, enabled = enabled }))
end

-- Report the loaded face's layer count to Android (Information tab statistics),
-- plus the flatten-optimization result: layers merged into cached run textures
-- and how many textures they baked into (config dialog performance tab).
function WatchfaceBridge.reportLayerCount(count, merged, runs)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({
        action = "reportLayerCount", count = count,
        merged = merged or 0, runs = runs or 0,
    }))
end

-- Report the persisted-variable backup size (bytes) for the loaded face.
function WatchfaceBridge.reportStorage(bytes)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "reportStorage", bytes = bytes }))
end

-- ── Lua → Android: watchface shortcut ────────────────────────────────────────

function WatchfaceBridge.tapAction(shortcut)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "tapAction", shortcut = shortcut }))
end

-- Ask Android to open the per-watchface configuration UI (scale/offset/FPS).
-- Lets a watchface (or an in-face tap target) surface the same dialog a gesture
-- would; Android handles it in WatchfaceBridgeManager (action "openConfig").
function WatchfaceBridge.openConfig()
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({ action = "openConfig" }))
end

-- Surface a Lua error (with traceback) to Android so it can be shown in a
-- visible dialog instead of failing silently. Shown in all builds by default
-- (WatchfaceBridgeManager action "luaError"), unless the user has enabled the
-- Debug settings "Ignore Lua errors" toggle. `context` is a short label, `trace`
-- the detail.
function WatchfaceBridge.reportError(context, trace)
    if not json then return end
    Bridge.sendToAndroid("wf_action", json.encode({
        action = "luaError", context = tostring(context or "Lua error"), trace = tostring(trace),
    }))
end

-- ── Android → Lua: watchface lifecycle commands ───────────────────────────────
-- Java calls these globals via callLuaFunction on the GL thread.
-- Watchface engines should listen with WatchfaceBridge.onChange("cmd_*", fn).

function WatchfaceOnReload()
    WF.cmd_reload = {}
    local h = WatchfaceBridge._handlers["cmd_reload"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_reload) end end
end

function WatchfaceOnReset()
    WF.cmd_reset = {}
    local h = WatchfaceBridge._handlers["cmd_reset"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_reset) end end
end

-- Java tells Lua to clear the current face's persisted variables and reload.
function WatchfaceOnClearVars()
    WF.cmd_clearvars = {}
    local h = WatchfaceBridge._handlers["cmd_clearvars"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_clearvars) end end
end

-- Java tells Lua to flush the current face's variables to disk now (app going
-- to background — the process may be killed before onExit/clear can run).
function WatchfaceOnSaveVars()
    WF.cmd_savevars = {}
    local h = WatchfaceBridge._handlers["cmd_savevars"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_savevars) end end
end

-- Java tells Lua the host activity paused/resumed (screen off, or covered by a
-- launched app). Pausing GL does NOT stop MediaEngine's decode thread, so face
-- videos must be stopped explicitly or the codec keeps burning CPU/battery
-- while the face is not visible.
function WatchfaceOnHostPause()
    WF.cmd_hostvis = { visible = false }
    local h = WatchfaceBridge._handlers["cmd_hostvis"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_hostvis) end end
end

function WatchfaceOnHostResume()
    WF.cmd_hostvis = { visible = true }
    local h = WatchfaceBridge._handlers["cmd_hostvis"]
    if h then for _, fn in ipairs(h) do pcall(fn, WF.cmd_hostvis) end end
end
