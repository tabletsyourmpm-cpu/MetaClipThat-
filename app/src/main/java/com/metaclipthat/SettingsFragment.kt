package com.metaclipthat

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * KeyMapper-inspired settings surface: grouped cards, each row a title +
 * summary with a trailing control (textbox, slider, switch), plus Shizuku
 * pairing and the KeyMapper trigger guide.
 */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        // --- Clip length textbox (1–300 s) ---
        val etClip = view.findViewById<TextInputEditText>(R.id.et_clip_seconds)
        etClip.setText(Prefs.clipSeconds(ctx).toString())
        etClip.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = etClip.text.toString().toIntOrNull()?.coerceIn(1, 300) ?: 30
                Prefs.setClipSeconds(ctx, v)
                etClip.setText(v.toString())
            }
        }

        // --- Rolling buffer slider (1–5 min) ---
        val sliderBuffer = view.findViewById<Slider>(R.id.slider_buffer)
        val tvBuffer = view.findViewById<TextView>(R.id.tv_buffer_value)
        sliderBuffer.value = Prefs.bufferMinutes(ctx).toFloat()
        tvBuffer.text = "${Prefs.bufferMinutes(ctx)} min"
        sliderBuffer.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val m = value.toInt().coerceIn(1, 5)
                Prefs.setBufferMinutes(ctx, m)
                tvBuffer.text = "$m min"
            }
        }

        // --- FPS slider (10 .. detected refresh rate) ---
        val sliderFps = view.findViewById<Slider>(R.id.slider_fps)
        val tvFps = view.findViewById<TextView>(R.id.tv_fps_value)
        val savedFps = Prefs.fps(ctx)
        sliderFps.value = if (savedFps >= 10) savedFps.toFloat().coerceAtMost(120f) else 60f
        tvFps.text = "${sliderFps.value.toInt()} fps"
        sliderFps.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                Prefs.setFps(ctx, value.toInt())
                tvFps.text = "${value.toInt()} fps"
            }
        }
        // Detect the real cap off the UI thread: Quest via Shizuku, else display rate.
        Thread {
            val max = ShizukuHelper.maxFps(ctx)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                sliderFps.valueTo = max.toFloat()
                view.findViewById<TextView>(R.id.tv_fps_summary).text =
                    "Capture frame rate (10–$max fps)"
                val cur = Prefs.fps(ctx).let { if (it in 10..max) it else max }
                sliderFps.value = cur.toFloat()
                Prefs.setFps(ctx, cur)
                tvFps.text = "$cur fps"
            }
        }.start()

        // --- Audio toggles ---
        val swInternal = view.findViewById<SwitchMaterial>(R.id.sw_internal_audio)
        swInternal.isChecked = Prefs.internalAudio(ctx)
        swInternal.setOnCheckedChangeListener { _, checked ->
            Prefs.setInternalAudio(ctx, checked)
        }

        val swMic = view.findViewById<SwitchMaterial>(R.id.sw_mic_audio)
        swMic.isChecked = Prefs.micAudio(ctx)
        swMic.setOnCheckedChangeListener { _, checked ->
            Prefs.setMicAudio(ctx, checked)
            if (checked) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            }
        }

        // --- Shizuku ---
        view.findViewById<TextView>(R.id.tv_shizuku_detail).text = shizukuDetailText()
        view.findViewById<MaterialButton>(R.id.btn_shizuku_setup).setOnClickListener {
            if (!ShizukuHelper.granted()) {
                ShizukuHelper.requestPermission(requireActivity())
            } else {
                Toast.makeText(ctx, "Shizuku already paired", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_open_shizuku).setOnClickListener {
            val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) {
                startActivity(launch)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app")))
            }
        }
    }

    private fun shizukuDetailText(): String {
        val ctx = requireContext()
        return when {
            !ShizukuHelper.installed(ctx) ->
                "Shizuku app is not installed. Install it (see the README), open it, and pair via wireless debugging — no PC needed after the first pairing."
            !ShizukuHelper.binderAlive() ->
                "Shizuku is installed but not running. Open the Shizuku app and start it (wireless debugging pairing), then come back here."
            ShizukuHelper.granted() ->
                "Shizuku is paired. Meta Clip That! can read the headset refresh rate to cap the FPS setting correctly."
            else ->
                "Shizuku is running. Press the setup button to grant Meta Clip That! permission."
        }
    }
}
