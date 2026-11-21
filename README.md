# Spotify Pool (working title)

Free Android development scaffold for an independent 8-ball-pool aiming assistant.

## Package identity
Android package names cannot contain hyphens. The requested display name is **Spotify Pool** and the valid application ID is `com.ollfx.spotifypool`.

## Build on GitHub using a phone
1. Create a new repository named `Spotify-Pool`.
2. Upload the contents of this folder to the repository root (not the containing folder).
3. Commit the files to the `main` branch.
4. Open the repository's **Actions** tab.
5. Select **Android APK** and press **Run workflow**, or let the push-triggered build run.
6. Open the successful workflow run, scroll to **Artifacts**, and download `spotify-pool-debug-apk`.
7. Extract the ZIP on Android and install `app-debug.apk`. Android may ask you to allow installs from your browser or file manager.

## Build environment
- JDK 17
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- compileSdk 35
- minSdk 29 (Android 10+)

## Current implementation status
This is a compiling foundation scaffold, not a finished aiming tool. It currently provides:
- Native Android activity and diagnostic screen
- Overlay-permission status check and settings shortcut
- GitHub Actions debug APK workflow

Not yet implemented:
- MediaProjection screen capture
- Table and ball detection
- Cue/target-ball classification
- Collision and cushion trajectory solver
- Floating aiming overlay

Do not treat the current APK as detecting balls or calculating shots. Implement and validate those modules in later milestones using screenshots from the intended device/game version.

## Debugging
- Build errors: open the failed GitHub Actions run and expand **Build debug APK**.
- Runtime crashes: capture Android Logcat output with an ADB-capable Android debugging setup, then share the exception and stack trace.
- Include device model, Android version, commit hash, and exact reproduction steps with bug reports.
