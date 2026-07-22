-- wf_expr.lua — WFExpr: import-time compiler for Watchmaker attribute strings.
--
-- Every dynamic attribute value ("{drss}", "{dh23z}:{dmz}", "{drh}+90",
-- "math.floor({ssc}/100)", "{var_mode}==1 and 0 or 100") is compiled ONCE at
-- face load into a plain Lua function. Tags become table lookups into the
-- engine's live value table V (V.drss / V["c_0_100_2_rp"]), literal text
-- becomes string constants, and the whole thing is load()ed against the face
-- sandbox — so bare identifiers (global-script variables like var_mode) and
-- math.* / string.* resolve naturally.
--
-- THIS FILE IS THE ONLY PLACE ATTRIBUTE STRINGS ARE PATTERN-MATCHED. The
-- functions it produces do zero gsub/find/match at runtime — the render loop
-- only ever calls compiled chunks and indexes tables. Import is allowed to be
-- slow; the running face is not.
--
-- compile(raw, kind, engine) → descriptor:
--   { const = true,  value = <final value> }                     -- static attr
--   { const = false, fn = f(V)→value, deps = {tag, ...} }        -- dynamic attr
--
-- kind selects the legacy evaluation semantics (matching old wf_tags):
--   "num"  — evalNumber: value must end up numeric; expression heuristics are
--            permissive; failures fall back to the caller's default.
--   "text" — evalText: strict expression heuristics (a plain label like
--            "2024-2026" stays literal); failed expressions fall back to the
--            substituted template string.
--   "sub"  — substitution only, never evaluated as code (colors, paths).

local WFExpr = {}

-- Set by the renderer per face load: fn(context, err). Compile failures fall
-- back to templates/literals so the face still renders, but they must fail
-- LOUDLY too — a silently mis-rendered attribute is as bad as a black layer.
WFExpr.onError = nil

-- ── XML entity decode (attributes are stored XML-escaped) ─────────────────────

function WFExpr.unescape(str)
    if not str then return str end
    return (str:gsub("&quot;", '"'):gsub("&apos;", "'")
               :gsub("&lt;", "<"):gsub("&gt;", ">"):gsub("&amp;", "&"))
end

-- ── display formatting for numbers landing in TEXT ────────────────────────────
-- WM renders numeric tag values with ~6 significant digits and no trailing
-- ".0" on whole numbers (drh → "263", drss → "238.062"); Lua's tostring gives
-- "263.0" and full-precision tails ("107.72797190389"). Applied wherever a
-- tag value becomes display text — never on the numeric attribute path
-- (rotation/pct_complete keep full precision).
function WFExpr.fmtnum(v)
    if type(v) ~= "number" then
        return v == nil and "" or tostring(v)
    end
    if v % 1 == 0 and v >= -9007199254740992 and v <= 9007199254740992 then
        return string.format("%.0f", v)
    end
    return string.format("%.6g", v)
end

-- ── expression detection (ported verbatim from the old wf_tags heuristics) ────
-- Applied to the raw string with every {tag} replaced by "0", which mirrors
-- the old substitute-then-sniff pipeline closely enough: tags resolve to plain
-- values, so any operator/call syntax around them is what makes code code.

local function looksLikeExprNum(s)
    return s:find("string%.") or s:find("math%.") or s:find("tonumber")
        or s:find("tostring") or s:find("[%(%)]")
        or s:find("[%+%*/%%]") or s:find("%d%s*%-") or s:find("%.%.")
        or s:find("==") or s:find("~=") or s:find("<") or s:find(">")
        or s:find("%sand%s") or s:find("%sor%s")
end

local function looksLikeExprText(s)
    if s:find("string%.") or s:find("math%.") or s:find("tonumber")
        or s:find("tostring") or s:find("tween")
        or s:find("[%a_][%w_]*%s*%(") then
        return true
    end
    -- Lua ternaries ("{dh}>6 and 'eve' or 'day'") are everywhere in real
    -- faces; the old renderer missed them. Require a comparison AND and/or so
    -- plain labels ("fish and chips", "20-5") stay literal — and even a false
    -- positive is safe, because a failed expression falls back to the
    -- substituted template string at runtime.
    if (s:find("==") or s:find("~=") or s:find("[<>]"))
        and (s:find("%sand%s") or s:find("%sor%s")) then
        return true
    end
    -- Comparison-less boolean ternaries too ("{swr} and 'STOP' or 'START'"):
    -- needing BOTH and+or keeps plain labels literal, and a false positive
    -- still falls back to the template.
    if s:find("%sand%s") and s:find("%sor%s") then
        return true
    end
    return false
end

-- ── codegen helpers ───────────────────────────────────────────────────────────

-- A tag reference as Lua source: identifier-shaped names use sugar indexing,
-- anything else (c_0_100_2_rp, tweens.spin, shr_1) uses explicit ["..."].
local function tagRef(name)
    if name:match("^[%a_][%w_]*$") then return "V." .. name end
    return string.format("V[%q]", name)
end

-- Split raw into an ordered list of segments: {lit="text"} | {tag="name"},
-- collecting unique dep names in order of first appearance.
local function segment(raw)
    local segs, deps, seen = {}, {}, {}
    local pos = 1
    while true do
        local a, b, name = raw:find("{([^{}]-)}", pos)
        if not a then break end
        if a > pos then segs[#segs + 1] = { lit = raw:sub(pos, a - 1) } end
        segs[#segs + 1] = { tag = name }
        if not seen[name] then seen[name] = true; deps[#deps + 1] = name end
        pos = b + 1
    end
    if pos <= #raw then segs[#segs + 1] = { lit = raw:sub(pos) } end
    return segs, deps
end

-- Template source: concat every segment ("" .. V.dh23z .. ":" .. V.dmz).
-- A lone tag compiles to a direct lookup with no concat (keeps numbers numeric
-- for "num" attributes like rotation="{drss}").
local function templateSrc(segs)
    if #segs == 1 and segs[1].tag then return tagRef(segs[1].tag) end
    local parts = {}
    for _, s in ipairs(segs) do
        -- __wn = WFExpr.fmtnum: numbers concatenated into text render
        -- WM-style ("258", not "258.0"/full-precision tails)
        parts[#parts + 1] = s.lit and string.format("%q", s.lit)
            or ("__wn(" .. tagRef(s.tag) .. ")")
    end
    return table.concat(parts, " .. ")
end

-- Expression source: the raw text with each {tag} spliced in as a V lookup.
-- "{drh}+90" → "V.drh+90". Improves on the old substitute-text-then-load
-- pipeline for string tags (they arrive as real string values, not bare words).
--
-- Quote-aware: a tag INSIDE a string literal — string.match("{wsr}", "(%d+):"),
-- '{da}' == 'AM' — must splice as a concat, not an identifier, or the literal
-- would contain the text "V.wsr". Inside quote q the tag emits  q .. V.x .. q
-- (close, concat, reopen); `..` binds tighter than comparisons, so '{da}'=='AM'
-- → '' .. V.da .. '' == 'AM' parses as intended.
--
-- UNQUOTED tags splice through __wb (boolean coercion): UL substitutes a
-- boolean tag as the bare words true/false, which Lua parses as boolean
-- LITERALS — so faces write "{swr} and 'STOP' or 'START'". Our tag values
-- are the strings "true"/"false" (both truthy!), so the unquoted splice
-- coerces exactly those two strings back to booleans and passes everything
-- else through untouched. Quoted splices stay strings ('{swr}'=='true'
-- keeps working).
local function exprSrc(raw)
    local out = {}
    local i, n = 1, #raw
    local quote = nil
    while i <= n do
        local c = raw:sub(i, i)
        if not quote and c == "{" then
            local _, e, name = raw:find("^{([^{}]-)}", i)
            if e then
                out[#out + 1] = "__wb(" .. tagRef(name) .. ")"
                i = e + 1
            else
                out[#out + 1] = c
                i = i + 1
            end
        elseif quote and c == "{" then
            local _, e, name = raw:find("^{([^{}]-)}", i)
            if e then
                out[#out + 1] = quote .. " .. " .. tagRef(name) .. " .. " .. quote
                i = e + 1
            else
                out[#out + 1] = c
                i = i + 1
            end
        elseif not quote and c == "\194" and raw:sub(i + 1, i + 1) == "\160" then
            -- U+00A0 no-break space, outside strings: face editors sneak
            -- these into expressions and Lua's lexer rejects the raw byte
            -- ("unexpected symbol near '<\194>'") — normalise to a space.
            -- Inside string literals it stays verbatim (legal, and possibly
            -- intentional display text).
            out[#out + 1] = " "
            i = i + 2
        else
            out[#out + 1] = c
            if quote then
                if c == "\\" and i < n then
                    out[#out + 1] = raw:sub(i + 1, i + 1)   -- escaped char verbatim
                    i = i + 1
                elseif c == quote then
                    quote = nil
                end
            elseif c == "'" or c == '"' then
                quote = c
            end
            i = i + 1
        end
    end
    return table.concat(out)
end

-- Bare identifiers attributes can reference live. By WatchMaker convention
-- these are ONLY script variables (var_* — always that prefix, even when the
-- script hasn't WRITTEN them yet: activation gives a neutral 0 and the first
-- write publishes into the same tag) and dotted tween references
-- (opacity="tweens.screen1"). Catalogue tags appear in attributes only in
-- {braces} — matching them bare (knowsName) misclassified literal text
-- containing tag-shaped words ("map bruj") as expressions and fired loud
-- compile errors.
local function identDeps(s, engine, into, seen)
    if not engine then return into end
    -- dotted tween references first — the plain ident scan below splits at
    -- the dot and would see only "tweens"/"screen1". The engine registers
    -- the full dotted name (definePrefix "tweens.").
    for id in s:gmatch("%f[%w_]tweens%.[%a_][%w_]*") do
        if not seen[id] then
            seen[id] = true
            into[#into + 1] = id
        end
    end
    for id in s:gmatch("%f[%w_]var_[%w_]*") do
        if not seen[id] then
            seen[id] = true
            into[#into + 1] = id
        end
    end
    return into
end

-- Compile "return <src>" in the face sandbox; the resulting chunk takes V as
-- its single argument. Returns fn or nil+err.
local function ensureHelpers(sandbox)
    if sandbox._wbInstalled then return end
    sandbox._wbInstalled = true
    -- boolean coercion for UNQUOTED tag splices (see exprSrc)
    sandbox:expose("__wb", function(v)
        if v == "true" then return true
        elseif v == "false" then return false end
        return v
    end)
    -- WM-style number display for template concats (see templateSrc)
    sandbox:expose("__wn", WFExpr.fmtnum)
    -- first-of-value-list truncation for the comma-list retry (see compileChunk)
    sandbox:expose("__wsel", select)
end

local function compileChunk(sandbox, src, label, quiet)
    ensureHelpers(sandbox)
    -- The closing paren goes on its OWN line: faces write trailing `--`
    -- comments in expressions ("GetCalX(18)-tweens.anim0--{dd}") and UL's
    -- loadstring tolerates them because the comment ends at the line break.
    -- On one line the comment would swallow our `)` → "')' expected".
    local fn, err = sandbox:compile("local V = ... return (" .. src .. "\n)", label)
    if not fn then
        -- Comma-separated multi-value attributes exist in the wild
        -- (cond_value="{nc}, {pws} >= 0.86 and 1 or …") — UL's bare
        -- `return <src>` compiles the value list and its single assignment
        -- keeps the FIRST value. The paren form can't parse `(a, b)`; retry
        -- inside a select() arg list, whose outer parens truncate to the
        -- first value identically. An assignment retry ("local __a = <src>")
        -- would ALSO accept statement sequences — "{dd} {dnnnn}" splices to
        -- "__wb(V.dd) __wb(V.dnnnn)", which parses as an assignment plus a
        -- discarded call statement and rendered just the first tag; inside
        -- an arg list it stays the syntax error it should be.
        local fn2 = sandbox:compile("local V = ... return (__wsel(1, " .. src .. "\n))", label)
        if fn2 then return fn2 end
        -- `quiet` marks SPECULATIVE compiles (every tagged text attr tries an
        -- expression form; prose like "Steps {sc}" legitimately fails)
        if not quiet then
            print("[WFExpr] compile failed (" .. tostring(label) .. "): " .. tostring(err)
                  .. " src=" .. src:sub(1, 120))
            if WFExpr.onError then
                WFExpr.onError(tostring(label), tostring(err) .. "\nsrc: " .. src:sub(1, 200))
            end
        end
    end
    return fn, err
end

-- ── public API ────────────────────────────────────────────────────────────────

-- engine supplies: engine:activate(tagname) → registers the tag and returns
-- its current value table entry (unknown tags become static "").
-- sandbox is the face's WFSandbox.
--
-- Returns the descriptor documented in the header.
function WFExpr.compile(raw, kind, engine, sandbox)
    if raw == nil then return { const = true, value = nil } end
    if type(raw) == "number" then return { const = true, value = raw } end
    local s = WFExpr.unescape(raw)

    local segs, deps = segment(s)

    -- ── no {tags}: constant UNLESS the expression reads live bare
    --    identifiers (script variables, tags without braces) ──
    if #deps == 0 then
        if kind == "num" then
            local n = tonumber(s)
            if n then return { const = true, value = n } end
            -- A known bare identifier IS an expression even without operator
            -- syntax: real faces write opacity="var_opacidad2" (just the
            -- variable) and expect it to track the script's writes.
            local ideps = identDeps(s, engine, {}, {})
            if #ideps > 0 or looksLikeExprNum(s) then
                local fn = compileChunk(sandbox, s, "=wf_attr_const")
                if fn and #ideps > 0 then
                    for _, name in ipairs(ideps) do engine:activate(name) end
                    return { const = false, fn = fn, deps = ideps }
                end
                if fn then
                    local ok, res = pcall(fn, engine and engine.V or {})
                    if ok and tonumber(res) then return { const = true, value = tonumber(res) } end
                end
            end
            return { const = true, value = nil }   -- caller applies its default
        elseif kind == "text" then
            -- A known bare identifier IS an expression here too: faces write
            -- text="var_s_date" (just the script variable) and expect the
            -- VALUE, not the name. Prose labels stay safe: multi-word text is
            -- not a valid Lua expression, so compileChunk fails → literal.
            local ideps = identDeps(s, engine, {}, {})
            if #ideps > 0 or looksLikeExprText(s) then
                local fn = compileChunk(sandbox, s, "=wf_attr_const")
                if fn and #ideps > 0 then
                    for _, name in ipairs(ideps) do engine:activate(name) end
                    -- runtime failure falls back to the literal text, like
                    -- the tagged path falls back to its template
                    local lit = s
                    return { const = false, deps = ideps, fn = function(V)
                        local ok, res = pcall(fn, V)
                        if ok and res ~= nil then return tostring(res) end
                        return lit
                    end }
                end
                if fn then
                    local ok, res = pcall(fn, engine and engine.V or {})
                    if ok and res ~= nil then return { const = true, value = tostring(res) } end
                end
            end
            return { const = true, value = s }
        else -- "sub"
            -- Colors/paths are usually plain literals, but UL evaluates ANY
            -- attribute referencing script variables as code — faces write
            -- color="var_col_time[var_i_t]" for tap-to-cycle color schemes.
            -- A known bare identifier makes it an expression; runtime failure
            -- falls back to the literal string, like the text path.
            local ideps = identDeps(s, engine, {}, {})
            if #ideps > 0 then
                local fn = compileChunk(sandbox, s, "=wf_attr_sub_const")
                if fn then
                    for _, name in ipairs(ideps) do engine:activate(name) end
                    local lit = s
                    return { const = false, deps = ideps, fn = function(V)
                        local ok, res = pcall(fn, V)
                        if ok and res ~= nil then return tostring(res) end
                        return lit
                    end }
                end
            end
            return { const = true, value = s }
        end
    end

    -- ── tagged: activate every dep so the engine owns a live value for it ──
    for _, name in ipairs(deps) do engine:activate(name) end

    -- Expression-ness decided at compile time on the placeholder-substituted
    -- text (each tag stands in as a plain value "0"). Expression forms also
    -- pick up bare-identifier deps ("{drss}+var_off" must re-fire when the
    -- script writes var_off, not only on ms).
    local probe = s:gsub("{[^{}]-}", "0")
    local seen = {}
    for _, name in ipairs(deps) do seen[name] = true end

    local fn
    if kind == "sub" then
        local tpl = compileChunk(sandbox, templateSrc(segs), "=wf_attr_sub")
        -- Colors are frequently tagged TERNARIES — UL evaluates any attribute
        -- as code: color="({ddw0} == 0 or {ddw0} == 6) and 'FF0000' or
        -- '{ucolor}'" (weekend highlight). The strict text heuristic keeps
        -- plain paths/colors on the template; runtime failure falls back to
        -- the substituted template like the text path.
        if looksLikeExprText(probe) then
            local ex = compileChunk(sandbox, exprSrc(s), "=wf_attr_sub_expr")
            if ex then
                identDeps(probe, engine, deps, seen)
                for _, name in ipairs(deps) do engine:activate(name) end
            end
            if ex and tpl then
                fn = function(V)
                    local ok, res = pcall(ex, V)
                    if ok and res ~= nil then return tostring(res) end
                    return tpl(V)
                end
            else
                fn = ex or tpl
            end
        else
            fn = tpl
        end
    elseif kind == "num" then
        if looksLikeExprNum(probe) then
            fn = compileChunk(sandbox, exprSrc(s), "=wf_attr_num")
            if fn then
                identDeps(probe, engine, deps, seen)
                for _, name in ipairs(deps) do engine:activate(name) end
            end
        end
        fn = fn or compileChunk(sandbox, templateSrc(segs), "=wf_attr_num")
    else -- "text"
        local tpl = compileChunk(sandbox, templateSrc(segs), "=wf_attr_text")
        -- UL's evalText ALWAYS tries the substituted text as Lua (loadstring
        -- "return <text>") — that's how "{wws}*1.6" arithmetic, ternaries and
        -- script-table indexing ("var_date1[{ddw0} +1]") all work. The
        -- attempt is QUIET: prose with tags ("Steps {sc}") fails to compile
        -- and simply stays a template. One UL guard survives at runtime: a
        -- numeric expression result is DISCARDED when the substituted text is
        -- itself purely numeric, so zero-padded "07" doesn't render as "7".
        local ex = compileChunk(sandbox, exprSrc(s), "=wf_attr_text_expr", true)
        if ex then
            identDeps(probe, engine, deps, seen)
            for _, name in ipairs(deps) do engine:activate(name) end
        end
        if ex and tpl then
            fn = function(V)
                local ok, res = pcall(ex, V)
                if ok and res ~= nil and res ~= false then
                    if type(res) == "number" then
                        local t = tpl(V)
                        if tonumber(t) then return t end   -- keep "07"
                        return tostring(res)
                    end
                    return tostring(res)
                end
                return tpl(V)
            end
        else
            fn = ex or tpl
        end
    end

    if not fn then
        -- Pathological attribute (unbalanced quotes etc.): freeze it as text.
        return { const = true, value = s }
    end
    return { const = false, fn = fn, deps = deps }
end

-- True when a raw attribute references at least one {tag}.
function WFExpr.isDynamic(raw)
    return type(raw) == "string" and raw:find("{[^{}]-}") ~= nil
end

return WFExpr
