package com.metaclipthat

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import rikka.shizuku.Shizuku

/**
 * Shizuku integration. Used to read the headset refresh rate on Quest via
 * `getprop debug.oculus.refreshRate` so the FPS setting can cap at the real
 * display rate. Falls back to the display's reported refresh rate.
 *
 * The Shizuku provider artifact auto-initializes the client; no extra code needed.
 */
object ShizukuHelper {
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 1001

    fun installed(c: Context): Boolean = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun requestPermission(activity: Activity) {
        if (!binderAlive()) return
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQ_CODE)
    }

    /**
     * Quest exposes its refresh rate via this system property (needs Shizuku shell).
     * Note: Shizuku deprecated newProcess in 13.1.x (in favor of UserService) and
     * made it private in 13.1.5, so we pin the Shizuku artifacts to 13.1.0 where
     * the 3-arg overload is still public. Revisit if the API removes it.
     */
    @Suppress("DEPRECATION")
    fun questRefreshRate(): Int? {
        if (!binderAlive() || !granted()) return null
        return try {
            val process = Shizuku.newProcess(
                arrayOf("getprop", "debug.oculus.refreshRate"), null, null
            )
            val line = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            line?.toFloatOrNull()?.toInt()?.takeIf { it in 10..240 }
        } catch (_: Exception) {
            null
        }
    }

    /** Max selectable FPS: Quest property via Shizuku, else the display's refresh rate. */
    fun maxFps(context: Context): Int {
        questRefreshRate()?.let { return it }
        val rate = if (Build.VERSION.SDK_INT >= 30) {
            context.display?.refreshRate
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.refreshRate
        }
        return (rate?.toInt() ?: 60).coerceIn(10, 240)
    }
}
