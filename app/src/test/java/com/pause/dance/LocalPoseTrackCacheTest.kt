package com.pause.dance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalPoseTrackCacheTest {

    @Test
    fun saveThenLoadReturnsFrameGroupedPoseData() {
        val root = Files.createTempDirectory("pose-cache-test").toFile()
        val video = root.videoFile()
        val cache = LocalPoseTrackCache(root)
        val frames = listOf(
            PoseFrame(
                timestampUs = 12_000L,
                imageWidth = 640,
                imageHeight = 480,
                poses = listOf(
                    PoseResult(
                        targetId = 7,
                        bounds = PoseBounds(1f, 2f, 3f, 4f),
                        keypoints = listOf(
                            PosePoint(10f, 11f, 0.9f),
                            PosePoint(12f, 13f, 0.8f)
                        )
                    )
                )
            )
        )

        cache.save(video, CHALLENGE_ID, MODEL_CONFIG, frames)

        val loaded = cache.load(video, CHALLENGE_ID, MODEL_CONFIG)

        assertNotNull(loaded)
        assertEquals(frames, loaded)
    }

    @Test
    fun loadReturnsNullWhenModelConfigChanges() {
        val root = Files.createTempDirectory("pose-cache-test").toFile()
        val video = root.videoFile()
        val cache = LocalPoseTrackCache(root)
        cache.save(video, CHALLENGE_ID, MODEL_CONFIG, emptyList())

        val loaded = cache.load(
            video,
            CHALLENGE_ID,
            MODEL_CONFIG.copy(poseId = "rtmpose-upgraded")
        )

        assertNull(loaded)
    }

    @Test
    fun loadReturnsNullWhenVideoIdentityChanges() {
        val root = Files.createTempDirectory("pose-cache-test").toFile()
        val video = root.videoFile()
        val cache = LocalPoseTrackCache(root)
        cache.save(video, CHALLENGE_ID, MODEL_CONFIG, emptyList())

        video.appendText("changed")

        val loaded = cache.load(video, CHALLENGE_ID, MODEL_CONFIG)

        assertNull(loaded)
    }

    private fun File.videoFile(): File {
        return File(this, "demo.mp4").also { file ->
            file.writeText("video")
            file.setLastModified(1_700_000_000_000L)
        }
    }

    companion object {
        private const val CHALLENGE_ID = "demo"

        private val MODEL_CONFIG = PoseModelConfig(
            detectorId = "rtmdet-n-fp16-ncnn",
            poseId = "rtmpose-t-body7-fp16-ncnn",
            backend = "ncnn",
            precision = "fp16",
            keypointSet = "body7"
        )
    }
}
