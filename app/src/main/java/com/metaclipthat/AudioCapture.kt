package com.metaclipthat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Captures internal (AudioPlaybackCapture, API 29+) and/or microphone audio,
 * mixes them into one mono stream, encodes to AAC live, and feeds the encoded
 * frames into an [AudioRingBuffer] with presentation timestamps matching the
 * video clock, so clips can be muxed with A/V sync.
 */
class AudioCapture(
    private val context: Context,
    private val projection: MediaProjection?,
    private val useInternal: Boolean,
    private val useMic: Boolean,
    private val ring: AudioRingBuffer,
    private val onFormat: (MediaFormat) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private var thread: Thread? = null
    @Volatile private var quit = false
    private var encoder: MediaCodec? = null

    fun start() {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBuf * 4).coerceAtLeast(8192)

        val internal = if (useInternal && projection != null) {
            try {
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (e: Exception) {
                Log.w("MetaClipThat", "internal audio unavailable", e)
                null
            }
        } else null

        val mic = if (useMic) {
            try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (e: Exception) {
                Log.w("MetaClipThat", "microphone unavailable", e)
                null
            }
        } else null

        if (internal == null && mic == null) return

        val enc = MediaCodec.createEncoderByType(MIME)
        val ef = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc
        onFormat(enc.outputFormat)

        quit = false
        thread = Thread({ loop(internal, mic, enc) }, "AudioCapture").also { it.start() }
    }

    private fun loop(internal: AudioRecord?, mic: AudioRecord?, enc: MediaCodec) {
        val n = 2048
        val a = ShortArray(n)
        val b = ShortArray(n)
        val mixed = ShortArray(n)
        var written = 0L
        val info = MediaCodec.BufferInfo()
        try { internal?.startRecording() } catch (_: Exception) {}
        try { mic?.startRecording() } catch (_: Exception) {}

        while (!quit) {
            val ra = try { internal?.read(a, 0, n) ?: 0 } catch (_: Exception) { 0 }
            val rb = try { mic?.read(b, 0, n) ?: 0 } catch (_: Exception) { 0 }
            val count = maxOf(ra, rb)
            if (count > 0) {
                for (i in 0 until count) {
                    val x = if (i < ra) a[i].toInt() else 0
                    val y = if (i < rb) b[i].toInt() else 0
                    mixed[i] = (x + y).coerceIn(-32768, 32767).toShort()
                }
                var off = 0
                while (off < count && !quit) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = enc.getInputBuffer(idx)!!
                        ib.clear()
                        val chunk = minOf(count - off, ib.remaining() / 2)
                        for (i in 0 until chunk) ib.putShort(mixed[off + i])
                        enc.queueInputBuffer(idx, 0, chunk * 2, written * 1_000_000L / SAMPLE_RATE, 0)
                        written += chunk
                        off += chunk
                    }
                }
                drain(enc, info, end = false)
            } else {
                try { Thread.sleep(10) } catch (_: Exception) {}
            }
        }

        // Flush remaining encoded data.
        try {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                enc.queueInputBuffer(
                    idx, 0, 0,
                    written * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(enc, info, end = true)
        } catch (_: Exception) {}

        try { internal?.stop() } catch (_: Exception) {}
        try { internal?.release() } catch (_: Exception) {}
        try { mic?.stop() } catch (_: Exception) {}
        try { mic?.release() } catch (_: Exception) {}
    }

    private fun drain(enc: MediaCodec, info: MediaCodec.BufferInfo, end: Boolean) {
        while (true) {
            val o = try {
                enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            } catch (_: Exception) {
                break
            }
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (o < 0) break
            try {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val ob = enc.getOutputBuffer(o)!!
                    val d = ByteArray(info.size)
                    ob.get(d)
                    ring.add(AudioFrame(d, info.presentationTimeUs))
                }
            } finally {
                enc.releaseOutputBuffer(o, false)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    fun stop() {
        quit = true
        try { thread?.join(3000) } catch (_: Exception) {}
        thread = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
    }
}
