package com.pause.dance

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import java.io.File


class MainActivity : AppCompatActivity() {

    private lateinit var sampleThumbnailView: AppCompatImageView
    private lateinit var sampleVideoCardView: MaterialCardView

    init {
        Log.i(TAG, "Instantiated new " + this.javaClass)
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        sampleThumbnailView = findViewById(R.id.activity_main_card_1_video_thumbnail)
        sampleVideoCardView = findViewById(R.id.activity_main_card_1)

        val videoAssetsFolderName = "videos"
        val videoAssetsFolder = File(baseContext.filesDir, videoAssetsFolderName)

        if (!(videoAssetsFolder.exists()
                    && videoAssetsFolder.isDirectory
                    && videoAssetsFolder.listFiles()?.isNotEmpty() == true)) {
            val videos = applicationContext.assets.list(videoAssetsFolderName)
            if (!videos.isNullOrEmpty()) {
                videoAssetsFolder.mkdirs()
                for (video in videos) {
                    val videoFile = File(videoAssetsFolder, video)
                    copyStream(
                        applicationContext.assets.open(
                            File(videoAssetsFolderName, video).path),
                        videoFile.outputStream()
                    )
                }
            }
            Log.d(TAG, "Copied ${videos?.size} video assets to $videoAssetsFolder")
        }

        videoAssetsFolder.listFiles()?.let { files ->
            if (files.isNotEmpty()) {
                Glide.with(this).load(files[0]).into(sampleThumbnailView)
                sampleVideoCardView.setOnClickListener {
                    val intent = Intent(this, DanceActivity::class.java)
                    intent.putExtra("videoPath", files[0].absolutePath)
                    startActivity(intent)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DanceApp::MainActivity"
    }
}