# Flue-compose — AGENTS.md

## Project

Android Wear launcher (Jetpack Compose), single module `:app`. Package: `com.flue.launcher.mod`.

## Build (Gradle 9.4.1 wrapper)

### Install testing — ALWAYS use release build

All install testing must use the release APK (`assembleRelease` with R8 + minification). **Never use `installDebug`** — the debug signature conflicts with the release install on device.

```powershell
# Build release APK
.\gradlew.bat assembleRelease --no-daemon --console=plain

# Install release APK (uninstall first if signed differently)
adb uninstall com.flue.launcher.mod 2>$null
adb install app\build\outputs\apk\release\app-release.apk
```

JDK 17 locally / JDK 21 in CI. Kotlin 2.3.21, AGP 9.2.1, Compose BOM 2026.04.01.

## Repository mirrors

`settings.gradle.kts` uses Aliyun mirrors for Gradle plugins, Google, and general dependencies. Add Aliyun mirror if adding new dependency repositories. The wrapper distribution points to `https://mirrors.cloud.tencent.com/gradle/gradle-9.4.1-all.zip`.

## Git

Never push unless explicitly told to.

## Signing

Release needs `keystore.properties` (in `app/`) or env vars `FLUE_SIGNING_*`. There's a debug keystore (`app/keystore.jks`) for local debug builds; add `keystore.properties` pointing to it to use release signing.

**Important:** `assembleRelease` is the only way to install test — see Build section above.

## Sensitive content (DO NOT PUBLISH)

The `.gitignore` already excludes these — never `git add -f` or commit them:
- `keystore.properties` — signing passwords & key alias
- `*.jks` / `keystore/` — keystore files
- `local.properties` — SDK paths (personal machine info)
- `.gradle/`, `.idea/`, `.kotlin/` — IDE & build caches

When adding new files, check they don't contain: ADB serials, file system paths with usernames, API keys, passwords.

## Source packages (three roots)

| Root | Content |
|---|---|
| `app/src/main/kotlin/com/example/wlauncher/` | **Main app**: LauncherActivity, SettingsActivity, ViewModel, UI screens |
| `app/src/main/java/com/flue/launcher/watchface/` | Native watchface support (LunchWatchFace*) |
| `app/src/main/java/com/dudu/wearlauncher/` | Legacy plugin — Java, external code, modify carefully |

## Key files

- `LauncherActivity.kt` — launcher entrypoint (`HOME`/`LAUNCHER` intent filter)
- `FlueApplication.kt` — Application class
- `CrashHandler.kt` — custom crash handler, routes to `CrashActivity`
- `LauncherViewModel.kt` — central ViewModel
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths (`com.flue.launcher.mod.fileprovider`)

## No tests, no linters, no formatters

Zero test files. No ktlint/detekt/`.editorconfig`. Do not add test infrastructure unless asked.

## ProGuard

`app/proguard-rules.pro` — keeps ViewModel constructors, data models, theme classes, Composables, and Lyricon classes.

## No opencode config

No `opencode.json`. Create one at root if needed.

## Branch guide

- `main` — current latest public version
- `classic/beta1.2` — older snapshot, reference only
- `youzipi/fix-1.4` — performance & shortcut fix branch from beta1.4 era

The CHANGELOG describes PR #19 (splice old side screen into main), version `beta1.3` in docs but `beta1.5-mod` (versionCode 15) in `app/build.gradle.kts`.

## README warning

> "historical spaghetti code and vibe-coded low quality code" — expect inconsistent patterns.
