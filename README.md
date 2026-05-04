# DanceApp Android

An Android dance-practice prototype built around on-device pose estimation.

The ML path is currently:

- RTMDet finds people in the frame.
- RTMPose predicts body keypoints for each detected person.
- MMDeploy runs the exported NCNN models on device.
- The app caches reference-video pose tracks for later comparison and scoring.

## Run

Build and install the debug app:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or run it from Android Studio on a connected device.

## Simple Structure

- `app/src/main/java/com/pause/dance/` app code, pose engine, cache, and UI
- `app/src/main/assets/` sample videos and bundled ML model archives
- `app/src/main/jniLibs/` MMDeploy native libraries
- `docs/` product, architecture, and phase planning notes

## Notes

- The app is focused on body pose estimation, not general-purpose vision.
- The longer design and implementation notes live in `docs/README.md`.
