# DanceApp Feature Specification

## Product Summary

DanceApp is a mobile dance-practice app that helps a learner follow a reference
dance video while the app observes their movement through the front camera. The
intended product experience is a guided practice session: choose a challenge,
watch the teacher video, perform alongside it, receive pose-based feedback, and
review progress.

The current implementation is an early prototype. It proves local video
playback, camera preview, frame analysis, and on-device pose inference. Scoring,
feedback, session recording, and polished challenge management are planned
features.

## Goals

- Let users practice a dance from a reference video on the same screen as their
  live camera preview.
- Run pose detection and tracking locally on device without sending camera frames
  to a server.
- Pre-compute or cache reference-video pose tracks so future sessions can start
  faster.
- Provide a foundation for movement comparison, timing alignment, and practice
  feedback.
- Keep the prototype usable on commodity Android phones with a front camera.

## Non-Goals

- Social sharing, user accounts, cloud sync, subscriptions, and leaderboards are
  outside the current scope.
- Multi-person dance evaluation is not part of the first scoring model.
- Server-side ML inference is not planned for the current architecture.
- Professional content authoring tools are not part of the current app.

## Personas

### Learner

A user practicing short dance clips alone. They want to see the teacher, see
themselves, and understand whether their movement timing and body position match
the reference.

### Developer/Researcher

A builder experimenting with on-device pose tracking, dance comparison, and
feedback UX. They need a simple codebase where the ML pipeline can be changed
without rewriting the whole app.

## Current Implemented Features

### Sample Challenge List

Status: implemented as a minimal prototype.

The home screen shows a "Sample challenge" section with one challenge card. On
launch, `MainActivity` copies files from `assets/videos` into app-private
storage and uses Glide to load the first copied file as the card thumbnail.

Requirements:

- The app must copy bundled videos into `filesDir/videos` if that directory is
  absent or empty.
- The app must show at least one selectable sample card when a bundled video is
  available.
- Tapping the sample card must open the dance-practice screen.
- The selected video path should be passed to the practice screen.

Current gaps:

- The practice screen currently reselects the first local video instead of using
  the passed `videoPath` extra.
- There is no metadata model for title, difficulty, duration, thumbnail, or
  challenge ID.
- Multiple bundled videos are copied but not rendered as separate cards.

### Dance Practice Screen

Status: implemented as a visual and camera prototype.

The practice screen displays a full-screen front-camera preview and overlays a
teacher video player near the bottom-left. The sample video loops automatically.
A `Play` button becomes visible after the camera binds successfully.

Requirements:

- The screen must keep the device awake during practice.
- The front camera must be requested and used when permission is granted.
- The camera preview must fill the available screen.
- The teacher/reference video must play on the practice screen.
- The teacher video should loop for repeated practice.
- The app must show useful failure logs if camera binding fails.

Current gaps:

- The `Play` button has no session-control behavior.
- There is no pause/resume synchronization between camera analysis and teacher
  video playback.
- There is no countdown, mirroring control, audio setting, or session timer.
- Camera permission denial is logged but not represented as a recovery UI.

### On-Device Pose Tracking

Status: partially implemented.

The app ships MMDeploy Java bindings, native ARM64 libraries, and RTMDet/RTMPose
NCNN model archives. `DanceActivity` unzips selected model archives into
app-private storage, creates a CPU MMDeploy context, configures a pose tracker,
and applies it to camera frames and reference-video frames.

Requirements:

- The app must load a person detector model and a body pose model from local
  assets.
- The detector should filter for the COCO `PERSON` class.
- The pose tracker should process CameraX RGBA frames after conversion to RGB
  `INT8` MMDeploy mats.
- The analysis pipeline should keep only the latest camera frame to avoid
  growing latency.
- Inference must run off the UI thread.

Current gaps:

- Pose tracker initialization and inference are not lifecycle-safe enough for
  repeated starts/stops.
- The camera analyzer may receive frames before model initialization completes.
- Pose results are logged but not surfaced in the UI.
- Pose inference currently targets CPU only.
- The active model choice is hard-coded to FP16 NCNN archives.

### Reference Video Pose Cache

Status: partially implemented.

The app can run pose tracking over the sample video with JavaCV/FFmpeg and write
results as JSON under `filesDir/video-track-res`.

Requirements:

- If a cached pose JSON file exists for the sample video, the app should load it.
- If no cache exists, the app should process video frames and save pose results.
- Cached data should include frame timestamps and pose results.
- Pose results should preserve keypoints, scores, bounding box, and target ID.

Current gaps:

- The cache schema stores flat pose results and timestamps, but does not group
  multiple people per frame.
- Timestamp/result cardinality can diverge when multiple poses are detected in a
  frame.
- Cache invalidation does not account for model version, app version, or source
  video changes.
- The processing loop does not currently expose progress or cancellation.

### Pose Visualization Utility

Status: implemented but not connected to the UI.

`COCOUtils.kt` defines a COCO skeleton, colors, sigmas, and a `visualizePose`
function that draws skeleton lines and keypoints onto an OpenCV `Mat`.

Requirements:

- The visualizer must draw only keypoints and links with scores above a
  configurable threshold.
- COCO skeleton and keypoint sigma values must stay aligned with the model's
  output keypoint layout.

Current gaps:

- The active camera preview does not show the skeleton overlay.
- The teacher video does not show the cached reference skeleton.
- The visualizer mutates the input frame and returns it, which should be made
  explicit in future API design.

## Planned Features

### Challenge Catalog

Purpose: let users choose from multiple dances.

Functional requirements:

- Display all bundled or locally available challenges.
- Show title, difficulty, duration, and thumbnail.
- Preserve a stable challenge ID independent of file name.
- Open the selected challenge in the practice screen.
- Handle missing or corrupt video files with a visible fallback state.

Acceptance criteria:

- Given three bundled challenges, the home screen renders three cards.
- Tapping each card opens the practice screen with the correct reference video.
- A challenge with no thumbnail still renders with a placeholder.

### Synchronized Practice Session

Purpose: give the `Play` button a real session lifecycle.

Functional requirements:

- The session starts from an idle state after camera and models are ready.
- Tapping `Play` should reset the teacher video to the start, start playback,
  and begin evaluation.
- The app should support pause, resume, restart, and finish states.
- The session should expose elapsed time and current reference-video timestamp
  to the comparison pipeline.
- The app should avoid evaluating frames before the countdown/session start.

Acceptance criteria:

- Starting a session always evaluates against the same reference start point.
- Pausing stops scoring and teacher playback.
- Restarting clears current session score and pose buffers.

### Live Pose Overlay

Purpose: show users that the app can see their movement.

Functional requirements:

- Render the user's detected skeleton over the camera preview.
- Keep overlay coordinates aligned with CameraX preview scaling, rotation, and
  front-camera mirroring.
- Allow confidence thresholds to hide noisy joints.
- Avoid blocking camera analysis while rendering.

Acceptance criteria:

- Keypoints align with the user's body in portrait orientation.
- Overlay remains stable when the device rotates or preview dimensions change.
- The app stays responsive while the overlay is enabled.

### Reference Pose Overlay

Purpose: make the teacher movement easier to inspect and debug.

Functional requirements:

- Load cached reference pose tracks for the selected video.
- Render reference keypoints on top of or near the teacher video.
- Synchronize the displayed reference pose with ExoPlayer's current position.
- Show a processing state while reference tracks are being generated.

Acceptance criteria:

- At timestamp T, the rendered reference pose comes from the nearest cached
  frame at or before T.
- A missing cache triggers generation once and then reuses saved results.

### Pose Comparison and Scoring

Purpose: provide actionable practice feedback.

Functional requirements:

- Compare the learner's current pose against the reference pose at the matching
  timestamp.
- Normalize poses for scale and translation before comparison.
- Account for front-camera mirroring.
- Score visible joints independently so partial detection can still produce
  useful feedback.
- Aggregate joint scores into body-region and session-level scores.
- Distinguish pose-shape errors from timing errors where possible.

Suggested first scoring model:

- Use torso-centered normalization based on shoulders and hips.
- Compute per-joint Euclidean distance between normalized learner and reference
  keypoints.
- Weight joints by confidence from both poses.
- Convert distance to a 0-100 score with configurable thresholds.
- Smooth scores over a short temporal window to reduce flicker.

Acceptance criteria:

- Matching a static pose produces a high score.
- Moving too early or too late lowers the time-aligned score.
- Missing low-confidence joints do not dominate the overall score.

### Feedback UI

Purpose: turn scores into useful coaching.

Functional requirements:

- Show a simple live score during practice.
- Highlight body regions that need adjustment.
- Show an end-of-session summary with overall score, best section, and weakest
  section.
- Keep feedback readable without covering the user's body or teacher video.

Acceptance criteria:

- The learner can understand whether they are broadly matching the reference
  during the session.
- The summary can be generated from locally stored session data.

### Session Recording and Review

Purpose: let users replay their attempt.

Functional requirements:

- Record the learner camera feed during a session when enabled.
- Save session metadata, scores, and optional video in app-private storage.
- Let users replay the teacher video and user attempt side-by-side.
- Allow deleting old sessions to recover storage.

Acceptance criteria:

- A completed session appears in local history.
- Deleting a session removes its metadata and recording.

## Data Models

### Challenge

```kotlin
data class Challenge(
    val id: String,
    val title: String,
    val difficulty: Difficulty,
    val durationMs: Long,
    val videoUri: Uri,
    val thumbnailUri: Uri?,
    val poseTrackUri: Uri?
)
```

### Pose Frame

```kotlin
data class PoseFrame(
    val timestampUs: Long,
    val poses: List<PoseResult>
)
```

### Pose Result

```kotlin
data class PoseResult(
    val targetId: Int,
    val keypoints: List<PointF>,
    val scores: FloatArray,
    val bbox: RectF
)
```

### Session

```kotlin
data class PracticeSession(
    val id: String,
    val challengeId: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val overallScore: Float?,
    val recordingUri: Uri?
)
```

## Non-Functional Requirements

### Performance

- Camera preview should remain visually smooth during inference.
- Analysis should prefer dropping stale frames over accumulating latency.
- Reference pose cache generation should run in the background and report
  progress.
- Model initialization should happen once per process where practical.

### Privacy

- Camera frames should stay on device.
- Session recordings should be opt-in.
- Locally stored videos, pose caches, and recordings should live in app-private
  storage unless explicit export is added.

### Reliability

- The app should handle missing camera permission, missing front camera, model
  load failure, corrupt video, and low storage with visible user states.
- Long-running model and video-processing work should be cancellable when the
  activity is destroyed.

### Compatibility

- Minimum supported SDK is currently 24.
- Current native MMDeploy libraries are bundled only for `arm64-v8a`.
- x86/x86_64 emulator support requires matching native MMDeploy binaries.

## Backlog Priority

### P0

- Use the selected challenge video path in `DanceActivity`.
- Add explicit session states for idle, preparing, ready, playing, paused,
  completed, and error.
- Move model loading and pose tracking out of `DanceActivity`.
- Fix reference pose cache schema to group poses by frame timestamp.
- Add visible permission/error states.

### P1

- Add live skeleton overlay.
- Implement first-pass pose comparison and live score.
- Render all available challenges on the home screen.
- Add progress and cancellation for reference pose cache generation.
- Add basic automated tests for JSON serialization and scoring math.

### P2

- Add session history and review.
- Add recording controls.
- Add challenge metadata files.
- Add configurable model selection and hardware backend support.
- Add polished feedback summaries.
