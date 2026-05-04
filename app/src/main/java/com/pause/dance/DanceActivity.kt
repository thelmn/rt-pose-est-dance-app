package com.pause.dance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.CV_8U
import org.bytedeco.opencv.global.opencv_core.CV_8UC4
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_RGBA2RGB
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.opencv_core.Mat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class DanceActivity : AppCompatActivity() {

    /** Blocking camera recording operations are performed using this executor */
    private lateinit var cameraRecorderExecutor: ExecutorService

    /** Blocking pose analysis operations are performed using this executor */
    private lateinit var frameAnalysisExecutor: ExecutorService

    private lateinit var sampleVideoPlayerView: PlayerView
    private lateinit var sampleVideoPlayer: ExoPlayer

    private var sampleVideoPath: String? = null
    private var sampleVideoTrackResults: List<mmdeploy.PoseTracker.Result>? = null

    private lateinit var cameraPreviewView: PreviewView

    private lateinit var playButton: Button

    private var poseTracker: mmdeploy.PoseTracker? = null
    private var poseTrackerStateHandle: Long? = null

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

        cameraPreviewView = findViewById(R.id.activity_dance_camera_preview)

        sampleVideoPlayer = ExoPlayer.Builder(this).build()
        sampleVideoPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        sampleVideoPlayer.playWhenReady = true
        sampleVideoPlayerView = findViewById(R.id.activity_dance_teacher_video_player)
        sampleVideoPlayerView.player = sampleVideoPlayer

        playButton = findViewById(R.id.activity_dance_play_button)
    }

    override fun onStart() {
        super.onStart()

        startSampleVideoPlayback()

        // run these in background
        Thread {
            initPoseTracker()
            initSampleVideoTrackResults()
        }.start()
    }

    public override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!hasPermissions(this)) {
            requestPermissions(PERMISSIONS_REQUIRED, 1)
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
            } else {
                Log.d(TAG, "Camera permission denied")
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()

        cameraRecorderExecutor.shutdown()
        frameAnalysisExecutor.shutdown()
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var frameAnalyzer: ImageAnalysis? = null

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

            frameAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            frameAnalyzer?.setAnalyzer(frameAnalysisExecutor, { imageProxy ->
                if (imageProxy.format != PixelFormat.RGBA_8888) {
                    Log.e(
                        TAG, "Expected image format ${PixelFormat.RGBA_8888}, " +
                                "got ${imageProxy.format}"
                    )
                    imageProxy.close()
                    return@setAnalyzer
                }
                Log.d(TAG, "Received image: ${imageProxy.width}x${imageProxy.height}")
                val imageBuffer = imageProxy.planes[0].buffer

                val mat = Mat(imageProxy.height, imageProxy.width, CV_8UC4, Pointer(imageBuffer))

                val poseResults = applyPoseTracker(mat)
                if (poseResults != null) {
                    // val frameMat = visualizePose(mat, poseResults, COCO_VISUALIZATION_CONFIG)
                    val poseResultJson = if (poseResults.isNotEmpty())
                        poseResults[0].toJSON().toString()
                    else "{}"
                    Log.d(TAG, "Pose results: $poseResultJson")
                }
                imageProxy.close()
            })

            try {
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector,
                    cameraPreview,
                    videoCapture,
                    frameAnalyzer
                )

                Toast.makeText(baseContext, "Camera setup successful", Toast.LENGTH_SHORT).show()
                playButton.visibility = Button.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }
    }

    private fun startSampleVideoPlayback() {
        val selectedVideo = selectedChallengeVideoFile()
        if (selectedVideo == null) {
            Log.e(TAG, "No sample video available for playback")
            Toast.makeText(baseContext, "No sample video available", Toast.LENGTH_SHORT).show()
            return
        }

        sampleVideoPath = selectedVideo.absolutePath
        sampleVideoPlayer.setMediaItem(MediaItem.fromUri(selectedVideo.toURI().toString()))
        sampleVideoPlayer.prepare()
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

    private fun initSampleVideoTrackResults() {
        sampleVideoTrackResults = loadOrRunSampleVideoTrackResults()
        Log.d(TAG, "Initialized sample video track results: $sampleVideoTrackResults")
    }

    private fun loadOrRunSampleVideoTrackResults(): List<mmdeploy.PoseTracker.Result>? {
        Log.d(TAG, "Loading or running sample video track results")
        val sampleVideoUri = sampleVideoPath ?: return null

        val videoTrackResFolder = File(baseContext.filesDir, VIDEO_TRACKRES_FOLDER_NAME)

        val sampleVideoFile = File(sampleVideoUri)
        val sampleVideoTrackResFile =
            File(videoTrackResFolder, sampleVideoFile.nameWithoutExtension + ".json")

        Log.d(
            TAG,
            "Sample video file: ${sampleVideoFile.absolutePath} exists: ${sampleVideoFile.exists()}"
        )
        Log.d(
            TAG,
            "Checking for sample video track results file: ${sampleVideoTrackResFile.absolutePath}"
        )

        if (sampleVideoTrackResFile.exists()) {
            val trackResDataJSON = JSONObject(sampleVideoTrackResFile.readText())

            Log.d(TAG, "Loaded track results from ${sampleVideoTrackResFile.absolutePath}")
            Log.d(TAG, "Track results: $trackResDataJSON")

            val trackResTimestamps = trackResDataJSON.getJSONArray("timestamps")
            val trackResPoses = trackResDataJSON.getJSONArray("poseResults")

            val trackResults = List(trackResPoses.length()) { i ->
                poseResultFromJSON(trackResPoses.getJSONObject(i))
            }
            return trackResults
        }

        Log.d(TAG, "Failed to load track results from ${sampleVideoTrackResFile.absolutePath}")
        Log.d(TAG, "Running pose tracker on video file $sampleVideoUri")

        // read video file and track poses using pose tracker

        val grabber: FFmpegFrameGrabber
        try {
            val sampleVideoInputStream = FileInputStream(sampleVideoUri)
            grabber = FFmpegFrameGrabber(sampleVideoInputStream)
            grabber.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open video capture for $sampleVideoUri to track poses", e)
            return null
        }

        poseTracker ?: return null

        var frame: Frame
        var frameMat: Mat
        val trackResults = mutableListOf<mmdeploy.PoseTracker.Result>()
        val trackResultsTimestamps = mutableListOf<Long>()
        val frameToMatConverter = OpenCVFrameConverter.ToMat()
        while (true) {
            try {
                frame = grabber.grabImage()
                Log.d(
                    TAG,
                    "Grabbed frame from video capture for $sampleVideoUri, " +
                            "frame: ${frame.imageWidth}x${frame.imageHeight}x${frame.imageChannels}," +
                            " ${frame.imageDepth} at ${frame.timestamp}"
                )

                frameMat = frameToMatConverter.convert(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grab frame from video capture for $sampleVideoUri", e)
                break
            }

            val poseResults = applyPoseTracker(frameMat)
            if (poseResults != null) {
                Log.d(TAG, "Tracked ${poseResults.size} poses from frame at ${frame.timestamp}")
                trackResults.addAll(poseResults)
                trackResultsTimestamps.add(frame.timestamp)
            }
        }
        Log.d(TAG, "Tracked ${trackResults.size} poses and " +
                "${trackResultsTimestamps.size} timestamps from video file $sampleVideoUri")

        val trackResDataJSON = poseResultsToJSON(trackResults, trackResultsTimestamps)
        sampleVideoTrackResFile.parentFile?.mkdirs()
        sampleVideoTrackResFile.createNewFile()
        sampleVideoTrackResFile.writeText(trackResDataJSON.toString())
        Log.d(TAG, "Saved track results to ${sampleVideoTrackResFile.absolutePath}")
        Log.d(TAG, "Track results: $trackResDataJSON")

        return trackResults
    }

    private fun loadDetectorModel(): mmdeploy.Model {
        // Load detector model
        val detectorModelFolder = File(baseContext.filesDir, DETECTOR_MODEL_BASENAME)

        if (!detectorModelFolder.isDirectory) {
            Log.d(TAG, "Detector model directory ${detectorModelFolder.absolutePath} " +
                    "not found. Unzipping...")

            val detectorModelZipName = "${DETECTOR_MODEL_BASENAME}.zip"
            val detectorModelZipInStream = applicationContext.assets.open(detectorModelZipName)
            val detectorModelZipOutFile = File(applicationContext.filesDir, detectorModelZipName)

            copyStream(detectorModelZipInStream, FileOutputStream(detectorModelZipOutFile))
            unzip(detectorModelZipOutFile, detectorModelFolder)
        }

        Log.d(
            TAG,
            "Loading detector model from directory ${detectorModelFolder.absolutePath}, " +
                    "${detectorModelFolder.isDirectory}, ${detectorModelFolder.listFiles()}"
        )

        // TODO: initialize only once globally
        val detectorModel = mmdeploy.Model(detectorModelFolder.absolutePath)
        Log.d(TAG, "Loaded detector model")

        return detectorModel
    }

    private fun loadPoseModel(): mmdeploy.Model {
        // Load pose detector model
        val poseModelFolder = File(baseContext.filesDir, POSE_MODEL_BASENAME)

        if (!poseModelFolder.isDirectory) {
            Log.d(
                TAG,
                "Pose model directory ${poseModelFolder.absolutePath} not found. Unzipping..."
            )
            val poseModelZipName = "${POSE_MODEL_BASENAME}.zip"
            val poseModelZipInStream = applicationContext.assets.open(poseModelZipName)
            val poseModelZipOutFile = File(applicationContext.filesDir, poseModelZipName)

            copyStream(poseModelZipInStream, FileOutputStream(poseModelZipOutFile))
            unzip(poseModelZipOutFile, poseModelFolder)
        }

        Log.d(
            TAG,
            "Loading pose model from directory ${poseModelFolder.absolutePath}, " +
                    "${poseModelFolder.isDirectory}, ${poseModelFolder.listFiles()}"
        )

        val poseModel = mmdeploy.Model(poseModelFolder.absolutePath)
        Log.d(TAG, "Loaded pose model")

        return poseModel
    }

    private fun initPoseTracker() {
        val detectorModel = loadDetectorModel()
        val poseModel = loadPoseModel()

        val poseCtx = mmdeploy.Context()
        poseCtx.add(mmdeploy.Device("cpu", 0))
        // poseCtx.add()  // TODO: use scheduler
        poseTracker = mmdeploy.PoseTracker(detectorModel, poseModel, poseCtx)
        Log.d(TAG, "Loaded pose tracker")

        val poseTrackerParam = poseTracker!!.initParams()
        Log.d(TAG, "Initialized pose tracker default params: ${poseTrackerParam.toMap()}")
        poseTrackerParam.detInterval = 1
        poseTrackerParam.detLabel = COCODetClasses.PERSON.ordinal
        poseTrackerParam.detThr = 0.5F
        poseTrackerParam.detMinBboxSize = 100F
        poseTrackerParam.keypointSigmas = COCO_VISUALIZATION_CONFIG.sigmas.toFloatArray()

        poseTrackerStateHandle = poseTracker!!.createState(poseTrackerParam)
    }

    private val threadLocalFrameRGB = ThreadLocal.withInitial { Mat() }
    private val threadLocalFrame8U = ThreadLocal.withInitial { Mat() }
    private val threadLocalFrameByteArray = ThreadLocal.withInitial { ByteArray(0) }

    private fun javaCVMatToMMDeployMat(frameIn: Mat): mmdeploy.Mat {
        var frame = frameIn

        val frameRGB = threadLocalFrameRGB.get() ?: Mat()
        cvtColor(frame, frameRGB, COLOR_RGBA2RGB)
        threadLocalFrameRGB.set(frameRGB)
        frame = frameRGB

        if (frame.depth() != CV_8U) {
            Log.d(TAG, "Converting frame from depth ${frame.depth()} to $CV_8U")
            val frame8U = threadLocalFrame8U.get() ?: Mat()
            frame.convertTo(frame8U, CV_8U)
            threadLocalFrame8U.set(frame8U)
            frame = frame8U
        }

        var frameByteArray = threadLocalFrameByteArray.get() ?: ByteArray(0)
        if (frameByteArray.size != (frame.total() * frame.channels()).toInt()) {
            frameByteArray = ByteArray((frame.total() * frame.channels()).toInt())
            threadLocalFrameByteArray.set(frameByteArray)
        }
        frame.data().get(frameByteArray)

        return mmdeploy.Mat(
            frame.rows(), frame.cols(), frame.channels(),
            mmdeploy.PixelFormat.RGB, mmdeploy.DataType.INT8,
            frameByteArray
        )
    }

    private fun applyPoseTracker(frame: Mat): Array<mmdeploy.PoseTracker.Result>? {
        poseTracker?.let { poseTracker ->
            val mat = javaCVMatToMMDeployMat(frame)
            val poseResults = poseTracker.apply(poseTrackerStateHandle!!, mat, -1)
            return poseResults
        }
        return null
    }

    companion object {
        private const val TAG = "DanceApp::DanceActivity"

        private const val VIDEO_TRACKRES_FOLDER_NAME = "video-track-res"

        private const val DETECTOR_MODEL_BASENAME = "rtmdet-n-fp16-ncnn"
        private const val POSE_MODEL_BASENAME = "rtmpose-t-body7-fp16-ncnn"

        const val EXTRA_CHALLENGE_ID = "challengeId"
        const val EXTRA_VIDEO_PATH = "videoPath"

        private var PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)

        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

fun mmdeploy.PoseTracker.Params.toMap(): Map<String, Any> {
    return mapOf(
        "detInterval" to detInterval,
        "detLabel" to detLabel,
        "detThr" to detThr,
        "detMinBboxSize" to detMinBboxSize,
        "detNmsThr" to detNmsThr,
        "poseMaxNumBboxes" to poseMaxNumBboxes,
        "poseKptThr" to poseKptThr,
        "poseMinKeypoints" to poseMinKeypoints,
        "poseBboxScale" to poseBboxScale,
        "poseMinBboxSize" to poseMinBboxSize,
        "poseNmsThr" to poseNmsThr,
        "keypointSigmas" to keypointSigmas,
        "keypointSigmasSize" to keypointSigmasSize,
        "trackIouThr" to trackIouThr,
        "trackMaxMissing" to trackMaxMissing,
        "trackHistorySize" to trackHistorySize,
        "stdWeightPosition" to stdWeightPosition,
        "stdWeightVelocity" to stdWeightVelocity,
        "smoothParams" to smoothParams
    )
}

fun poseResultFromJSON(poseResult: org.json.JSONObject): mmdeploy.PoseTracker.Result {
    val keypoints = poseResult.getJSONArray("keypoints")
    val keypointsArray = Array(keypoints.length()) { i ->
        val keypoint = keypoints.getJSONObject(i)
        mmdeploy.PointF(
            keypoint.getDouble("x").toFloat(),
            keypoint.getDouble("y").toFloat()
        )
    }

    val scores = poseResult.getJSONArray("scores")
    val scoresArray = FloatArray(scores.length()) { i -> scores.getDouble(i).toFloat() }

    val bbox = poseResult.getJSONObject("bbox")
    val bboxRect = mmdeploy.Rect(
        bbox.getDouble("left").toFloat(), bbox.getDouble("top").toFloat(),
        bbox.getDouble("right").toFloat(), bbox.getDouble("bottom").toFloat()
    )

    val targetID = poseResult.getInt("targetID")

    return mmdeploy.PoseTracker.Result(keypointsArray, scoresArray, bboxRect, targetID)
}

fun mmdeploy.PoseTracker.Result.toJSON(): JSONObject {
    val keypoints = JSONArray()
    this.keypoints.forEach { keypoint ->
        val keypointJSON = JSONObject()
        keypointJSON.put("x", keypoint.x.toDouble())
        keypointJSON.put("y", keypoint.y.toDouble())
        keypoints.put(keypointJSON)
    }

    val scores = JSONArray()
    this.scores.forEach { score -> scores.put(score.toDouble()) }

    val bbox = JSONObject()
    bbox.put("left", this.bbox.left.toDouble())
    bbox.put("top", this.bbox.top.toDouble())
    bbox.put("right", this.bbox.right.toDouble())
    bbox.put("bottom", this.bbox.bottom.toDouble())

    val poseResult = JSONObject()
    poseResult.put("keypoints", keypoints)
    poseResult.put("scores", scores)
    poseResult.put("bbox", bbox)
    poseResult.put("targetID", this.targetID)

    return poseResult
}

fun poseResultsToJSON(
    poseResults: List<mmdeploy.PoseTracker.Result>,
    timestamps: List<Long>
): JSONObject {
    val poseResultsJSON = JSONArray()
    poseResults.forEach { poseResult -> poseResultsJSON.put(poseResult.toJSON()) }

    val timestampsJSON = JSONArray()
    timestamps.forEach { timestamp -> timestampsJSON.put(timestamp) }

    val trackResData = JSONObject()
    trackResData.put("timestamps", timestampsJSON)
    trackResData.put("poseResults", poseResultsJSON)

    return trackResData
}
