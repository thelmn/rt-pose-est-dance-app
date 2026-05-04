package com.pause.dance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.opencv_core.Mat
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DanceActivity : AppCompatActivity() {

    /** Blocking camera recording operations are performed using this executor */
    private lateinit var cameraRecorderExecutor: ExecutorService

    /** Blocking pose analysis operations are performed using this executor */
    private lateinit var frameAnalysisExecutor: ExecutorService

    private lateinit var sampleVideoPlayerView: PlayerView
    private lateinit var sampleVideoPlayer: ExoPlayer

    private var sampleVideoFile: File? = null
    private var sampleVideoTrackFrames: List<PoseFrame>? = null

    private lateinit var cameraPreviewView: PreviewView

    private lateinit var playButton: Button
    private lateinit var sessionStatusView: TextView

    private lateinit var poseTrackerEngine: PoseTrackerEngine
    private lateinit var referencePoseTrackCache: LocalPoseTrackCache

    private var sessionState: PracticeSessionState = PracticeSessionState.Preparing
    private var sampleVideoReady = false
    private var cameraReady = false
    private var poseTrackerReady = false
    private var referenceTrackReady = false

    init {
        Log.i(TAG, "Instantiated new " + this.javaClass)
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dance)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraRecorderExecutor = Executors.newSingleThreadExecutor()
        frameAnalysisExecutor = Executors.newSingleThreadExecutor()
        poseTrackerEngine = MmdeployPoseTrackerEngine(applicationContext)
        referencePoseTrackCache = LocalPoseTrackCache(filesDir)

        cameraPreviewView = findViewById(R.id.activity_dance_camera_preview)

        sampleVideoPlayer = ExoPlayer.Builder(this).build()
        sampleVideoPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        sampleVideoPlayer.playWhenReady = false
        sampleVideoPlayerView = findViewById(R.id.activity_dance_teacher_video_player)
        sampleVideoPlayerView.player = sampleVideoPlayer

        playButton = findViewById(R.id.activity_dance_play_button)
        sessionStatusView = findViewById(R.id.activity_dance_session_status)

        playButton.setOnClickListener {
            handlePlayButtonClick()
        }

        setSessionState(PracticeSessionState.Preparing)
    }

    override fun onStart() {
        super.onStart()

        if (startSampleVideoPlayback()) {
            sampleVideoReady = true
            updateReadinessState()
        }

        Thread {
            try {
                poseTrackerEngine.initialize(DEFAULT_POSE_MODEL_CONFIG)
                runOnUiThread {
                    poseTrackerReady = true
                    updateReadinessState()
                }

                val trackFrames = loadOrRunSampleVideoTrackFrames()
                sampleVideoTrackFrames = trackFrames
                Log.d(TAG, "Initialized sample video track frames: $sampleVideoTrackFrames")
                runOnUiThread {
                    if (trackFrames != null) {
                        referenceTrackReady = true
                        updateReadinessState()
                    } else {
                        setSessionState(
                            PracticeSessionState.Error(
                                "Reference pose setup failed"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare pose tracking", e)
                runOnUiThread {
                    setSessionState(PracticeSessionState.Error("Pose setup failed"))
                }
            }
        }.start()
    }

    public override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!hasPermissions(this)) {
            setSessionState(PracticeSessionState.Preparing)
            requestPermissions(PERMISSIONS_REQUIRED, 1)
            return
        }

        lifecycleScope.launch {
            setupCamera()
        }
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted")
                lifecycleScope.launch {
                    setupCamera()
                }
            } else {
                Log.d(TAG, "Camera permission denied")
                setSessionState(PracticeSessionState.Error("Camera permission denied"))
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()

        cameraRecorderExecutor.shutdown()
        frameAnalysisExecutor.shutdown()
        poseTrackerEngine.close()
        sampleVideoPlayer.release()
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private suspend fun setupCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).await()

        cameraProvider?.let { cameraProvider ->
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionFilter { resolutions, _ ->
                    // allow all h<=480, w<=640 if portrait, h<=640, w<=480 if landscape
                    resolutions.filter { resolution ->
                        (resolution.height <= 480 && resolution.width <= 640) ||
                                (resolution.height <= 640 && resolution.width <= 480)
                    }
                }
                .build()

            cameraPreview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            cameraPreview?.surfaceProvider = cameraPreviewView.surfaceProvider

            val recorder = Recorder.Builder().setExecutor(cameraRecorderExecutor).build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.bindToLifecycle(
                    this, cameraSelector,
                    cameraPreview,
                    videoCapture
                )

                Toast.makeText(baseContext, "Camera setup successful", Toast.LENGTH_SHORT).show()
                cameraReady = true
                updateReadinessState()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                setSessionState(PracticeSessionState.Error("Camera setup failed"))
            }
        }
    }

    private fun startSampleVideoPlayback(): Boolean {
        val selectedVideo = selectedChallengeVideoFile()
        if (selectedVideo == null) {
            Log.e(TAG, "No sample video available for playback")
            Toast.makeText(baseContext, "No sample video available", Toast.LENGTH_SHORT).show()
            setSessionState(PracticeSessionState.Error("No sample video available"))
            return false
        }

        sampleVideoFile = selectedVideo
        sampleVideoPlayer.setMediaItem(MediaItem.fromUri(selectedVideo.toURI().toString()))
        sampleVideoPlayer.prepare()
        return true
    }

    private fun selectedChallengeVideoFile(): File? {
        intent.getStringExtra(EXTRA_VIDEO_PATH)
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { return it }

        val selectedChallengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID)
        val challengeRepository = ChallengeRepository(applicationContext)
        if (selectedChallengeId != null) {
            challengeRepository.getChallengeById(selectedChallengeId)?.let { return it.videoFile }
            Log.e(TAG, "Selected challenge $selectedChallengeId was not found")
        }

        return challengeRepository.getChallenges().firstOrNull()?.videoFile
    }

    private fun loadOrRunSampleVideoTrackFrames(): List<PoseFrame>? {
        Log.d(TAG, "Loading or running sample video track frames")
        val videoFile = sampleVideoFile ?: return null
        val challengeId = selectedChallengeId()
        val sampleVideoTrackResFile = referencePoseTrackCache.cacheFile(videoFile)

        Log.d(TAG, "Sample video file: ${videoFile.absolutePath} exists: ${videoFile.exists()}")
        Log.d(TAG, "Checking for sample video track results file: ${sampleVideoTrackResFile.absolutePath}")

        referencePoseTrackCache.load(videoFile, challengeId, DEFAULT_POSE_MODEL_CONFIG)?.let { cachedFrames ->
            Log.d(TAG, "Loaded track results from ${sampleVideoTrackResFile.absolutePath}")
            return cachedFrames
        }

        Log.d(TAG, "Failed to load track results from ${sampleVideoTrackResFile.absolutePath}")
        Log.d(TAG, "Running pose tracker on video file ${videoFile.absolutePath}")

        val grabber: FFmpegFrameGrabber
        try {
            val sampleVideoInputStream = FileInputStream(videoFile)
            grabber = FFmpegFrameGrabber(sampleVideoInputStream)
            grabber.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open video capture for ${videoFile.absolutePath} to track poses", e)
            return null
        }

        var frameMat: Mat
        val trackFrames = mutableListOf<PoseFrame>()
        val frameToMatConverter = OpenCVFrameConverter.ToMat()
        try {
            while (true) {
                val frame: Frame
                try {
                    frame = grabber.grabImage() ?: break
                    Log.d(
                        TAG,
                        "Grabbed frame from video capture for ${videoFile.absolutePath}, " +
                                "frame: ${frame.imageWidth}x${frame.imageHeight}x${frame.imageChannels}," +
                                " ${frame.imageDepth} at ${frame.timestamp}"
                    )

                    frameMat = frameToMatConverter.convert(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to grab frame from video capture for ${videoFile.absolutePath}", e)
                    break
                }

                val poseFrame = poseTrackerEngine.track(frameMat, frame.timestamp)
                Log.d(TAG, "Tracked ${poseFrame.poses.size} poses from frame at ${frame.timestamp}")
                trackFrames.add(poseFrame)
            }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.close() }
        }

        Log.d(
            TAG,
            "Tracked ${trackFrames.sumOf { it.poses.size }} poses across " +
                    "${trackFrames.size} frames from video file ${videoFile.absolutePath}"
        )

        referencePoseTrackCache.save(videoFile, challengeId, DEFAULT_POSE_MODEL_CONFIG, trackFrames)
        Log.d(TAG, "Saved track results to ${sampleVideoTrackResFile.absolutePath}")

        return trackFrames
    }

    private fun updateReadinessState() {
        if (sessionState is PracticeSessionState.Error || sessionState is PracticeSessionState.Playing) {
            return
        }

        if (sampleVideoReady && cameraReady && poseTrackerReady && referenceTrackReady) {
            setSessionState(PracticeSessionState.Ready)
        } else {
            setSessionState(PracticeSessionState.Preparing)
        }
    }

    private fun handlePlayButtonClick() {
        when (sessionState) {
            PracticeSessionState.Ready,
            PracticeSessionState.Paused,
            PracticeSessionState.Completed -> {
                sampleVideoPlayer.seekTo(0)
                sampleVideoPlayer.play()
                setSessionState(PracticeSessionState.Playing)
            }

            PracticeSessionState.Playing -> {
                sampleVideoPlayer.pause()
                setSessionState(PracticeSessionState.Paused)
            }

            else -> Unit
        }
    }

    private fun setSessionState(state: PracticeSessionState) {
        sessionState = state

        val (message, buttonText, buttonEnabled) = when (state) {
            PracticeSessionState.Preparing -> Triple(
                readinessMessage(),
                "Play",
                false
            )

            PracticeSessionState.Ready -> Triple(
                "Ready",
                "Play",
                true
            )

            PracticeSessionState.Playing -> Triple(
                "Playing",
                "Pause",
                true
            )

            PracticeSessionState.Paused -> Triple(
                "Paused",
                "Resume",
                true
            )

            PracticeSessionState.Completed -> Triple(
                "Completed",
                "Restart",
                true
            )

            is PracticeSessionState.Error -> Triple(
                state.message,
                "Play",
                false
            )
        }

        sessionStatusView.text = message
        playButton.text = buttonText
        playButton.isEnabled = buttonEnabled
        playButton.visibility = Button.VISIBLE
    }

    private fun readinessMessage(): String {
        val pending = mutableListOf<String>()
        if (!sampleVideoReady) pending.add("video")
        if (!cameraReady) pending.add("camera")
        if (!poseTrackerReady) pending.add("pose model")
        if (!referenceTrackReady) pending.add("reference poses")

        return if (pending.isEmpty()) {
            "Preparing"
        } else {
            "Preparing: ${pending.joinToString(", ")}"
        }
    }

    private fun selectedChallengeId(): String {
        return intent.getStringExtra(EXTRA_CHALLENGE_ID)
            ?: sampleVideoFile?.nameWithoutExtension
            ?: "unknown"
    }

    companion object {
        private const val TAG = "DanceApp::DanceActivity"

        private val DEFAULT_POSE_MODEL_CONFIG = PoseModelConfig(
            detectorId = "rtmdet-n-fp16-ncnn",
            poseId = "rtmpose-t-body7-fp16-ncnn",
            backend = "ncnn",
            precision = "fp16",
            keypointSet = "body7"
        )

        const val EXTRA_CHALLENGE_ID = "challengeId"
        const val EXTRA_VIDEO_PATH = "videoPath"

        private var PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
