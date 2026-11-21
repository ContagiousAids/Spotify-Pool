# Spotify Pool — Android 0.2.0

This is the next development build for the existing `com.ollfx.spotifypool` application.

## Included in 0.2.0
- Android MediaProjection consent flow.
- Foreground screen-capture service and notification.
- ImageReader frame acquisition with safe frame closing.
- Start/stop capture controls.
- GitHub Actions debug APK workflow.

## Not yet implemented
- Automatic table boundary detection.
- Ball recognition/tracking.
- Trajectory, collision, cushion/bank-shot solver.
- Floating aiming overlay and calibration.
- Device/gameplay validation.

This build is an implementation step, not a finished aiming assistant. Android requires the user to approve screen capture each session. The capture notification remains visible while capture is active.

## Build
Push to `main` or manually run the Android APK workflow in GitHub Actions. Download the `Spotify-Pool-APK` artifact and install the APK on an Android 10+ device.
