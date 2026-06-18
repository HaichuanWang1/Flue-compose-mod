# Add project specific ProGuard rules here.
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.flue.launcher.data.model.** { *; }
-keep class com.flue.launcher.ui.theme.** { *; }
-keep class * extends androidx.compose.runtime.Composable { *; }
