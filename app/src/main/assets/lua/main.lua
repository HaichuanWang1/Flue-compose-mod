-- CloX Launcher — Axmol/Lua entry point
-- Called by ax::Application on GL thread start

-- A release-built libclox (_AX_DEBUG off) runs at LogLevel::Info, which
-- filters the AXLOGD behind the default print() — every [WFRender]/[WFPersist]
-- line vanishes from logcat. release_print logs at Silent level (passes any
-- filter); rebind so face logs stay visible on release native builds too.
if release_print then print = release_print end

require "watchface"

local function main()
    local scene = WatchfaceScene:create()
    cc.Director:getInstance():runWithScene(scene)
end

main()
