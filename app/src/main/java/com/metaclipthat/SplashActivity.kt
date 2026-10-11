package com.metaclipthat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/**
 * Two-stage splash: the FluxTech studio logo (~1.2 s), crossfade to the
 * "M Clip that!" logo (~1.2 s), then into the main activity.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val STAGE_MS = 1200L
        private const val FADE_MS = 450L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val stage1 = findViewById<ImageView>(R.id.splash_stage1)

        // Stage 1 -> stage 2: fade the FluxTech logo out, revealing the app logo.
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            stage1.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction { stage1.visibility = View.GONE }
                .start()
        }, STAGE_MS)

        // Stage 2 -> main: after the app logo has had its moment, move on.
        handler.postDelayed({ launchMain() }, STAGE_MS + FADE_MS + STAGE_MS)
    }

    private fun launchMain() {
        if (launched || isFinishing) return
        launched = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
