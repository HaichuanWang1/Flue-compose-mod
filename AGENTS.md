# Flue-compose — AGENTS.md

## Project

Android Wear launcher (Jetpack Compose), single module `:app`. Package: `com.flue.launcher.mod`.

## Build (Gradle 9.4.1 wrapper)

```powershell
.\gradlew.bat assembleRelease --no-daemon --console=plain
.\gradlew.bat installDebug
```

JDK 17 locally / JDK 21 in CI. Kotlin 2.3.21, AGP 9.2.1, Compose BOM 2026.04.01.

## Repository mirrors

`settings.gradle.kts` uses Aliyun mirrors for Gradle plugins, Google, and general dependencies. Add Aliyun mirror if adding new dependency repositories. The wrapper distribution points to `https://mirrors.cloud.tencent.com/gradle/gradle-9.4.1-all.zip`.

## Git

Never push unless explicitly told to.

## Signing

Release needs `keystore.properties` (in `app/`) or env vars `FLUE_SIGNING_*`. There's a debug keystore (`app/keystore.jks`) for local debug builds; add `keystore.properties` pointing to it to use release signing.

## Source packages (three roots)

| Root | Content |
|---|---|
| `app/src/main/kotlin/com/example/wlauncher/` | **Main app**: LauncherActivity, SettingsActivity, ViewModel, UI screens |
| `app/src/main/java/com/flue/launcher/watchface/` | Native watchface support (LunchWatchFace*, JbWatchFace*) |
| `app/src/main/java/com/dudu/wearlauncher/` | Legacy jb_watch plugin — Java, external code, modify carefully |

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
