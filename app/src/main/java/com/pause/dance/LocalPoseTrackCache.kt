package com.pause.dance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalPoseTrackCache(
    filesDir: File,
    private val folderName: String = CACHE_FOLDER_NAME
) {
    private val cacheDir = File(filesDir, folderName)

    fun load(videoFile: File, challengeId: String, modelConfig: PoseModelConfig): List<PoseFrame>? {
        val cacheFile = cacheFile(videoFile)
        if (!cacheFile.isFile) return null

        val json = runCatching { JSONObject(cacheFile.readText()) }.getOrNull() ?: return null
        if (!isCompatible(json, videoFile, challengeId, modelConfig)) return null

        return json.getJSONArray("frames").toPoseFrames()
    }

    fun save(
        videoFile: File,
        challengeId: String,
        modelConfig: PoseModelConfig,
        frames: List<PoseFrame>
    ) {
        if (!cacheDir.isDirectory) cacheDir.mkdirs()

        val cacheFile = cacheFile(videoFile)
        val temporaryFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        temporaryFile.writeText(toJson(videoFile, challengeId, modelConfig, frames).toString())
        if (cacheFile.exists() && !cacheFile.delete()) {
            throw IllegalStateException("Failed to replace ${cacheFile.absolutePath}")
        }
        if (!temporaryFile.renameTo(cacheFile)) {
            throw IllegalStateException("Failed to move ${temporaryFile.absolutePath} to ${cacheFile.absolutePath}")
        }
    }

    fun cacheFile(videoFile: File): File {
        return File(cacheDir, videoFile.nameWithoutExtension + ".json")
    }

    private fun isCompatible(
        json: JSONObject,
        videoFile: File,
        challengeId: String,
        modelConfig: PoseModelConfig
    ): Boolean {
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return false

        val sourceVideo = json.optJSONObject("sourceVideo") ?: return false
        if (sourceVideo.optString("challengeId") != challengeId) return false
        if (sourceVideo.optString("fileName") != videoFile.name) return false
        if (sourceVideo.optLong("sizeBytes") != videoFile.length()) return false
        if (sourceVideo.optLong("lastModifiedMs") != videoFile.lastModified()) return false

        val model = json.optJSONObject("model") ?: return false
        return model.optString("detectorId") == modelConfig.detectorId &&
                model.optString("poseId") == modelConfig.poseId &&
                model.optString("backend") == modelConfig.backend &&
                model.optString("precision") == modelConfig.precision &&
                model.optString("keypointSet") == modelConfig.keypointSet
    }

    private fun toJson(
        videoFile: File,
        challengeId: String,
        modelConfig: PoseModelConfig,
        frames: List<PoseFrame>
    ): JSONObject {
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                "sourceVideo",
                JSONObject()
                    .put("challengeId", challengeId)
                    .put("fileName", videoFile.name)
                    .put("sizeBytes", videoFile.length())
                    .put("lastModifiedMs", videoFile.lastModified())
            )
            .put(
                "model",
                JSONObject()
                    .put("detectorId", modelConfig.detectorId)
                    .put("poseId", modelConfig.poseId)
                    .put("backend", modelConfig.backend)
                    .put("precision", modelConfig.precision)
                    .put("keypointSet", modelConfig.keypointSet)
            )
            .put("frames", frames.toPoseFrameJsonArray())
    }

    companion object {
        const val CACHE_FOLDER_NAME = "video-track-res-v2"
        const val SCHEMA_VERSION = 2
    }
}

fun PoseFrame.toJSON(): JSONObject {
    return JSONObject()
        .put("timestampUs", timestampUs)
        .put("imageWidth", imageWidth)
        .put("imageHeight", imageHeight)
        .put("poses", poses.toPoseResultJsonArray())
}

fun PoseResult.toJSON(): JSONObject {
    return JSONObject()
        .put("targetId", targetId)
        .put(
            "bounds",
            JSONObject()
                .put("left", bounds.left.toDouble())
                .put("top", bounds.top.toDouble())
                .put("right", bounds.right.toDouble())
                .put("bottom", bounds.bottom.toDouble())
        )
        .put("keypoints", keypoints.toPosePointJsonArray())
}

fun PosePoint.toJSON(): JSONObject {
    return JSONObject()
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("score", score.toDouble())
}

private fun List<PoseFrame>.toPoseFrameJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { frame -> array.put(frame.toJSON()) }
    return array
}

private fun List<PoseResult>.toPoseResultJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { pose -> array.put(pose.toJSON()) }
    return array
}

private fun List<PosePoint>.toPosePointJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { point -> array.put(point.toJSON()) }
    return array
}

private fun JSONArray.toPoseFrames(): List<PoseFrame> {
    return List(length()) { index ->
        getJSONObject(index).toPoseFrame()
    }
}

private fun JSONObject.toPoseFrame(): PoseFrame {
    return PoseFrame(
        timestampUs = getLong("timestampUs"),
        imageWidth = getInt("imageWidth"),
        imageHeight = getInt("imageHeight"),
        poses = getJSONArray("poses").toPoseResults()
    )
}

private fun JSONArray.toPoseResults(): List<PoseResult> {
    return List(length()) { index ->
        getJSONObject(index).toPoseResult()
    }
}

private fun JSONObject.toPoseResult(): PoseResult {
    val boundsJson = getJSONObject("bounds")
    return PoseResult(
        targetId = getInt("targetId"),
        bounds = PoseBounds(
            left = boundsJson.getDouble("left").toFloat(),
            top = boundsJson.getDouble("top").toFloat(),
            right = boundsJson.getDouble("right").toFloat(),
            bottom = boundsJson.getDouble("bottom").toFloat()
        ),
        keypoints = getJSONArray("keypoints").toPosePoints()
    )
}

private fun JSONArray.toPosePoints(): List<PosePoint> {
    return List(length()) { index ->
        val point = getJSONObject(index)
        PosePoint(
            x = point.getDouble("x").toFloat(),
            y = point.getDouble("y").toFloat(),
            score = point.getDouble("score").toFloat()
        )
    }
}
