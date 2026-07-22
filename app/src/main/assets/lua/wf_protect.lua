-- wf_protect.lua — decode WatchMaker "protected" faces (protection="y").
--
-- Protected .watch packages ship a stub watch.xml plus the real definition in
-- watch.pxml, and images as .ppng / .pjpg. Both use light, fixed obfuscation:
--
--   watch.pxml     : standard Base64 of the real watch.xml, but with two alphabet
--                    entries swapped — 'D'↔'g' (values 3↔32) and '4'↔'L' (11↔56).
--   .ppng / .pjpg  : the real PNG / JPEG bytes XOR'd with the repeating 5-byte
--                    key "SWHn-" (0x53 0x57 0x48 0x6E 0x2D).
--
-- Matches the reference wftools WatchmakerExtractor.kt. (There is no crypto spec
-- in the format docs; reverse-engineered from sample faces — see the CloX
-- watchface_renderer_implementation.md "Protected asset streams" note.)

local M = {}

local fu = cc.FileUtils:getInstance()

-- ── Base64 with the swapped alphabet ─────────────────────────────────────────

local STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
local DEC = {}
for i = 1, #STD do DEC[STD:sub(i, i)] = i - 1 end
DEC['D'], DEC['g'] = 32, 3      -- swap: 'D'→32, 'g'→3
DEC['4'], DEC['L'] = 11, 56     -- swap: '4'→11, 'L'→56

-- Decode the Base64 (swapped) text of a watch.pxml into the real XML string.
-- Pure arithmetic (no bit ops) — the payload is small (a few KB).
function M.decodePxml(s)
    if not s or #s == 0 then return "" end
    local out  = {}
    local bits, nbits = 0, 0
    for i = 1, #s do
        local v = DEC[s:sub(i, i)]
        if s:sub(i, i) == '=' then break end
        if v then
            bits  = bits * 64 + v
            nbits = nbits + 6
            if nbits >= 8 then
                nbits = nbits - 8
                local div = 2 ^ nbits
                out[#out + 1] = string.char(math.floor(bits / div) % 256)
                bits = bits % div
            end
        end
    end
    return table.concat(out)
end

-- ── XOR helper (portable across Lua flavours) ─────────────────────────────────

local bxor
do
    if rawget(_G, "bit32") then bxor = bit32.bxor
    elseif rawget(_G, "bit") then bxor = bit.bxor              -- LuaJIT
    else
        local ok, f = pcall(load, "return function(a,b) return a ~ b end")  -- 5.3+
        if ok and f then bxor = f() end
    end
    if not bxor then
        bxor = function(a, b)   -- arithmetic fallback
            local r, p = 0, 1
            while a > 0 or b > 0 do
                if (a % 2) ~= (b % 2) then r = r + p end
                a = math.floor(a / 2); b = math.floor(b / 2); p = p * 2
            end
            return r
        end
    end
end

local PPNG_KEY = "SWHn-"
local unpack = table.unpack or unpack

-- XOR-decode a byte string with the repeating key. Chunked so string.char never
-- receives too many arguments at once.
function M.xorBytes(data, key)
    key = key or PPNG_KEY
    local klen = #key
    local tabs = {}                        -- per key-position 0..255 lookup
    for i = 1, klen do
        local k, t = key:byte(i), {}
        for b = 0, 255 do t[b] = bxor(b, k) end
        tabs[i] = t
    end
    local out, ki, n, pos = {}, 0, #data, 1
    local CHUNK = 256
    while pos <= n do
        local e = pos + CHUNK - 1; if e > n then e = n end
        local b = { data:byte(pos, e) }
        for j = 1, #b do
            b[j] = tabs[(ki % klen) + 1][b[j]]
            ki = ki + 1
        end
        out[#out + 1] = string.char(unpack(b))
        pos = e + 1
    end
    return table.concat(out)
end

-- ── public file helpers ───────────────────────────────────────────────────────

-- True if the extracted package at baseDir is a protected face.
function M.isProtected(baseDir)
    return fu:isFileExist(baseDir .. "watch.pxml")
end

-- Read + decode watch.pxml → real XML string (or "" on failure).
function M.readPxml(baseDir)
    local enc = fu:getStringFromFile(baseDir .. "watch.pxml")
    if not enc or #enc == 0 then return "" end
    return M.decodePxml(enc)
end

-- XOR-decode a protected image (.ppng / .pjpg) into a real image (.png / .jpg)
-- on disk. Returns true on success. No-op (true) if the decoded file already
-- exists (cached from a previous load). Extension-agnostic — the same repeating
-- key covers both PNG and JPEG payloads.
function M.ensureDecoded(srcPath, dstPath)
    if fu:isFileExist(dstPath) then return true end
    local data = fu:getStringFromFile(srcPath)
    if not data or #data == 0 then return false end
    return fu:writeStringToFile(M.xorBytes(data, PPNG_KEY), dstPath)
end

-- Back-compat alias for the .ppng-only helper.
function M.ensurePng(ppngPath, pngPath)
    return M.ensureDecoded(ppngPath, pngPath)
end

return M
