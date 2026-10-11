package com.metaclipthat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

/**
 * The Medal.tv-style home screen: clipping first, gamer-oriented.
 * Big status card, giant CLIP NOW button, start/stop rolling, Shizuku status.
 */
class HomeFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvLastClip: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var btnStartStop: MaterialButton

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus = view.findViewById(R.id.tv_status)
        tvStatusDetail = view.findViewById(R.id.tv_status_detail)
        tvLastClip = view.findViewById(R.id.tv_last_clip)
        tvShizuku = view.findViewById(R.id.tv_shizuku_status)
        btnStartStop = view.findViewById(R.id.btn_start_stop)

        view.findViewById<MaterialButton>(R.id.btn_clip_now).setOnClickListener {
            (activity as? MainActivity)?.clipNow()
        }
        btnStartStop.setOnClickListener {
            val act = activity as? MainActivity ?: return@setOnClickListener
            if (RollingCaptureService.running) act.stopRolling() else act.startRolling()
        }
        view.findViewById<MaterialButton>(R.id.btn_shizuku).setOnClickListener {
            activity?.let { ShizukuHelper.requestPermission(it) }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(),
            statusReceiver,
            IntentFilter(RollingCaptureService.STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onPause() {
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun refresh() {
        if (!isAdded) return
        val ctx = requireContext()
        if (RollingCaptureService.running) {
            tvStatus.text = getString(R.string.rolling)
            tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.clip_red))
            tvStatusDetail.text = RollingCaptureService.statusDetail
            btnStartStop.text = getString(R.string.stop_rolling)
        } else {
            tvStatus.text = getString(R.string.not_recording)
            tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.charcoal2))
            tvStatusDetail.text =
                "Start rolling to keep a ${Prefs.bufferMinutes(ctx)}-minute buffer"
            btnStartStop.text = getString(R.string.start_rolling)
        }
        tvLastClip.text = RollingCaptureService.lastClipName?.let { "Last clip: $it" }
            ?: getString(R.string.no_clips_yet)
        tvShizuku.text = when {
            !ShizukuHelper.installed(ctx) -> "Shizuku: not installed"
            !ShizukuHelper.binderAlive() -> "Shizuku: installed, not running"
            ShizukuHelper.granted() -> "Shizuku: paired"
            else -> "Shizuku: permission needed"
        }
    }
}
