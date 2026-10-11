package com.metaclipthat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Single activity hosting the Medal-style Clip screen and the
 * KeyMapper-inspired Settings screen, with a bottom nav like the
 * MelonLoader reference.
 */
class MainActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, RollingCaptureService::class.java)
                .setAction(RollingCaptureService.ACTION_START)
                .putExtra(RollingCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(RollingCaptureService.EXTRA_RESULT_DATA, result.data)
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whether granted or not, try the projection consent next.
        launchProjectionConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, HomeFragment())
            }
        }

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_clip -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, HomeFragment())
                    }
                    true
                }
                R.id.nav_settings -> {
                    supportFragmentManager.commit {
                        replace(R.id.fragment_container, SettingsFragment())
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Starts rolling: notification permission (33+) then MediaProjection consent. */
    fun startRolling() {
        if (RollingCaptureService.running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    fun stopRolling() {
        startService(
            Intent(this, RollingCaptureService::class.java)
                .setAction(RollingCaptureService.ACTION_STOP)
        )
    }

    /** In-app clip trigger (the big button); same path as the KeyMapper broadcast. */
    fun clipNow() {
        if (!RollingCaptureService.running) {
            Toast.makeText(this, "Start rolling first, then clip!", Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, RollingCaptureService::class.java)
                .setAction(RollingCaptureService.ACTION_CLIP)
        )
    }
}
