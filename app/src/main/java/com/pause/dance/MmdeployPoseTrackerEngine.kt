package com.pause.dance

import android.content.Context
import android.util.Log
import org.bytedeco.opencv.global.opencv_core.CV_8U
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_RGBA2RGB
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.opencv_core.Mat
import java.io.File
import java.io.FileOutputStream

class MmdeployPoseTrackerEngine(
    private val context: Context
) : PoseTrackerEngine {

    private var detectorModel: mmdeploy.Model? = null
    private var poseModel: mmdeploy.Model? = null
    private var poseContext: mmdeploy.Context? = null
    private var poseDevice: mmdeploy.Device? = null
    private var poseTracker: mmdeploy.PoseTracker? = null
    private var poseTrackerStateHandle: Long? = null
    private val poseTrackerLock = Any()

    private val threadLocalFrameRGB = ThreadLocal.withInitial { Mat() }
    private val threadLocalFrame8U = ThreadLocal.withInitial { Mat() }
    private val threadLocalFrameByteArray = ThreadLocal.withInitial { ByteArray(0) }

    override fun initialize(config: PoseModelConfig) {
        if (poseTracker != null) return

        detectorModel = loadModel(config.detectorId)
        poseModel = loadModel(config.poseId)

        val device = mmdeploy.Device("cpu", 0)
        val mmdeployContext = mmdeploy.Context()
        mmdeployContext.add(device)

        val tracker = mmdeploy.PoseTracker(detectorModel, poseModel, mmdeployContext)
        val params = tracker.initParams()
        Log.d(TAG, "Initialized pose tracker default params: ${params.toMap()}")
        params.detInterval = 1
        params.detLabel = COCODetClasses.PERSON.ordinal
        params.detThr = 0.5F
        params.detMinBboxSize = 100F
        params.keypointSigmas = COCO_VISUALIZATION_CONFIG.sigmas.toFloatArray()

        poseDevice = device
        poseContext = mmdeployContext
        poseTracker = tracker
        poseTrackerStateHandle = tracker.createState(params)
    }

    override fun track(frame: Mat, timestampUs: Long, detect: Int): PoseFrame {
        val tracker = poseTracker ?: error("Pose tracker is not initialized")
        val stateHandle = poseTrackerStateHandle ?: error("Pose tracker state is not initialized")
        val mat = javaCVMatToMMDeployMat(frame)
        val poseResults = synchronized(poseTrackerLock) {
            tracker.apply(stateHandle, mat, detect)
        }
        return poseResults.toPoseFrame(timestampUs, frame.cols(), frame.rows())
    }

    override fun close() {
        val tracker = poseTracker
        val stateHandle = poseTrackerStateHandle

        if (tracker != null && stateHandle != null) {
            runCatching { tracker.releaseState(stateHandle) }
                .onFailure { Log.w(TAG, "Failed to release pose tracker state", it) }
        }
        runCatching { tracker?.release() }
            .onFailure { Log.w(TAG, "Failed to release pose tracker", it) }
        runCatching { detectorModel?.release() }
            .onFailure { Log.w(TAG, "Failed to release detector model", it) }
        runCatching { poseModel?.release() }
            .onFailure { Log.w(TAG, "Failed to release pose model", it) }
        runCatching { poseContext?.release() }
            .onFailure { Log.w(TAG, "Failed to release pose context", it) }
        runCatching { poseDevice?.release() }
            .onFailure { Log.w(TAG, "Failed to release pose device", it) }

        poseTrackerStateHandle = null
        poseTracker = null
        detectorModel = null
        poseModel = null
        poseContext = null
        poseDevice = null
    }

    private fun loadModel(modelId: String): mmdeploy.Model {
        val modelFolder = File(context.filesDir, modelId)

        if (!modelFolder.isDirectory) {
            Log.d(TAG, "Model directory ${modelFolder.absolutePath} not found. Unzipping...")

            val modelZipName = "$modelId.zip"
            val modelZipOutFile = File(context.filesDir, modelZipName)
            context.assets.open(modelZipName).use { input ->
                FileOutputStream(modelZipOutFile).use { output ->
                    copyStream(input, output)
                }
            }
            unzip(modelZipOutFile, modelFolder)
        }

        Log.d(
            TAG,
            "Loading model from directory ${modelFolder.absolutePath}, " +
                    "${modelFolder.isDirectory}, ${modelFolder.listFiles()}"
        )

        return mmdeploy.Model(modelFolder.absolutePath)
    }

    private fun javaCVMatToMMDeployMat(frameIn: Mat): mmdeploy.Mat {
        var frame = frameIn

        if (frame.channels() == 4) {
            val frameRGB = threadLocalFrameRGB.get() ?: Mat()
            cvtColor(frame, frameRGB, COLOR_RGBA2RGB)
            threadLocalFrameRGB.set(frameRGB)
            frame = frameRGB
        }

        if (frame.depth() != CV_8U) {
            Log.d(TAG, "Converting frame from depth ${frame.depth()} to $CV_8U")
            val frame8U = threadLocalFrame8U.get() ?: Mat()
            frame.convertTo(frame8U, CV_8U)
            threadLocalFrame8U.set(frame8U)
            frame = frame8U
        }

        var frameByteArray = threadLocalFrameByteArray.get() ?: ByteArray(0)
        val expectedSize = (frame.total() * frame.channels()).toInt()
        if (frameByteArray.size != expectedSize) {
            frameByteArray = ByteArray(expectedSize)
            threadLocalFrameByteArray.set(frameByteArray)
        }
        frame.data().get(frameByteArray)

        return mmdeploy.Mat(
            frame.rows(), frame.cols(), frame.channels(),
            mmdeploy.PixelFormat.RGB, mmdeploy.DataType.INT8,
            frameByteArray
        )
    }

    private fun Array<mmdeploy.PoseTracker.Result>.toPoseFrame(
        timestampUs: Long,
        imageWidth: Int,
        imageHeight: Int
    ): PoseFrame {
        return PoseFrame(
            timestampUs = timestampUs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            poses = map { result -> result.toPoseResult() }
        )
    }

    private fun mmdeploy.PoseTracker.Result.toPoseResult(): PoseResult {
        return PoseResult(
            targetId = targetID,
            bounds = PoseBounds(
                left = bbox.left,
                top = bbox.top,
                right = bbox.right,
                bottom = bbox.bottom
            ),
            keypoints = keypoints.mapIndexed { index, keypoint ->
                PosePoint(
                    x = keypoint.x,
                    y = keypoint.y,
                    score = scores.getOrElse(index) { 0f }
                )
            }
        )
    }

    companion object {
        private const val TAG = "DanceApp::PoseEngine"
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
