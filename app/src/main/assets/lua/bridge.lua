-- Bridge — Lua side of the JNI ↔ Lua event bus
-- Android calls Bridge.dispatchEvent(event, payload) from JNI

Bridge = {}
Bridge._handlers = {}

-- Register a handler for a named event
function Bridge.onEvent(event, handler)
    if not Bridge._handlers[event] then
        Bridge._handlers[event] = {}
    end
    table.insert(Bridge._handlers[event], handler)
end

-- Called from native (CocosManager.kt → JNI → Lua) to dispatch events
function Bridge.dispatchEvent(event, payload)
    local handlers = Bridge._handlers[event]
    if handlers then
        for _, h in ipairs(handlers) do
            local ok, err = pcall(h, payload)
            if not ok then
                print("[Bridge] handler error for event=" .. event .. ": " .. tostring(err))
            end
        end
    end
end

-- Sends a Lua event up to Kotlin via JNI (reverse bridge)
-- Kotlin side: LuaBridge.onLuaEvent() is called by native code
local _bridgeLogged = false
function Bridge.sendToAndroid(event, payload)
    local p = payload or ""
    -- Android: LuaJavaBridge is registered as a global by Axmol's C++ binding
    if LuaJavaBridge then
        if not _bridgeLogged then
            print("[Bridge] LuaJavaBridge available — reverse bridge OK")
            _bridgeLogged = true
        end
        local ok, err = pcall(LuaJavaBridge.callStaticMethod,
            "com/ailife/clox/cocos/LuaBridge",
            "onLuaEvent",
            { event, p },
            "(Ljava/lang/String;Ljava/lang/String;)V"
        )
        if not ok then
            print("[Bridge] callStaticMethod FAILED for event=" .. event .. ": " .. tostring(err))
        end
    elseif luaoc then
        -- iOS fallback
        luaoc.callStaticMethod(
            "com/ailife/clox/cocos/LuaBridge",
            "onLuaEvent",
            { event, p }
        )
    else
        if not _bridgeLogged then
            print("[Bridge] ERROR: LuaJavaBridge is NIL — reverse bridge unavailable, all events will be silently dropped")
            _bridgeLogged = true
        end
    end
end

return Bridge
