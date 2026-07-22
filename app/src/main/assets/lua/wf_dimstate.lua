-- wf_dimstate.lua — shared bright/dim timing state for the {abss}/{abssl}
-- tags. The renderer reports dim switches and the running elapsed clock;
-- the tag computes read milliseconds-since-bright from here. -1 while dim
-- (WM/UL convention).

local M = {
    dim         = false,
    elapsed     = 0,    -- latest renderer elapsed (seconds since face load)
    brightSince = 0,    -- elapsed at the moment bright mode began
}

-- Called every renderer tick so brightSince has a live time base.
function M.tick(elapsed)
    M.elapsed = elapsed
end

-- Called by WFRender:setDim on every transition (and once at face load).
function M.setDim(dim)
    if dim == M.dim then return end
    M.dim = dim
    if not dim then M.brightSince = M.elapsed end
end

-- Seconds since bright mode started, or nil while dim.
function M.brightFor(elapsed)
    if M.dim then return nil end
    return elapsed - M.brightSince
end

return M
