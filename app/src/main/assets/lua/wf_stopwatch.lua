-- wf_stopwatch.lua — shared stopwatch state behind the {sw*} tags and the
-- sw_start_stop / sw_reset tap actions (UL WatchSkin semantics: one global
-- stopwatch per process, state survives face switches).
--
-- Time source is the engine clock's `elapsed` (seconds since face load,
-- monotonic within a face). startAt is rebased on every start so pausing and
-- resuming accumulates correctly even across face switches (a new face's
-- elapsed restarts at 0, but startAt is recomputed from stoppedAt on the
-- next start; a RUNNING stopwatch carried across a face switch is rebased by
-- wf_render on load).

local SW = { running = false, startAt = 0, stoppedAt = 0 }

local ok_json, json = pcall(require, "cjson")
if not ok_json then json = nil end

-- ── persistence (survives process death; per-transition writes, no polling) ──
-- The file stores accumulated seconds + a wall-clock epoch. A RUNNING
-- stopwatch needs no further writes: its current value is always derivable as
-- accum + (now_wall - epoch), so state saved at START stays correct no matter
-- when the process dies (UL semantics: the stopwatch runs in real time even
-- while the app is dead). A STOP or RESET rewrites/removes the file.
local function stateFile()
    return cc.FileUtils:getInstance():getWritablePath() .. "wf_vars/__stopwatch.json"
end

local function persist(now)
    if not json then return end
    local fu = cc.FileUtils:getInstance()
    local accum = SW.running and (now - SW.startAt) or SW.stoppedAt
    if accum < 0 then accum = 0 end
    if not SW.running and accum == 0 then                 -- reset state: nothing to keep
        if fu:isFileExist(stateFile()) then fu:removeFile(stateFile()) end
        return
    end
    local dir = cc.FileUtils:getInstance():getWritablePath() .. "wf_vars/"
    if not fu:isDirectoryExist(dir) then fu:createDirectories(dir) end
    local ok, enc = pcall(json.encode,
        { running = SW.running, accum = accum, epoch = os.time() })
    if ok and type(enc) == "string" then fu:writeStringToFile(enc, stateFile()) end
end

local restoredThisProcess = false
-- Called once per process, on the FIRST face load (engine elapsed ≈ `now`).
-- Later loads go through rebase() instead — in-memory state carries over.
function SW.restoreOnce(now)
    if restoredThisProcess then return end
    restoredThisProcess = true
    if not json then return end
    local fu = cc.FileUtils:getInstance()
    if not fu:isFileExist(stateFile()) then return end
    local ok, data = pcall(json.decode, fu:getStringFromFile(stateFile()) or "")
    if not ok or type(data) ~= "table" then return end
    local accum = tonumber(data.accum) or 0
    if data.running then
        accum = accum + math.max(0, os.time() - (tonumber(data.epoch) or os.time()))
        SW.running = true
        SW.startAt = now - accum
    else
        SW.running = false
        SW.stoppedAt = accum
    end
    print(string.format("[WFStopwatch] restored: running=%s accum=%.1fs",
        tostring(SW.running), accum))
end

-- Elapsed stopwatch time in MILLISECONDS at engine time `now` (seconds).
function SW.durationMs(now)
    local d = SW.running and (now - SW.startAt) or SW.stoppedAt
    if d < 0 then d = 0 end
    return d * 1000
end

function SW.startStop(now)
    if SW.running then
        SW.stoppedAt = now - SW.startAt
        SW.running = false
    else
        SW.startAt = now - SW.stoppedAt
        SW.running = true
    end
    persist(now)
end

function SW.reset()
    SW.running = false
    SW.startAt, SW.stoppedAt = 0, 0
    persist(0)
end

-- A face switch resets the engine clock's elapsed to 0; a running stopwatch
-- must keep its accumulated time. Called by wf_render on load with the OLD
-- clock's final elapsed: startAt becomes negative by the accumulated amount,
-- so duration keeps counting seamlessly on the new clock. A stopped watch
-- needs nothing (stoppedAt already holds the accumulated time).
function SW.rebase(oldNow)
    if SW.running then
        SW.startAt = -(oldNow - SW.startAt)
    end
end

return SW
