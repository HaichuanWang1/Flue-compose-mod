-- wf_tagdefs.lua — the {tag} catalogue: one compute function per tag, plus the
-- event that owns it.
--
-- This replaces both halves of the old pipeline in one declarative table:
--   • wf_tags.build      (WHAT each tag's value is)
--   • wf_cadence.classify (WHEN it can change)
--
-- A def is { ev = <event-name or list>, fn = function(c) → value }.
-- Event names:
--   clock:  "ms" | "second" | "minute" | "hour" | "day" | "static"
--   bridge: the WF.* category Java pushes — "battery", "weather",
--           "health_steps", "health_heartRate", "health_spo2",
--           "health_sleep", "health_stress",
--           "sensor_accelerometer", "sensor_gyroscope", "sensor_compass",
--           "sensor_barometer", "calendar", "notification", "network",
--           "connectivity", "system", "storage", "alarm", "location",
--           "timezones", "settings"
--   other:  "theme" (published by the renderer on theme switch)
-- A def may list several events ({"minute","timezones"}) when its value moves
-- with the clock AND with pushed data.
--
-- `c` is the engine's clock context: c.t (os.date("*t")), c.subsec (0..1),
-- c.elapsed (seconds since face load), plus memoised c:epoch() and c:utc().
-- Compute functions are ENGINE code — they read the launcher's WF table
-- directly and never run inside the face sandbox.
--
-- Value formats are ported verbatim from the old wf_tags.build so every
-- existing face renders identically. See WM_TAGS.md for the catalogue.

local WFTagDefs = {}

local floor, fmt = math.floor, string.format
local function z2(n)  return fmt("%02d", n) end
local function num(v) return tonumber(v) or 0 end
local function boolStr(b) return b and "true" or "false" end

local MONTHS_FULL = { "January","February","March","April","May","June",
                      "July","August","September","October","November","December" }
local MONTHS_3    = { "Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec" }
local DOW_FULL    = { "Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday" }
local DOW_3       = { "Sun","Mon","Tue","Wed","Thu","Fri","Sat" }
local DOW_2       = { "Su","Mo","Tu","We","Th","Fr","Sa" }
local DOW_1       = { "S","M","T","W","T","F","S" }

local BEAR_8  = { "N","NE","E","SE","S","SW","W","NW" }
local BEAR_16 = { "N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW" }
local function bearing(deg, list)
    local n = #list
    local idx = floor((deg % 360) / (360 / n) + 0.5) % n
    return list[idx + 1]
end

local function h12of(h23) local h = h23 % 12; if h == 0 then h = 12 end return h end

-- number → English words for the spelled-out time tags ({dht}/{dmat}/{dsat}…).
-- WM writes compounds with a space: 46 → "forty six".
local ONES = { [0]="zero","one","two","three","four","five","six","seven","eight","nine",
               "ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen",
               "seventeen","eighteen","nineteen" }
local TENS = { [2]="twenty",[3]="thirty",[4]="forty",[5]="fifty" }
local function numWord(n)
    n = floor(n)
    if n < 0 then n = 0 end
    if n < 20 then return ONES[n] end
    local t, o = floor(n / 10), n % 10
    if o == 0 then return TENS[t] or "" end
    return (TENS[t] or "") .. " " .. ONES[o]
end

-- WM/UL word-clock split for minutes/seconds (UL WatchSkin ground truth):
--   0     → "o'" / "clock"        1–9  → "o'" / "three"
--   10–19 → "eleven" / ""         20+  → "forty" / "six" ("" on exact tens)
local function timeWordParts(n)
    if n == 0 then return "o'", "clock" end
    if n < 10 then return "o'", ONES[n] end
    if n < 20 then return ONES[n], "" end
    local o = n % 10
    return TENS[floor(n / 10)] or "", (o == 0) and "" or ONES[o]
end
-- full form: "o'clock" / "o'three" (no space after o'), "eleven", "forty six"
local function timeWordFull(n)
    local a, b = timeWordParts(n)
    if a == "o'" then return a .. b end
    if b == "" then return a end
    return a .. " " .. b
end

-- "HH:MM"(:SS) clock string → fraction of the day (0..1); 0 when absent or
-- unparseable. Byte walk, no patterns (runtime path). Used by {wsrp}/{wssp}.
local function dayFrac(hm)
    if type(hm) ~= "string" or #hm == 0 then return 0 end
    local h, m, phase = 0, 0, 1
    for i = 1, #hm do
        local b = hm:byte(i)
        if b == 58 then                       -- ':'
            phase = phase + 1
            if phase > 2 then break end       -- ignore seconds
        elseif b >= 48 and b <= 57 then
            if phase == 1 then h = h * 10 + (b - 48)
            elseif phase == 2 then m = m * 10 + (b - 48) end
        end
    end
    if phase == 1 then return 0 end           -- no colon → not a clock string
    return (h * 60 + m) / 1440
end
local function daysInMonth(yr, mo)
    local nd = { 31,28,31,30,31,30,31,31,30,31,30,31 }
    if mo == 2 and (yr % 4 == 0 and (yr % 100 ~= 0 or yr % 400 == 0)) then return 29 end
    return nd[mo]
end

-- ── the exact-name catalogue ──────────────────────────────────────────────────

local D = {}
local function def(ev, tbl)
    for name, fn in pairs(tbl) do D[name] = { ev = ev, fn = fn } end
end

-- ms — sub-second motion, recomputed every rendered frame
def("ms", {
    dss  = function(c) return floor(c.subsec * 1000) end,
    dssz = function(c) return fmt("%03d", floor(c.subsec * 1000)) end,
    dsps = function(c) return c.t.sec * 1000 + floor(c.subsec * 1000) end,
    drss = function(c) return (c.t.sec + c.subsec) / 60 * 360 end,
    drms = function(c) return c.subsec * 360 end,
    c_elapsed = function(c) return c.elapsed end,
})

-- stopwatch — shared state in wf_stopwatch, driven by the sw_start_stop /
-- sw_reset tap actions. WM renders swh/swm/sws/swss/swsss as ZERO-PADDED
-- strings ("00","02","68","689" — screenshot-confirmed vs WatchMaker
-- 2026-07-07; an earlier note claimed UL-style plain numbers). The tsw*
-- variants are UL-only aliases (WM leaves them empty) and stay padded too.
-- While stopped the values don't change, so the ms recompute is diffed away.
local SW = require "wf_stopwatch"
local function swms(c) return SW.durationMs(c.elapsed) end
def("ms", {
    swr   = function()  return boolStr(SW.running) end,
    swsst = function(c) return floor(swms(c)) end,
    swh   = function(c) return z2(floor(swms(c) / 3600000)) end,
    swm   = function(c) return z2(floor(swms(c) % 3600000 / 60000)) end,
    sws   = function(c) return z2(floor(swms(c) % 60000 / 1000)) end,
    swss  = function(c) return z2(floor(swms(c) % 1000 / 10)) end,
    swsss = function(c) return fmt("%03d", floor(swms(c) % 1000)) end,
    tswh  = function(c) return z2(floor(swms(c) / 3600000)) end,
    tswm  = function(c) return z2(floor(swms(c) % 3600000 / 60000)) end,
    tsws  = function(c) return z2(floor(swms(c) % 60000 / 1000)) end,
    tswss = function(c) return z2(floor(swms(c) % 1000 / 10)) end,
    tswsss = function(c) return fmt("%03d", floor(swms(c) % 1000)) end,
    tswsst = function(c) return floor(swms(c)) end,
    swrm  = function(c) return floor(swms(c) % 3600000 / 60000) * 6.0 end,
    swrs  = function(c) return floor(swms(c) % 60000 / 1000) * 6.0 end,
    swrss = function(c)
        local ms = swms(c)
        return floor(ms % 60000 / 1000) * 6.0 + 6 * (ms % 1000) / 1000
    end,
})

-- second
def("second", {
    ds   = function(c) return c.t.sec end,
    dsz  = function(c) return z2(c.t.sec) end,
    dst  = function(c) return floor(c.t.sec / 10) end,
    dso  = function(c) return c.t.sec % 10 end,
    dsat = function(c) return timeWordFull(c.t.sec) end,
    dstt = function(c) local a = timeWordParts(c.t.sec) return a end,
    dsot = function(c) local _, b = timeWordParts(c.t.sec) return b end,
    drs  = function(c) return c.t.sec / 60 * 360 end,
    dtp  = function(c) return (c.t.hour * 3600 + c.t.min * 60 + c.t.sec) / 86400 end,
    depoch = function(c) return c:epoch() end,
    -- drm/drh fold seconds into their formulas (gliding hands), so they are
    -- second-event tags: they must recompute per second AND count as a fast
    -- dep so hand layers are never flattened into a static run.
    drm  = function(c) return (c.t.min + c.t.sec / 60) / 60 * 360 end,
    drh  = function(c) return ((c.t.hour % 12) + c.t.min / 60 + c.t.sec / 3600) / 12 * 360 end,
})

-- minute
def("minute", {
    dm   = function(c) return c.t.min end,
    dmz  = function(c) return z2(c.t.min) end,
    dmt  = function(c) return floor(c.t.min / 10) end,
    dmo  = function(c) return c.t.min % 10 end,
    dmat = function(c) return timeWordFull(c.t.min) end,
    dmtt = function(c) local a = timeWordParts(c.t.min) return a end,
    dmot = function(c) local _, b = timeWordParts(c.t.min) return b end,
    drh24 = function(c) return (c.t.hour + c.t.min / 60) / 24 * 360 end,
    da   = function(c) return (c.t.hour < 12) and "AM" or "PM" end,
    dz   = function(c) return os.date("%Z", c:epoch()) end,
})

-- hour
def("hour", {
    dh    = function(c) return h12of(c.t.hour) end,
    dhz   = function(c) return z2(h12of(c.t.hour)) end,
    dh11  = function(c) return c.t.hour % 12 end,
    dh11z = function(c) return z2(c.t.hour % 12) end,
    dh24  = function(c) return (c.t.hour == 0) and 24 or c.t.hour end,
    dh24z = function(c) return z2((c.t.hour == 0) and 24 or c.t.hour) end,
    dh23  = function(c) return c.t.hour end,
    dh23z = function(c) return z2(c.t.hour) end,
    dh23i = function(c) return c.t.hour end,
    dhtt  = function(c) return floor(h12of(c.t.hour) / 10) end,
    dhto  = function(c) return h12of(c.t.hour) % 10 end,
    dh11tt = function(c) return floor((c.t.hour % 12) / 10) end,
    dh11to = function(c) return (c.t.hour % 12) % 10 end,
    dh24tt = function(c) local h = (c.t.hour == 0) and 24 or c.t.hour; return floor(h / 10) end,
    dh24to = function(c) local h = (c.t.hour == 0) and 24 or c.t.hour; return h % 10 end,
    dh23tt = function(c) return floor(c.t.hour / 10) end,
    dh23to = function(c) return c.t.hour % 10 end,
    dht    = function(c) return numWord(h12of(c.t.hour)) end,
    dh24t  = function(c) return numWord((c.t.hour == 0) and 24 or c.t.hour) end,
    -- UTC offset as ±HHMM ("+0800"); minute math across the local/UTC day
    -- boundary, clamping the month wrap to ±1 day.
    dutcoff = function(c)
        local lt, ut = c.t, c:utc()
        local dayd = lt.day - ut.day
        if dayd > 1 then dayd = -1 elseif dayd < -1 then dayd = 1 end
        local diff = (lt.hour * 60 + lt.min) - (ut.hour * 60 + ut.min) + dayd * 1440
        local sign = diff < 0 and "-" or "+"
        local a = diff < 0 and -diff or diff
        return sign .. z2(floor(a / 60)) .. z2(a % 60)
    end,
    dhutc24  = function(c) return c:utc().hour end,
    dhutc24z = function(c) return z2(c:utc().hour) end,
    dhutc12  = function(c) return h12of(c:utc().hour) end,
    dhutc12z = function(c) return z2(h12of(c:utc().hour)) end,
    drh0  = function(c) return (c.t.hour % 12) / 12 * 360 end,
})

-- day
def("day", {
    dd   = function(c) return c.t.day end,
    ddz  = function(c) return z2(c.t.day) end,
    ddy  = function(c) return c.t.yday end,
    ddiy = function(c)
        local y = c.t.year
        return (y % 4 == 0 and (y % 100 ~= 0 or y % 400 == 0)) and 366 or 365
    end,
    ddim = function(c) return daysInMonth(c.t.year, c.t.month) end,
    ddw0 = function(c) return c.t.wday - 1 end,
    ddw  = function(c) return DOW_3[c.t.wday] end,
    ddww = function(c) return DOW_FULL[c.t.wday] end,
    ddw2 = function(c) return DOW_2[c.t.wday] end,
    ddw1 = function(c) return DOW_1[c.t.wday] end,
    dn   = function(c) return c.t.month end,
    dnn  = function(c) return z2(c.t.month) end,
    dnnn = function(c) return MONTHS_3[c.t.month] end,
    dnnnn = function(c) return MONTHS_FULL[c.t.month] end,
    dy   = function(c) return fmt("%02d", c.t.year % 100) end,
    dyy  = function(c) return c.t.year end,
    dw   = function(c) return (tonumber(os.date("%W", c:epoch())) or 0) + 1 end,
    -- calendar week-of-month (WM): week 1 is the week containing the 1st,
    -- weeks start on Sunday — NOT ceil(day/7) (2026-07-07 is week 2, the
    -- old formula said 1).
    dwm  = function(c)
        local firstW = (c.t.wday - 1 - (c.t.day - 1)) % 7   -- weekday of the 1st, 0=Sun
        return floor((c.t.day - 1 + firstW) / 7) + 1
    end,
})

-- ── battery ──
def("battery", {
    bl   = function() return floor(num(WF.battery.level)) end,
    blp  = function() return floor(num(WF.battery.level)) .. "%" end,
    br   = function() return num(WF.battery.level) * 3.6 end,
    btc  = function() return floor(num(WF.battery.temperature)) end,
    btf  = function() return floor(num(WF.battery.temperature) * 9 / 5 + 32) end,
    btcd = function() return floor(num(WF.battery.temperature)) .. "°C" end,
    btfd = function() return floor(num(WF.battery.temperature) * 9 / 5 + 32) .. "°F" end,
    bc   = function() return WF.battery.charging and "Charging" or "Discharging" end,
    bv   = function() return WF.battery.voltage or 0 end,
    bi   = function() return WF.battery.current or 0 end,
    -- phone battery {pb*}: standalone watch has no companion phone, so WM
    -- mirrors the local battery — match that instead of pinning 0.
    pbl   = function() return floor(num(WF.battery.level)) end,
    pblp  = function() return floor(num(WF.battery.level)) .. "%" end,
    pbr   = function() return num(WF.battery.level) * 3.6 end,
    pbtc  = function() return floor(num(WF.battery.temperature)) end,
    pbtf  = function() return floor(num(WF.battery.temperature) * 9 / 5 + 32) end,
    pbtcd = function() return floor(num(WF.battery.temperature)) .. "°C" end,
    pbtfd = function() return floor(num(WF.battery.temperature) * 9 / 5 + 32) .. "°F" end,
    pbc   = function() return WF.battery.charging and "Charging" or "Discharging" end,
})

-- ── weather (current conditions) ──
def("weather", {
    wl   = function() return WF.weather.locationName or "" end,
    wml  = function() return WF.weather.locationName or "" end,   -- manual weather location
    wt   = function() return floor(num(WF.weather.currentTemp)) end,
    wm   = function() return WF.weather.tempUnit or "C" end,
    wtd  = function() return floor(num(WF.weather.currentTemp)) .. "°" .. (WF.weather.tempUnit or "C") end,
    wth  = function() return floor(num(WF.weather.highTemp)) end,
    wthd = function() return floor(num(WF.weather.highTemp)) .. "°" .. (WF.weather.tempUnit or "C") end,
    wtl  = function() return floor(num(WF.weather.lowTemp)) end,
    wtld = function() return floor(num(WF.weather.lowTemp)) .. "°" .. (WF.weather.tempUnit or "C") end,
    wct  = function() return WF.weather.conditionText or "" end,
    wci  = function()
        -- WM's {wci} is the OWM-style icon code faces compare with == "01d"
        -- etc; the bridge pushes the numeric part only (1,2,3,4,9,10,11,13,
        -- 50) — zero-pad and ALWAYS suffix "d". WM's own editor generates
        -- day-code-only comparisons even for night-enabled weather elements
        -- (confirmed 2026-07-10 against a WM-exported test face), so a real
        -- "n" suffix made EVERY face fall to its `or 1` fallback at night.
        -- Night is signalled separately: {wisday}, and weather_night="Y"
        -- sheet/cell swapping inside the image_cond builder.
        local n = num(WF.weather.conditionIcon)
        if n <= 0 then return "" end
        return fmt("%02d", n) .. "d"
    end,
    wh   = function() return floor(num(WF.weather.humidity)) end,
    whp  = function() return floor(num(WF.weather.humidity)) .. "%" end,
    wp   = function() return floor(num(WF.weather.pressure)) end,
    wws  = function() return num(WF.weather.windSpeed) end,
    wwd  = function() return num(WF.weather.windDirection) end,
    wwdb = function() return bearing(num(WF.weather.windDirection), BEAR_8) end,
    wwdbb = function() return bearing(num(WF.weather.windDirection), BEAR_16) end,
    -- WM: cloud cover is a 0..1 FRACTION (0.42 = 42%), not the percent the
    -- providers push — divide down (user-confirmed vs WatchMaker 2026-07-07)
    wcl  = function() return num(WF.weather.cloudiness) / 100 end,
    wr   = function() return num(WF.weather.rainVolume) end,
    wisday = function() return boolStr(WF.weather.isDaytime) end,
    wsr  = function() return WF.weather.sunrise or "" end,
    wss  = function() return WF.weather.sunset or "" end,
    wsrp = function() return dayFrac(WF.weather.sunrise) end,   -- sunrise as fraction of day
    wssp = function() return dayFrac(WF.weather.sunset) end,    -- sunset as fraction of day
    wmp  = function()
        -- Kotlin pushes the raw lunation FRACTION (0=new, .5=full, 1=new
        -- again); WM's {wmp} is the integer 0..9 phase index (1=young,
        -- 5=full, 9=old) that faces compare with == — a fractional value
        -- never matches. Values already > 1 pass through as an index.
        local f = num(WF.weather.moonPhase)
        if f <= 1 then return floor(f * 10) % 10 end
        return floor(f)
    end,
    -- WM shows the last-update moment as a clock string, not epoch millis
    wlu  = function()
        local ms = num(WF.weather.lastUpdate)
        if ms <= 0 then return "" end
        return os.date("%H:%M:%S", floor(ms / 1000))
    end,
})

-- ── health & activity ──
def("health_steps", {
    ssc   = function() return floor(num(WF.health_steps.steps)) end,
    stsc  = function() return floor(num(WF.health_steps.stepGoal)) end,
    sscp  = function()
        local goal = floor(num(WF.health_steps.stepGoal))
        return goal > 0 and floor(floor(num(WF.health_steps.steps)) / goal * 100) or 0
    end,
    sdst  = function() return num(WF.health_steps.distance) end,
    stdst = function() return 8.0 end,
    sdstp = function() return floor(num(WF.health_steps.distance) / 8.0 * 100) end,
    sdstu = function() return "km" end,
    scal  = function() return num(WF.health_steps.calories) end,
    stcal = function() return 350 end,
    scalp = function() return floor(num(WF.health_steps.calories) / 350 * 100) end,
})
def("health_heartRate", {
    shr  = function() return floor(num(WF.health_heartRate.bpm)) end,
    sthr = function() return floor(num(WF.health_heartRate.maxHeartRate)) end,
    shrp = function()
        local mx = floor(num(WF.health_heartRate.maxHeartRate))
        return mx > 0 and floor(floor(num(WF.health_heartRate.bpm)) / mx * 100) or 0
    end,
})
def("health_spo2", {
    sbo = function() return floor(num(WF.health_spo2.percent)) end,
})
-- Sleep tags are CloX extensions (no WM equivalent) — backed by BHT 2.0's
-- sleep endpoint. Times are "--:--" and durations 0 until a session exists.
def("health_sleep", {
    sslm  = function() return floor(num(WF.health_sleep.asleepSec) / 60) end,
    sslh  = function() return fmt("%.1f", num(WF.health_sleep.asleepSec) / 3600) end,
    sslt  = function()
        local m = floor(num(WF.health_sleep.asleepSec) / 60)
        return floor(m / 60) .. ":" .. z2(m % 60)
    end,
    sslbt = function()
        if not WF.health_sleep.hasData then return "--:--" end
        return os.date("%H:%M", num(WF.health_sleep.sleepStart))
    end,
    sslwt = function()
        if not WF.health_sleep.hasData then return "--:--" end
        return os.date("%H:%M", num(WF.health_sleep.wake))
    end,
    sslrm = function() return floor(num(WF.health_sleep.remSec)   / 60) end,
    ssllm = function() return floor(num(WF.health_sleep.lightSec) / 60) end,
    ssldm = function() return floor(num(WF.health_sleep.deepSec)  / 60) end,
    sslam = function() return floor(num(WF.health_sleep.awakeSec) / 60) end,
    sslp  = function()
        local total = num(WF.health_sleep.totalSec)
        return total > 0 and floor(num(WF.health_sleep.asleepSec) / total * 100) or 0
    end,
})
-- Stress score is a CloX extension — BHT 2.0's stress endpoint (currently stubbed at 0).
def("health_stress", {
    sstr = function() return floor(num(WF.health_stress.score)) end,
})

-- ── sensors / compass ──
def("sensor_accelerometer", {
    sax = function() return num(WF.sensor_accelerometer.x) end,
    say = function() return num(WF.sensor_accelerometer.y) end,
    saz = function() return num(WF.sensor_accelerometer.z) end,
})
def("sensor_gyroscope", {
    sgx = function() return num(WF.sensor_gyroscope.x) end,
    sgy = function() return num(WF.sensor_gyroscope.y) end,
    sgz = function() return num(WF.sensor_gyroscope.z) end,
})
local function heading() return (num(WF.sensor_compass.azimuth) + 360) % 360 end
-- WM formats (screenshot-confirmed 2026-07-07): {sct} is a ROUNDED integer
-- (335, while {scr} keeps full precision), and the display combos put a
-- space between degrees and bearing: "335° NW".
def("sensor_compass", {
    scr  = function() return -heading() end,
    sct  = function() return floor(heading() + 0.5) % 360 end,
    sctd = function() return fmt("%d°", floor(heading() + 0.5) % 360) end,
    scb  = function() return bearing(heading(), BEAR_8) end,
    scbb = function() return bearing(heading(), BEAR_16) end,
    sctdb  = function() return fmt("%d° ", floor(heading() + 0.5) % 360) .. bearing(heading(), BEAR_8) end,
    sctdbb = function() return fmt("%d° ", floor(heading() + 0.5) % 360) .. bearing(heading(), BEAR_16) end,
})
def("sensor_barometer", {
    sprs = function() return num(WF.sensor_barometer.pressure) end,
})

-- ── system & device ──
def("static", {
    aos    = function() return WF.device.osType or "Android" end,
    -- WM's {aosv} is the Android RELEASE version ("15"), not the SDK int
    aosv   = function() return WF.device.osVersion or WF.device.apiLevel or 0 end,
    aman   = function() return WF.device.manufacturer or "" end,
    amodel = function() return WF.device.deviceModel or "" end,
    aname  = function() return WF.device.productName or "" end,
    awname = function() return WF.device.deviceLabel or WF.device.deviceModel or "" end,
    around = function() return boolStr(WF.device.isRound) end,
    atyre  = function() return "false" end,
    adimlo = function() return "false" end,
    alangcode = function() return WF.device.language or "" end,
    alangreg  = function() return WF.device.langRegion or "" end,
    alangfull = function() return WF.device.langFull or WF.device.language or "" end,
    areboot   = function() return WF.device.lastReboot or "" end,
    ulver     = function() return WF.device.appVersion or "" end,
})
-- {abss}/{abssl} — time since bright mode; -1 while dim (WM/UL convention).
-- Live values, so they sit in the ms bucket and read the renderer's dim state.
local DIM = require "wf_dimstate"
def("ms", {
    abss  = function(c)
        local s = DIM.brightFor(c.elapsed)
        return s and floor(s * 1000) or -1
    end,
    abssl = function(c)
        local s = DIM.brightFor(c.elapsed)
        if not s then return -1 end
        if s > 30 then return 30 end
        return floor(s * 1000) / 1000
    end,
})
-- abright tracks AOD state, which arrives on the settings push — the old
-- STATIC classification meant it never updated after load.
def("settings", {
    abright = function() return boolStr(not (WF.settings and WF.settings.aodEnabled)) end,
})
def("system", {
    avol   = function() return (WF.system or {}).volume or 0 end,
    abrt   = function() return (WF.system or {}).brightness or 0 end,
    alowpw = function() return boolStr((WF.system or {}).lowPowerMode) end,
    adark  = function() return boolStr((WF.system or {}).darkMode) end,
    d24h   = function() return boolStr((WF.system or {}).is24h) end,
})

-- location — primary coordinates + elevation (manual, IP-resolved, or the
-- open-meteo elevation API) and the place name, pushed on the "location"
-- category. names[1] is the primary city (location_name_1).
-- Formats per WM_TAGS.md: alatd/alond = absolute degrees, 1 decimal ("51.5");
-- alatdd/alondd add the hemisphere ("51.5°N" / "0.1°E").
local function absDeg1(v) return fmt("%.1f", math.abs(num(v))) end
def("location", {
    alat = function() return (WF.location or {}).latitude or 0 end,
    alon = function() return (WF.location or {}).longitude or 0 end,
    alatd  = function() return absDeg1((WF.location or {}).latitude) end,
    alond  = function() return absDeg1((WF.location or {}).longitude) end,
    alatdd = function()
        local v = num((WF.location or {}).latitude)
        return absDeg1(v) .. "°" .. (v >= 0 and "N" or "S")
    end,
    alondd = function()
        local v = num((WF.location or {}).longitude)
        return absDeg1(v) .. "°" .. (v >= 0 and "E" or "W")
    end,
    aalt = function() return (WF.location or {}).elevation or 0 end,
    aloc = function() return ((WF.location or {}).names or {})[1] or "" end,
    -- what3words addresses need the proprietary w3w API (no offline mapping);
    -- resolve to empty strings rather than the unknown-tag 0
    aw3w  = function() return "" end,
    aw3w1 = function() return "" end,
    aw3w2 = function() return "" end,
    aw3w3 = function() return "" end,
})

-- Apple-health-style activity tags with NO data source on this platform
-- (HealthHelper carries steps / heart rate / SpO2 only): exercise minutes,
-- calories, stand hours, flights climbed + their targets. Pinned to 0 so
-- faces render a sane value instead of the unknown-tag warning.
def("static", {
    hae  = function() return 0 end,
    ham  = function() return 0 end,
    has  = function() return 0 end,
    hfc  = function() return 0 end,
    htae = function() return 0 end,
    htam = function() return 0 end,
    htas = function() return 0 end,
})

-- ── memory & storage (bytes → GB strings) ──
local GB = 1073741824
local function gb1(bytes) return fmt("%.1f", num(bytes) / GB) end
local function pct1(part, total)
    total = num(total)
    if total <= 0 then return "0" end
    return fmt("%.1f", num(part) / total * 100)
end
def("storage", {
    amup  = function() return pct1(WF.storage.usedRam, WF.storage.totalRam) end,
    amfp  = function() return pct1(num(WF.storage.totalRam) - num(WF.storage.usedRam), WF.storage.totalRam) end,
    adsup = function() return pct1(WF.storage.usedStorage, WF.storage.totalStorage) end,
    adsfp = function() return pct1(num(WF.storage.totalStorage) - num(WF.storage.usedStorage), WF.storage.totalStorage) end,
    amu  = function() return gb1(WF.storage.usedRam) end,
    amuf = function() return gb1(WF.storage.usedRam) .. " GB" end,
    amt  = function() return gb1(WF.storage.totalRam) end,
    amtf = function() return gb1(WF.storage.totalRam) .. " GB" end,
    amf  = function() return gb1(num(WF.storage.totalRam) - num(WF.storage.usedRam)) end,
    amff = function() return gb1(num(WF.storage.totalRam) - num(WF.storage.usedRam)) .. " GB" end,
    adsu  = function() return gb1(WF.storage.usedStorage) end,
    adsuf = function() return gb1(WF.storage.usedStorage) .. " GB" end,
    adst  = function() return gb1(WF.storage.totalStorage) end,
    adstf = function() return gb1(WF.storage.totalStorage) .. " GB" end,
    adsf  = function() return gb1(num(WF.storage.totalStorage) - num(WF.storage.usedStorage)) end,
    adsff = function() return gb1(num(WF.storage.totalStorage) - num(WF.storage.usedStorage)) .. " GB" end,
})

-- ── network / connectivity ──
def("network", {
    nc   = function() return boolStr(WF.network.wifiConnected or WF.network.dataConnected) end,
    ncc  = function() return boolStr(WF.network.dataConnected) end,
    pwc  = function() return boolStr(WF.network.wifiConnected) end,
    -- UL: pws is a 0..1 FRACTION (wifiSignalStrength/100), NOT the percent
    -- WM_TAGS.md claims — faces compare `{pws} >= 0.86` for signal bars.
    pws  = function() return num(WF.network.wifiSignal) / 4 end,
    -- cellular signal, same 0..1 fraction convention (dataSignal is 0..4 bars)
    ncs  = function() return num(WF.network.dataSignal) / 4 end,
    nwip = function() return WF.network.wifiIp or "" end,
})
def("connectivity", {
    nbc  = function() return boolStr((WF.connectivity or {}).bluetoothEnabled) end,
    ngc  = function() return boolStr((WF.connectivity or {}).gpsEnabled) end,
    abtc = function() return boolStr((WF.connectivity or {}).bluetoothEnabled) end,
})

-- ── notifications ──
def("notification", {
    cnc = function() return (WF.notification or {}).count or 0 end,
})

-- ── alarm ──
def("alarm", {
    aalo = function() return (WF.alarm or {}).hasAlarm and 1 or 0 end,
    aalh23z = function()
        local al = WF.alarm or {}
        if al.hasAlarm and al.alarmTime and al.alarmTime:find(":") then
            local ah = al.alarmTime:match("(%d+):")
            return z2(tonumber(ah) or 0)
        end
        return "--"
    end,
    aalmz = function()
        local al = WF.alarm or {}
        if al.hasAlarm and al.alarmTime and al.alarmTime:find(":") then
            local am = al.alarmTime:match(":(%d+)")
            return z2(tonumber(am) or 0)
        end
        return "--"
    end,
})

-- ── calendar (aggregate; per-event fields are the c<N><field> pattern) ──
def("calendar", {
    cex = function() return boolStr((WF.calendar or {}).hasEvents) end,
})

-- ── theme accents (published by the renderer via engine:publish on theme
--    switch; the fns supply the pre-theme default) ──
def("theme", {
    ucolor   = function() return "ffffff" end,
    ucolor2  = function() return "ffffff" end,
    ucolor3  = function() return "ffffff" end,
    ucolor_b = function() return "ffffff" end,
})

-- ── time zones (time-of-day moves with the clock; metadata with the push) ──
local function tzOf(i) return (WF.timezones or {})["tz" .. i] end
for i = 1, 3 do
    local p = "tz" .. i
    def("timezones", {
        [p .. "l"]   = function() local tz = tzOf(i); return tz and (tz.locationShort or "") or "" end,
        [p .. "ll"]  = function() local tz = tzOf(i); return tz and (tz.locationLong or "") or "" end,
        [p .. "o"]   = function() local tz = tzOf(i); return tz and (tz.utcOffset or "") or "" end,
        [p .. "om"]  = function() local tz = tzOf(i); return tz and (tz.utcOffsetMinutes or 0) or 0 end,
        [p .. "dst"] = function() local tz = tzOf(i); return tz and boolStr(tz.dstActive) or "false" end,
    })
    -- tz1 falls back to device local time when no zone data has been pushed
    def({ "minute", "timezones" }, {
        [p .. "t"] = function(c)
            local tz = tzOf(i)
            if not tz and i == 1 then return z2(c.t.hour) .. ":" .. z2(c.t.min) end
            if not tz then return "" end
            local zt = os.date("!*t", c:epoch() + num(tz.utcOffsetMinutes) * 60)
            return z2(zt.hour) .. ":" .. z2(zt.min)
        end,
        [p .. "rh"] = function(c)
            local tz = tzOf(i)
            if not tz and i == 1 then
                return ((c.t.hour % 12) + c.t.min / 60 + c.t.sec / 3600) / 12 * 360
            end
            if not tz then return 0 end
            local zt = os.date("!*t", c:epoch() + num(tz.utcOffsetMinutes) * 60)
            return ((zt.hour % 12) + zt.min / 60) / 12 * 360
        end,
        [p .. "rh24"] = function(c)
            local tz = tzOf(i)
            if not tz and i == 1 then return (c.t.hour + c.t.min / 60) / 24 * 360 end
            if not tz then return 0 end
            local zt = os.date("!*t", c:epoch() + num(tz.utcOffsetMinutes) * 60)
            return (zt.hour + zt.min / 60) / 24 * 360
        end,
        [p .. "rm"] = function(c)
            local tz = tzOf(i)
            if not tz and i == 1 then return (c.t.min + c.t.sec / 60) / 60 * 360 end
            if not tz then return 0 end
            local zt = os.date("!*t", c:epoch() + num(tz.utcOffsetMinutes) * 60)
            return zt.min / 60 * 360
        end,
    })
end

-- ── parametric families ───────────────────────────────────────────────────────
-- Matched at ACTIVATION (import time — patterns are fine here). Each factory
-- parses the name once and returns a def whose compute closure captures the
-- parsed parameters, so the runtime never re-parses anything.

local FAMILIES = {

    -- {c_FROM_TO_DURATION_MODE[_DELAY]} — animation counters off elapsed time
    { pat = "^c_", make = function(name)
        local from, to, dur, mode, delay =
            name:match("^c_([%-%d%.]+)_([%-%d%.]+)_([%d%.]+)_(%a+)_?([%d%.]*)$")
        if not from then return nil end
        from, to, dur = tonumber(from), tonumber(to), tonumber(dur)
        delay = tonumber(delay) or 0
        if not (from and to and dur and dur > 0) then return nil end
        return { ev = "ms", fn = function(c)
            local e = c.elapsed - delay
            if e < 0 then return from end
            local phase = e / dur
            local frac
            if mode == "rp" then
                frac = phase % 1
            elseif mode == "rv" then
                local p = phase % 2
                frac = p <= 1 and p or (2 - p)
            else                       -- "st" and default: run once, stop
                frac = math.min(phase, 1)
            end
            return from + (to - from) * frac
        end }
    end },

    -- heart-rate history {shr_1}..{shr_9} (1 = previous, history[10] = newest)
    { pat = "^shr_%d+$", make = function(name)
        local n = tonumber(name:match("^shr_(%d+)$"))
        if not n or n < 1 or n > 9 then return nil end
        return { ev = "health_heartRate", fn = function()
            local entry = (WF.health_heartRate.history or {})[10 - n]
            return entry and floor(num(entry.bpm)) or 0
        end }
    end },

    -- daily forecast {wf<N>d<field>} — N 0..5, daily[1] = today
    { pat = "^wf%d+d", make = function(name)
        local n, field = name:match("^wf(%d+)d(%a+)$")
        n = tonumber(n)
        if not n or not field then return nil end
        local PICK = {
            t  = function(d) return floor(num(d.avgTemp)) end,
            th = function(d) return floor(num(d.high)) end,
            tl = function(d) return floor(num(d.low)) end,
            ct = function(d) return d.conditionText or "" end,
            -- OWM-style icon code like {wci}: zero-padded numeral + suffix
            -- (WM emits "03d"/"10d", never the bare int; days are always "d")
            ci = function(d)
                local n = num(d.conditionIcon)
                if n <= 0 then return "" end
                return fmt("%02d", n) .. "d"
            end,
        }
        local pick = PICK[field]
        if not pick then return nil end
        return { ev = "weather", fn = function()
            local d = (WF.weather.daily or {})[n + 1]
            return d and pick(d) or ""
        end }
    end },

    -- hourly forecast {wf<N>h<field>} — N 1..12, hourly[1] = next hour
    { pat = "^wf%d+h", make = function(name)
        local n, field = name:match("^wf(%d+)h(%a+)$")
        n = tonumber(n)
        if not n or not field then return nil end
        local PICK = {
            t  = function(h) return floor(num(h.temp)) end,
            h  = function(h) return floor(num(h.hourOfDay)) end,
            ct = function(h) return h.conditionText or "" end,
            -- zero-padded icon code; ALWAYS "d" — faces only ever compare day
            -- codes (same finding as {wci}, 2026-07-10)
            ci = function(h)
                local n = num(h.conditionIcon)
                if n <= 0 then return "" end
                return fmt("%02d", n) .. "d"
            end,
        }
        local pick = PICK[field]
        if not pick then return nil end
        return { ev = "weather", fn = function()
            local h = (WF.weather.hourly or {})[n]
            return h and pick(h) or ""
        end }
    end },

    -- notifications {cn<N>a|t|c} — app name / title / content
    { pat = "^cn%d+[atc]$", make = function(name)
        local n, field = name:match("^cn(%d+)([atc])$")
        n = tonumber(n)
        if not n then return nil end
        local KEY = { a = "appName", t = "title", c = "content" }
        local key = KEY[field]
        return { ev = "notification", fn = function()
            local it = ((WF.notification or {}).list or {})[n]
            return it and (it[key] or "") or ""
        end }
    end },

    -- calendar events {c<N><field>} — c1ex / c1t / c1b / … / c10cal
    { pat = "^c%d+%a", make = function(name)
        local n, field = name:match("^c(%d+)(%a+)$")
        n = tonumber(n)
        if not n or n < 1 or n > 10 then return nil end
        -- br/bp/er/ep: event begin/end as a 12h-dial rotation (0-360) and
        -- percent of day (0-100) — undocumented in WM_TAGS.md; semantics from
        -- the UL renderer's WatchSkinUtils comments (UL itself never fills them).
        local function timeRot(ms)
            if not ms or ms == 0 then return 0 end
            local t = os.date("*t", floor(ms / 1000))
            return ((t.hour % 12) + t.min / 60) / 12 * 360
        end
        local function timePct(ms)
            if not ms or ms == 0 then return 0 end
            local t = os.date("*t", floor(ms / 1000))
            return (t.hour * 60 + t.min) / 1440 * 100
        end
        local PICK = {
            ex  = function(ev) return boolStr(ev.exists) end,
            t   = function(ev) return ev.title or "" end,
            b   = function(ev) return ev.beginTime or "" end,
            e   = function(ev) return ev.endTime or "" end,
            bd  = function(ev) return ev.beginDate or "" end,
            ed  = function(ev) return ev.endDate or "" end,
            l   = function(ev) return ev.location or "" end,
            c   = function(ev) return ev.color or "ffffff" end,
            ad  = function(ev) return boolStr(ev.allDay) end,
            cal = function(ev) return ev.calendar or "" end,
            i   = function(ev) return ev.id or "" end,
            br  = function(ev) return timeRot(ev.beginTimeMs) end,
            bp  = function(ev) return timePct(ev.beginTimeMs) end,
            er  = function(ev) return timeRot(ev.endTimeMs) end,
            ep  = function(ev) return timePct(ev.endTimeMs) end,
        }
        local pick = PICK[field]
        if not pick then return nil end
        return { ev = "calendar", fn = function()
            local ev = ((WF.calendar or {}).events or {})[n] or {}
            return pick(ev)
        end }
    end },

    -- future weekday names {ddw_1}..{ddww_6} (N days ahead)
    { pat = "^ddww?[12]?_%d+$", make = function(name)
        local kind, n = name:match("^(ddw[w12]?)_(%d+)$")
        n = tonumber(n)
        if not kind or not n or n < 1 or n > 6 then return nil end
        local LIST = { ddw = DOW_3, ddww = DOW_FULL, ddw1 = DOW_1, ddw2 = DOW_2 }
        local list = LIST[kind]
        if not list then return nil end
        return { ev = "day", fn = function(c)
            local ft = os.date("*t", c:epoch() + n * 86400)
            return list[ft.wday]
        end }
    end },
}

-- ── public API ────────────────────────────────────────────────────────────────

-- lookup(name) → def { ev, fn } or nil for unknown tags (engine decides the
-- fallback). Pattern families allocate one def per distinct name; the engine
-- caches the result on its WFTag object so this runs once per name per face.
function WFTagDefs.lookup(name)
    local d = D[name]
    if d then return d end
    for _, fam in ipairs(FAMILIES) do
        if name:find(fam.pat) then
            local made = fam.make(name)
            if made then return made end
        end
    end
    return nil
end

return WFTagDefs
