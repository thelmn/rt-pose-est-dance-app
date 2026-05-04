package com.pause.dance

import android.content.Context
import java.io.File
import java.util.Locale

data class Challenge(
    val id: String,
    val title: String,
    val difficulty: Difficulty,
    val videoFile: File,
)

enum class Difficulty(val displayName: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced"),
    UNKNOWN("Unknown");

    companion object {
        fun fromLabel(label: String): Difficulty {
            return entries.firstOrNull {
                it.displayName.equals(label.trim(), ignoreCase = true)
            } ?: UNKNOWN
        }
    }
}

class ChallengeRepository(private val context: Context) {

    fun getChallenges(): List<Challenge> {
        ensureBundledVideosCopied()

        val videoFolder = File(context.filesDir, VIDEO_ASSETS_FOLDER_NAME)
        return videoFolder.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in SUPPORTED_VIDEO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.map { file ->
                val metadata = ChallengeMetadata.fromFile(file)
                Challenge(
                    id = file.nameWithoutExtension.toStableId(),
                    title = metadata.title,
                    difficulty = metadata.difficulty,
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

    companion object {
        const val VIDEO_ASSETS_FOLDER_NAME = "videos"

        private val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mkv")
    }
}

private data class ChallengeMetadata(
    val title: String,
    val difficulty: Difficulty,
) {
    companion object {
        fun fromFile(file: File): ChallengeMetadata {
            val parts = file.nameWithoutExtension
                .split(Regex("\\s+-\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            return ChallengeMetadata(
                title = parts.firstOrNull() ?: file.nameWithoutExtension,
                difficulty = parts.getOrNull(1)?.let(Difficulty::fromLabel) ?: Difficulty.UNKNOWN,
            )
        }
    }
}
