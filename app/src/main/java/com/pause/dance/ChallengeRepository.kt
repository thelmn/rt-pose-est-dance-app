package com.pause.dance

import android.content.Context
import java.io.File
import java.util.Locale

data class Challenge(
    val id: String,
    val title: String,
    val videoFile: File,
)

class ChallengeRepository(private val context: Context) {

    fun getChallenges(): List<Challenge> {
        ensureBundledVideosCopied()

        val videoFolder = File(context.filesDir, VIDEO_ASSETS_FOLDER_NAME)
        return videoFolder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in SUPPORTED_VIDEO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.map { file ->
                Challenge(
                    id = file.nameWithoutExtension.toStableId(),
                    title = file.nameWithoutExtension.toDisplayTitle(),
                    videoFile = file,
                )
            }
            ?: emptyList()
    }

    fun getChallengeById(challengeId: String): Challenge? {
        return getChallenges().firstOrNull { it.id == challengeId }
    }

    private fun ensureBundledVideosCopied() {
        val videoFolder = File(context.filesDir, VIDEO_ASSETS_FOLDER_NAME)
        val assetVideos = context.assets.list(VIDEO_ASSETS_FOLDER_NAME).orEmpty()
        if (assetVideos.isEmpty()) return

        videoFolder.mkdirs()
        for (video in assetVideos) {
            val destination = File(videoFolder, video)
            if (destination.isFile) continue

            copyStream(
                context.assets.open(File(VIDEO_ASSETS_FOLDER_NAME, video).path),
                destination.outputStream()
            )
        }
    }

    private fun String.toStableId(): String {
        return lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun String.toDisplayTitle(): String {
        return replace(Regex("\\s+-\\s+"), "\n")
    }

    companion object {
        const val VIDEO_ASSETS_FOLDER_NAME = "videos"

        private val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mkv")
    }
}
