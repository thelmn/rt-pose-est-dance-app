package com.pause.dance

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView


class MainActivity : AppCompatActivity() {

    private lateinit var challengeListView: LinearLayout
    private lateinit var emptyStateView: TextView

    init {
        Log.i(TAG, "Instantiated new " + this.javaClass)
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "called onCreate")
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        challengeListView = findViewById(R.id.activity_main_challenge_list)
        emptyStateView = findViewById(R.id.activity_main_empty_state)

        val challenges = ChallengeRepository(applicationContext).getChallenges()
        renderChallenges(challenges)
    }

    private fun renderChallenges(challenges: List<Challenge>) {
        challengeListView.removeAllViews()
        emptyStateView.visibility = if (challenges.isEmpty()) View.VISIBLE else View.GONE

        for (challenge in challenges) {
            challengeListView.addView(createChallengeCard(challenge))
        }
    }

    private fun createChallengeCard(challenge: Challenge): MaterialCardView {
        val card = MaterialCardView(this)
        card.isClickable = true
        card.isFocusable = true
        card.minimumHeight = dp(80)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val thumbnail = AppCompatImageView(this)
        thumbnail.layoutParams = LinearLayout.LayoutParams(dp(150), dp(180))
        thumbnail.contentDescription = "${challenge.title} thumbnail"
        thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        Glide.with(this).load(challenge.videoFile).into(thumbnail)

        val title = TextView(this)
        title.text = challenge.title
        title.maxWidth = dp(150)
        title.setPadding(dp(8), dp(8), dp(8), dp(2))

        val difficulty = TextView(this)
        difficulty.text = challenge.difficulty.displayName
        difficulty.maxWidth = dp(150)
        difficulty.setPadding(dp(8), 0, dp(8), dp(8))

        content.addView(thumbnail)
        content.addView(title)
        content.addView(difficulty)
        card.addView(content)

        card.setOnClickListener {
            val intent = Intent(this, DanceActivity::class.java)
            intent.putExtra(DanceActivity.EXTRA_CHALLENGE_ID, challenge.id)
            intent.putExtra(DanceActivity.EXTRA_VIDEO_PATH, challenge.videoFile.absolutePath)
            startActivity(intent)
        }

        return card
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "DanceApp::MainActivity"
    }
}
