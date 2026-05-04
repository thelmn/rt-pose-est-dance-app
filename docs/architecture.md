# DanceApp Architecture

## Overview

DanceApp is a single-module native Android app written in Kotlin. It uses XML
layouts, AppCompat/Material components, CameraX for live camera input, Media3
ExoPlayer for reference video playback, JavaCV/OpenCV/FFmpeg for video frame
access and image conversion, and MMDeploy Java bindings for local pose tracking.

The current architecture is prototype-oriented: most orchestration lives in
`DanceActivity`. The recommended direction is to preserve the working local ML
pipeline while separating UI, session state, challenge data, media playback,
camera input, and pose inference into explicit components.

## Module Structure

```text
.
├── app
│   ├── libs
│   │   └── mmdeploy.jar
│   └── src/main
│       ├── assets
│       │   ├── videos/
│       │   ├── rtmdet-*.zip
│       │   └── rtmpose-*.zip
│       ├── java/com/pause/dance
│       │   ├── MainActivity.kt
│       │   ├── DanceActivity.kt
│       │   ├── COCOUtils.kt
│       │   ├── utils.kt
│       │   └── DanceAppGlideModule.kt
│       ├── jniLibs/arm64-v8a
│       │   ├── libmmdeploy.so
│       │   └── libmmdeploy_java.so
│       └── res
│           ├── layout/
│           ├── values/
│           └── navigation/
├── gradle/libs.versions.toml
└── docs/
```

## Runtime Components

### MainActivity

Responsibility:

- Inflate the home/sample challenge screen.
- Copy bundled videos from `assets/videos` to `filesDir/videos`.
- Load the first copied video into a thumbnail view with Glide.
- Launch `DanceActivity` when the challenge card is tapped.

Current notes:

- The selected video path is passed with the `videoPath` intent extra.
- Only one card is rendered.
- There is no challenge metadata abstraction.

Recommended evolution:

- Replace direct file enumeration with a `ChallengeRepository`.
- Render a list of challenge view models.
- Pass a stable `challengeId` to the practice screen rather than a raw path.

### DanceActivity

Responsibility:

- Inflate the practice screen.
- Keep the screen awake.
- Request/verify camera permission.
- Bind CameraX preview, video capture, and image analysis use cases.
- Create and control an ExoPlayer instance for the reference video.
- Initialize MMDeploy models and pose tracker.
- Analyze camera frames.
- Generate/load cached pose tracks for the reference video.

Current notes:

- Camera setup runs from `onResume`.
- Model initialization and reference-video tracking run on a manually created
  background `Thread` from `onStart`.
- Camera frame analysis runs on `frameAnalysisExecutor`.
- Camera recording executor exists and is used to build `Recorder`, but session
  recording is not implemented.
- ExoPlayer is created in `onCreate`, prepared in `startSampleVideoPlayback`,
  and set to repeat.

Recommended evolution:

- Move session orchestration into a lifecycle-aware controller or ViewModel.
- Replace manual `Thread` usage with structured coroutines.
- Release ExoPlayer and MMDeploy resources explicitly in lifecycle callbacks.
- Split pose tracking into a service with a small API.

### COCOUtils

Responsibility:

- Define COCO skeleton metadata.
- Define visualization colors and keypoint sigmas.
- Draw pose skeletons onto OpenCV matrices.
- Define COCO detector class labels.

Current notes:

- `COCO_VISUALIZATION_CONFIG.sigmas` feeds MMDeploy pose tracker parameters.
- Visualization utility is available but commented out in camera analysis.

Recommended evolution:

- Keep model metadata near the model configuration.
- Add coordinate mapping utilities for preview/video overlay rendering.

### utils

Responsibility:

- Copy streams.
- Unzip model archives into app-private storage.

Current notes:

- `unzip` trusts zip entry paths. If future model archives can come from outside
  the APK, add zip-slip path validation before extraction.

## Dependency Map

```text
UI
├── MainActivity
│   ├── Glide
│   └── File/assets utilities
└── DanceActivity
    ├── CameraX Preview/ImageAnalysis/VideoCapture
    ├── Media3 ExoPlayer
    ├── JavaCV FFmpegFrameGrabber
    ├── OpenCV Mat/image conversion
    ├── MMDeploy Model/Context/PoseTracker
    ├── COCOUtils metadata
    └── JSON pose cache helpers
```

## Data Flow

### Challenge Launch Flow

```text
APK assets/videos
    ↓ copy on first launch
filesDir/videos
    ↓ first file loaded as thumbnail
MainActivity challenge card
    ↓ tap
DanceActivity intent
```

Implementation detail:

- `MainActivity` passes `videoPath`, but `DanceActivity` currently discovers the
  first local video again. This should be fixed before multiple challenges are
  added.

### Camera Pose Flow

```text
Front camera
    ↓ CameraX Preview
PreviewView

Front camera
    ↓ CameraX ImageAnalysis RGBA_8888
ImageProxy
    ↓ OpenCV Mat wrapping plane buffer
RGBA Mat
    ↓ cvtColor RGBA -> RGB
RGB Mat
    ↓ byte array copy
MMDeploy Mat
    ↓ PoseTracker.apply(state, mat, -1)
PoseTracker.Result[]
    ↓ currently Logcat only
future overlay/scoring/session store
```

Key properties:

- Backpressure strategy is `STRATEGY_KEEP_ONLY_LATEST`.
- Target rotation is currently fixed to `Surface.ROTATION_0`.
- Resolution is filtered to small 4:3-friendly sizes for inference cost.

Risks:

- Fixed rotation and front-camera mirroring will complicate overlay alignment.
- The OpenCV `Mat` wraps the `ImageProxy` plane buffer. Processing must complete
  before `imageProxy.close()`.
- `poseTrackerStateHandle!!` assumes initialization succeeded.

### Reference Video Pose Flow

```text
filesDir/videos/<video>
    ↓ derive cache file name
filesDir/video-track-res/<video-name>.json
    ├── exists: parse JSON into PoseTracker.Result list
    └── missing:
        FFmpegFrameGrabber
            ↓ grabImage loop
        JavaCV Frame
            ↓ OpenCVFrameConverter.ToMat
        OpenCV Mat
            ↓ PoseTracker.apply
        PoseTracker.Result[]
            ↓ serialize
        JSON cache
```

Current cache shape:

```json
{
  "timestamps": [12345],
  "poseResults": [
    {
      "keypoints": [{"x": 0.0, "y": 0.0}],
      "scores": [0.0],
      "bbox": {"left": 0.0, "top": 0.0, "right": 0.0, "bottom": 0.0},
      "targetID": 0
    }
  ]
}
```

Recommended cache shape:

```json
{
  "schemaVersion": 1,
  "sourceVideo": {
    "id": "demo-dance-1",
    "fileName": "Demo dance 1 - Intermediate - Short.mp4",
    "lastModifiedMs": 0
  },
  "model": {
    "detector": "rtmdet-n-fp16-ncnn",
    "pose": "rtmpose-t-body7-fp16-ncnn"
  },
  "frames": [
    {
      "timestampUs": 12345,
      "poses": []
    }
  ]
}
```

Why change it:

- It groups multiple poses by frame.
- It supports cache invalidation when videos or models change.
- It makes timestamp-to-pose lookup straightforward for synchronized playback.

## Threading Model

Current threads/executors:

- Main thread: activity lifecycle, view setup, ExoPlayer setup, permission
  request.
- `cameraRecorderExecutor`: single-thread executor used by CameraX `Recorder`.
- `frameAnalysisExecutor`: single-thread executor for CameraX frame analyzer.
- Manual background `Thread`: model initialization and reference-video tracking.

Recommended threading model:

- Use `lifecycleScope` or a ViewModel scope for initialization jobs.
- Use a dedicated `PoseInferenceDispatcher` backed by one single-thread executor
  if MMDeploy tracker state is not thread-safe.
- Use cancellable coroutines for reference-video cache generation.
- Keep all UI updates on the main dispatcher.

## Storage

App-private storage under `Context.filesDir`:

- `videos/`: copied bundled videos.
- `rtmdet-n-fp16-ncnn/`: unzipped detector model.
- `rtmpose-t-body7-fp16-ncnn/`: unzipped pose model.
- `video-track-res/`: generated reference pose JSON.

APK assets:

- Model zip archives are packaged under `app/src/main/assets`.
- One sample video is packaged under `app/src/main/assets/videos`.

Native libraries:

- MMDeploy native libraries are packaged for `arm64-v8a`.

## Build Configuration

Important Gradle settings:

- Android Gradle Plugin: 8.13.2.
- Kotlin: 1.9.24.
- `compileSdk`: 35.
- `targetSdk`: 34.
- `minSdk`: 24.
- Java/Kotlin target: 11.
- ViewBinding enabled.
- Legacy JNI library packaging enabled.

Notable dependencies:

- CameraX `camera-core` via lifecycle, camera2, view, video.
- Media3 ExoPlayer/UI/common.
- Glide.
- JavaCV, JavaCPP, OpenBLAS, FFmpeg, OpenCV, VideoInput.
- MMDeploy from local `app/libs/mmdeploy.jar` and `jniLibs`.

## Proposed Target Architecture

```text
Presentation
├── MainActivity
├── DanceActivity
├── ChallengeListViewModel
└── PracticeSessionViewModel

Domain
├── ChallengeRepository
├── PracticeSessionController
├── PoseComparisonEngine
├── ScoreAggregator
└── SessionRepository

Infrastructure
├── AssetChallengeDataSource
├── LocalPoseTrackCache
├── CameraFrameSource
├── ReferenceVideoPlayer
├── ReferenceVideoFrameReader
├── MmdeployPoseTracker
└── AppStorage
```

### ChallengeRepository

Owns challenge discovery and metadata.

Responsibilities:

- Discover bundled challenges.
- Copy required challenge assets to app-private storage.
- Return stable challenge models to the UI.
- Hide file-system details from activities.

### PracticeSessionController

Owns session state transitions.

States:

- `Idle`
- `Preparing`
- `Ready`
- `Countdown`
- `Playing`
- `Paused`
- `Completed`
- `Error`

Responsibilities:

- Coordinate camera frames, reference playback, pose tracker readiness, and
  scoring.
- Expose immutable UI state.
- Cancel work when the session ends.

### MmdeployPoseTracker

Owns model extraction, model loading, state creation, and inference.

Interface sketch:

```kotlin
interface PoseTrackerEngine : Closeable {
    suspend fun initialize(config: PoseModelConfig)
    fun track(frame: RgbFrame, timestampUs: Long): PoseFrame
}
```

Design notes:

- Keep MMDeploy object lifetime out of activities.
- Serialize calls if tracker state is not thread-safe.
- Return app-owned data classes instead of leaking MMDeploy classes through the
  app.

### LocalPoseTrackCache

Owns cached reference pose tracks.

Responsibilities:

- Read/write versioned pose-track JSON.
- Validate cache compatibility with source video and model config.
- Provide timestamp lookup for reference playback.

### PoseComparisonEngine

Owns pose matching and scoring math.

Responsibilities:

- Normalize learner and reference poses.
- Match learner target to reference target.
- Compute joint, region, frame, and session scores.
- Emit debuggable intermediate values for tuning.

## Error Handling Strategy

Required visible errors:

- Camera permission denied.
- No front camera available.
- Camera bind failure.
- Missing/corrupt reference video.
- Model archive missing or unzip failure.
- MMDeploy model load failure.
- Pose cache read/write failure.
- Low storage during cache generation or recording.

Logging strategy:

- Keep detailed technical failures in Logcat.
- Convert recoverable failures into UI states with retry actions.
- Avoid silent fallback when model or video selection changes.

## Lifecycle Risks To Fix

- ExoPlayer should be released when the activity is destroyed.
- Pose tracker, MMDeploy models, contexts, and states should be closed/released
  if their bindings expose close/destroy APIs.
- Camera binding should avoid rebinding repeatedly on every resume if already
  bound.
- Long-running reference-video processing should cancel when leaving the screen.
- Model initialization should report readiness before the user can start a
  session.

## Testing Strategy

### Unit Tests

- Pose JSON serialization/deserialization.
- Cache schema validation and invalidation.
- Pose normalization math.
- Joint scoring and aggregate scoring.
- Challenge metadata parsing.

### Instrumented Tests

- Challenge list opens practice screen with the selected challenge.
- Permission-denied state is shown.
- Practice screen reaches ready state with a fake camera/pose engine.

### Manual Device Tests

- Fresh install copies assets and loads model archives.
- First reference cache generation completes.
- Relaunch uses cached pose data.
- Front camera preview and teacher video render together.
- Inference does not freeze the UI.
- App handles background/foreground transitions.

## Security And Privacy Considerations

- Camera frames are processed locally.
- Session recording should remain opt-in.
- App-private storage should be the default for pose caches and recordings.
- Validate zip paths before extracting any archive that is not bundled with the
  APK.
- Do not log raw pose data in production builds unless a debug flag is enabled.

## Migration Plan

1. Add app-owned data classes for challenges, pose frames, and sessions.
2. Move asset copying into `ChallengeRepository`.
3. Move model extraction/loading/inference into `MmdeployPoseTracker`.
4. Replace the reference pose cache JSON with a frame-grouped, versioned schema.
5. Add a session controller/ViewModel and wire the `Play` button to it.
6. Add live pose overlay with correct coordinate mapping.
7. Add first-pass comparison/scoring.
8. Add session summary and local history.
