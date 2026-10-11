package com.metaclipthat

import android.content.Context

/** Simple SharedPreferences wrapper for all user settings. */
object Prefs {
    private const val FILE = "metaclipthat"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Seconds of footage saved per clip. Range 1..300. */
    fun clipSeconds(c: Context): Int = p(c).getInt("clip_seconds", 30).coerceIn(1, 300)
    fun setClipSeconds(c: Context, v: Int) {
        p(c).edit().putInt("clip_seconds", v.coerceIn(1, 300)).apply()
    }

    /** Rolling buffer length in minutes. Hard max 5. */
    fun bufferMinutes(c: Context): Int = p(c).getInt("buffer_minutes", 2).coerceIn(1, 5)
    fun setBufferMinutes(c: Context, v: Int) {
        p(c).edit().putInt("buffer_minutes", v.coerceIn(1, 5)).apply()
    }

    /** Capture frame rate. 0 = never set (use detected refresh rate). Min 10. */
    fun fps(c: Context): Int = p(c).getInt("fps", 0)
    fun setFps(c: Context, v: Int) {
        p(c).edit().putInt("fps", v.coerceAtLeast(10)).apply()
    }

    fun internalAudio(c: Context): Boolean = p(c).getBoolean("internal_audio", true)
    fun setInternalAudio(c: Context, v: Boolean) {
        p(c).edit().putBoolean("internal_audio", v).apply()
    }

    fun micAudio(c: Context): Boolean = p(c).getBoolean("mic_audio", false)
    fun setMicAudio(c: Context, v: Boolean) {
        p(c).edit().putBoolean("mic_audio", v).apply()
    }
}
