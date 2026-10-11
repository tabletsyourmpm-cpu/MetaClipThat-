package com.metaclipthat

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Muxes a snapshot of buffered frames into an MP4 and stores it in
 * Movies/MetaClipThat via MediaStore. No re-encoding: frames go straight
 * from the ring buffer into the muxer in presentation-timestamp order.
 */
object ClipSaver {

    fun save(
        context: Context,
        video: List<VideoFrame>,
        videoFormat: MediaFormat,
        audio: List<AudioFrame>,
        audioFormat: MediaFormat?,
        auto: Boolean
    ): String? {
        if (video.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "MetaClipThat_${stamp}${if (auto) "_auto" else ""}.mp4"

        val tmp = File(context.cacheDir, "clip_tmp.mp4")
        if (tmp.exists()) tmp.delete()

        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoTrack = muxer.addTrack(videoFormat)
        val audioTrack = if (audioFormat != null && audio.isNotEmpty()) {
            muxer.addTrack(audioFormat)
        } else -1
        muxer.start()

        // Merge video + audio by presentation timestamp.
        val info = MediaCodec.BufferInfo()
        var vi = 0
        var ai = 0
        while (vi < video.size || (audioTrack != -1 && ai < audio.size)) {
            val vf = video.getOrNull(vi)
            val af = if (audioTrack != -1) audio.getOrNull(ai) else null
            if (af != null && (vf == null || af.ptsUs < vf.ptsUs)) {
                info.set(0, af.data.size, af.ptsUs, 0)
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(af.data), info)
                ai++
            } else if (vf != null) {
                val flags = if (vf.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, vf.data.size, vf.ptsUs, flags)
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(vf.data), info)
                vi++
            } else {
                break
            }
        }
        muxer.stop()
        muxer.release()

        // Publish via MediaStore (no storage permission needed for our own entry).
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MetaClipThat")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: run {
            Log.e("MetaClipThat", "MediaStore insert failed")
            return null
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(tmp).use { input -> input.copyTo(out) }
        }
        tmp.delete()
        return name
    }
}
