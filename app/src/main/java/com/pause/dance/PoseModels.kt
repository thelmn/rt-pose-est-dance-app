package com.pause.dance

data class PosePoint(
    val x: Float,
    val y: Float,
    val score: Float
)

data class PoseBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class PoseResult(
    val targetId: Int,
    val bounds: PoseBounds,
    val keypoints: List<PosePoint>
)

data class PoseFrame(
    val timestampUs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val poses: List<PoseResult>
)

data class PoseModelConfig(
    val detectorId: String,
    val poseId: String,
    val backend: String,
    val precision: String,
    val keypointSet: String
)

interface PoseTrackerEngine : AutoCloseable {
    fun initialize(config: PoseModelConfig)
    fun track(frame: org.bytedeco.opencv.opencv_core.Mat, timestampUs: Long, detect: Int = -1): PoseFrame
    override fun close()
}
