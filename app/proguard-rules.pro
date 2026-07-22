# Add project specific ProGuard rules here.
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.flue.launcher.data.model.** { *; }
-keep class com.flue.launcher.ui.theme.** { *; }
-keep class * extends androidx.compose.runtime.Composable { *; }
-keep class io.github.proify.lyricon.** { *; }
-keepclassmembers class io.github.proify.lyricon.** { *; }

# Axmol engine — native code calls these via JNI
-keep class dev.axmol.lib.** { *; }
-keep class com.ailife.clox.cocos.** { *; }
-keep class com.ailife.clox.cocos.bridge.** { *; }

# LuaBridge.onLuaEvent is called from native (JNI)
-keepclassmembers class com.ailife.clox.cocos.LuaBridge {
    static void onLuaEvent(java.lang.String, java.lang.String);
    static void onLuaEvent(java.lang.String, java.lang.String, java.lang.String);
}

# CocosManager methods called from Axmol native
-keepclassmembers class com.ailife.clox.cocos.CocosManager {
    public static ** getGlView();
    public static ** init(android.app.Activity);
}

# Bridge helpers — called via Lua dispatch
-keep class com.ailife.clox.cocos.bridge.HealthHelper { *; }
-keep class com.ailife.clox.cocos.bridge.SensorHelper { *; }
-keep class com.ailife.clox.cocos.bridge.WeatherRepository { *; }
-keep class com.ailife.clox.cocos.bridge.CalendarHelper { *; }
-keep class com.ailife.clox.cocos.bridge.LiveDataHelper { *; }
