package com.metaclipthat

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/** Plays a loud system alarm/notification tone, e.g. when rolling dies unexpectedly. */
object AlertSound {
    fun play(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= 28) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        } catch (_: Exception) {
            // Never crash the shutdown path because of a sound.
        }
    }
}
