package com.metaclipthat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Exported clip trigger. KeyMapper (expert mode "Run shell command" action) fires:
 *
 *     am broadcast -a com.metaclipthat.action.CLIP_NOW -p com.metaclipthat
 * (-p required: Android 8+ never delivers implicit broadcasts to
 * manifest-declared receivers)
 *
 * Clipping never interrupts rolling: the service keeps the ring buffer running
 * and the clipped segment stays buffered so the user can clip again immediately.
 */
class ClipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLIP_NOW) return
        if (RollingCaptureService.running) {
            // The foreground service is already running, so plain startService
            // just delivers the clip command — no background-start issues.
            context.startService(
                Intent(context, RollingCaptureService::class.java)
                    .setAction(RollingCaptureService.ACTION_CLIP)
            )
        } else {
            Toast.makeText(
                context,
                "Meta Clip That! is not rolling — start rolling first",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_CLIP_NOW = "com.metaclipthat.action.CLIP_NOW"
    }
}
