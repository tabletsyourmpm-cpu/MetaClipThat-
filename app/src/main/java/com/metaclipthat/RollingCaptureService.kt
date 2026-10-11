package com.metaclipthat

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Foreground service that keeps a rolling in-memory buffer of encoded H.264
 * (+ AAC) frames via MediaProjection.
 *
 * - Clipping (ACTION_CLIP) snapshots the tail of the ring buffer and muxes it
 *   to MP4 *without* touching the buffer: rolling continues and the clipped
 *   segment stays available for another clip right after.
 * - If the MediaProjection session dies unexpectedly, whatever is left in the
 *   buffer is auto-clipped (up to the configured clip length) and THEN a loud
 *   alert sound tells the user to restart rolling — the last moments are never
 *   lost.
 */
class RollingCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.metaclipthat.action.START_ROLLING"
        const val ACTION_STOP = "com.metaclipthat.action.STOP_ROLLING"
        const val ACTION_CLIP = "com.metaclipthat.action.CLIP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Broadcast (explicit, own package) whenever status changes for the UI. */
        const val STATUS_CHANGED = "com.metaclipthat.action.STATUS_CHANGED"

        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "rolling"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val VIDEO_BITRATE = 8_000_000
        private const val KEYFRAME_INTERVAL_S = 2

        @Volatile var running = false
            private set
        @Volatile var startedAtMs = 0L
            private set
        @Volatile var lastClipName: String? = null
            private set
        @Volatile var statusDetail: String = "Idle"
            private set

        fun poke(context: android.content.Context) {
            context.sendBroadcast(Intent(STATUS_CHANGED).setPackage(context.packageName))
        }
    }

    private val clipExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var videoFormat: MediaFormat? = null
    private var codecConfig: ByteArray? = null

    private var drainThread: Thread? = null
    @Volatile private var drainQuit = false

    private var videoRing: VideoRingBuffer? = null
    private var audioRing: AudioRingBuffer = AudioRingBuffer(0L)
    private var audioFormat: MediaFormat? = null
    private var audioCapture: AudioCapture? = null

    @Volatile private var userStopped = false
    private val clipLock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Runs on the main thread (registered with mainHandler).
            if (!running) return
            if (userStopped) return // we initiated this stop; shutdown is handled.
            Log.w("MetaClipThat", "MediaProjection died unexpectedly — auto-clipping buffer")
            clipExecutor.execute {
                stopDrainAndEncoder()
                // 1. Preserve the last moments first…
                doClip(Prefs.clipSeconds(this@RollingCaptureService), auto = true)
                // 2. …then alert loudly so the user restarts rolling.
                AlertSound.play(this@RollingCaptureService)
                mainHandler.post {
                    Toast.makeText(
                        this@RollingCaptureService,
                        "Screen share ended — final clip saved",
                        Toast.LENGTH_LONG
                    ).show()
                }
                shutdown()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (!running && code == Activity.RESULT_OK && data != null) {
                    startRolling(code, data)
                }
            }
            ACTION_STOP -> {
                userStopped = true
                clipExecutor.execute {
                    stopDrainAndEncoder()
                    shutdown()
                }
            }
            ACTION_CLIP -> {
                if (running) {
                    clipExecutor.execute {
                        doClip(Prefs.clipSeconds(this), auto = false)
                    }
                }
            }
        }
        return START_STICKY
    }

    // ---------- rolling ----------

    private fun startRolling(resultCode: Int, data: Intent) {
        userStopped = false
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr.getMediaProjection(resultCode, data)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        val (width, height) = captureSize()
        val fps = Prefs.fps(this).takeIf { it >= 10 }
            ?: ShizukuHelper.maxFps(this).coerceIn(10, 240)
        val bufferMinutes = Prefs.bufferMinutes(this)
        val windowUs = bufferMinutes * 60L * 1_000_000L

        startForeground(
            NOTIF_ID,
            buildNotification("Rolling capture active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        videoRing = VideoRingBuffer(windowUs = windowUs)
        audioRing = AudioRingBuffer(windowUs = windowUs)

        val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec

        virtualDisplay = projection.createVirtualDisplay(
            "MetaClipThat",
            width, height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        drainQuit = false
        drainThread = Thread({ drainLoop(codec) }, "VideoDrain").also { it.start() }

        val wantInternal = Prefs.internalAudio(this)
        val wantMic = Prefs.micAudio(this)
        if (wantInternal || wantMic) {
            audioFormat = null
            audioCapture = AudioCapture(
                this, projection, wantInternal, wantMic, audioRing
            ) { fmt -> audioFormat = fmt }.also { it.start() }
        }

        running = true
        startedAtMs = System.currentTimeMillis()
        statusDetail = "Buffer: last $bufferMinutes min • ${width}x$height @ $fps fps"
        updateNotification("Rolling — buffering last $bufferMinutes min")
        poke(this)
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (!drainQuit) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 2000)
            } catch (e: Exception) {
                Log.w("MetaClipThat", "video dequeue failed", e)
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoFormat = codec.outputFormat
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output yet; loop again.
                }
                idx >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            val buf = codec.getOutputBuffer(idx)!!
                            codecConfig = ByteArray(info.size).also { buf.get(it) }
                        } else if (info.size > 0) {
                            val buf = codec.getOutputBuffer(idx)!!
                            val data = ByteArray(info.size).also { buf.get(it) }
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val csd = codecConfig
                            val framed = if (key && csd != null) csd + data else data
                            videoRing?.add(VideoFrame(framed, info.presentationTimeUs, key))
                        }
                    } finally {
                        codec.releaseOutputBuffer(idx, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    // ---------- clipping ----------

    private fun doClip(seconds: Int, auto: Boolean) {
        synchronized(clipLock) {
            val ring = videoRing
            val format = videoFormat
            if (ring == null || format == null || ring.isEmpty()) {
                mainHandler.post {
                    Toast.makeText(this, "Nothing buffered yet — wait a moment", Toast.LENGTH_SHORT)
                        .show()
                }
                return
            }
            val frames = ring.snapshotTail(seconds * 1_000_000L)
            if (frames.isEmpty()) return
            val fromUs = frames.first().ptsUs
            val toUs = frames.last().ptsUs
            val aFrames = audioRing.range(fromUs, toUs)
            val aFormat = audioFormat?.takeIf { aFrames.isNotEmpty() }

            // NOTE: the ring buffer is intentionally NOT cleared here — rolling
            // continues and the clipped segment stays buffered for the next clip.
            val name = try {
                ClipSaver.save(this, frames, format, aFrames, aFormat, auto)
            } catch (e: Exception) {
                Log.e("MetaClipThat", "clip failed", e)
                null
            }
            if (name != null) {
                lastClipName = name
                mainHandler.post {
                    Toast.makeText(
                        this,
                        if (auto) "Final clip saved: $name" else "Clip saved: $name",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                mainHandler.post {
                    Toast.makeText(this, "Clip failed — see log", Toast.LENGTH_LONG).show()
                }
            }
            poke(this)
        }
    }

    // ---------- shutdown ----------

    private fun stopDrainAndEncoder() {
        drainQuit = true
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            drainThread?.join(3000)
        } catch (_: Exception) {
        }
        drainThread = null
    }

    private fun shutdown() {
        if (!running && mediaProjection == null) return
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { audioCapture?.stop() } catch (_: Exception) {}
        audioCapture = null
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        videoFormat = null
        codecConfig = null
        videoRing?.clear()
        audioRing.clear()
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        if (userStopped) {
            // We initiated the stop; make sure the projection actually ends.
            try { mediaProjection?.stop() } catch (_: Exception) {}
        }
        mediaProjection = null
        statusDetail = "Idle"
        poke(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        clipExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------- helpers ----------

    private fun captureSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val bounds: Rect = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            Rect(0, 0, p.x, p.y)
        }
        // H.264 encoders require even dimensions.
        return ((bounds.width() / 2) * 2) to ((bounds.height() / 2) * 2)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rolling capture", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meta Clip That!")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_clip)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
