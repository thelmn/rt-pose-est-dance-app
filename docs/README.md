# DanceApp Documentation

DanceApp is an Android prototype for dance practice with on-device pose tracking.
The current app lets a user select a bundled sample dance challenge, opens a
front-camera practice view, plays the teacher/reference video as an overlay, and
runs RTMDet/RTMPose pose tracking locally through MMDeploy.

This documentation separates the current implementation from the intended
product behavior so it can serve both as a handoff and as a roadmap.

## Documents

- [Feature Specification](feature-spec.md): product goals, user flows, feature
  requirements, non-functional requirements, and backlog.
- [Architecture](architecture.md): Android module structure, runtime flows,
  pose model pipeline, data storage, threading, dependencies, and known technical
  risks.

## Current Snapshot

- Platform: native Android, Kotlin, XML views.
- Package: `com.pause.dance`.
- Minimum SDK: 24.
- Target SDK: 34.
- Core libraries: CameraX, Media3 ExoPlayer, Glide, JavaCV/OpenCV/FFmpeg,
  MMDeploy Java bindings.
- Bundled ML assets: RTMDet-N and RTMPose-T body7 NCNN model archives.
- Bundled content: one sample dance video under `assets/videos`.

## Current App Flow

1. `MainActivity` copies bundled videos from assets into app-private storage.
2. The first copied video is shown as a sample challenge thumbnail.
3. Tapping the sample challenge launches `DanceActivity`.
4. `DanceActivity` starts looping the sample video with ExoPlayer.
5. The front camera is bound through CameraX.
6. A background initialization step unzips and loads detector and pose models.
7. Camera frames and sample-video frames are converted to OpenCV/MMDeploy mats.
8. Pose tracker results are logged and sample-video pose results are cached as
   JSON under app-private storage.

## Important Current Limitations

- There is no pose comparison or scoring yet.
- The `Play` button is shown after camera setup but does not currently start a
  synchronized evaluation flow.
- Pose overlays are implemented as utilities but not rendered in the active UI.
- Only the first bundled sample video is exposed in the home screen.
- The navigation graph appears to be template residue and is not wired into the
  active two-activity flow.
- Model loading and pose tracking live directly in `DanceActivity`; the
  architecture document describes how to split this into testable services.
