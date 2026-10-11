package com.metaclipthat

/** One encoded H.264 video frame. CSD bytes are prepended to keyframes at ingest. */
data class VideoFrame(
    val data: ByteArray,
    val ptsUs: Long,
    val keyFrame: Boolean
)

/** One encoded AAC audio frame. */
data class AudioFrame(
    val data: ByteArray,
    val ptsUs: Long
)

/**
 * In-memory ring buffer of encoded video frames.
 *
 * Frames older than [windowUs] are discarded automatically, so memory stays
 * bounded no matter how long rolling runs. The buffer is also capped by
 * [maxBytes] as a safety net. The first retained frame is always a keyframe,
 * so any snapshot can be muxed into a valid MP4 without re-encoding.
 */
class VideoRingBuffer(
    private val windowUs: Long,
    private val maxBytes: Long = 400L * 1024L * 1024L
) {
    private val frames = ArrayDeque<VideoFrame>()
    private var bytes = 0L

    @Synchronized
    fun add(frame: VideoFrame) {
        frames.addLast(frame)
        bytes += frame.data.size
        evict(frame.ptsUs)
    }

    private fun evict(newestPtsUs: Long) {
        val cutoff = newestPtsUs - windowUs
        while (frames.size > 1 &&
            (frames.first().ptsUs < cutoff || bytes > maxBytes)
        ) {
            bytes -= frames.removeFirst().data.size
        }
        // A clip must start on a keyframe.
        while (frames.size > 1 && !frames.first().keyFrame) {
            bytes -= frames.removeFirst().data.size
        }
    }

    /**
     * Returns the tail of the buffer covering roughly [durationUs], starting at
     * the latest keyframe at or before the requested start so the segment is
     * decodable. If less than [durationUs] is buffered, returns everything.
     * The buffer itself is NOT modified — rolling continues uninterrupted.
     */
    @Synchronized
    fun snapshotTail(durationUs: Long): List<VideoFrame> {
        if (frames.isEmpty()) return emptyList()
        val list = frames.toList()
        val endUs = list.last().ptsUs
        val wantStartUs = endUs - durationUs
        var startIdx = 0
        for (i in list.indices) {
            val f = list[i]
            if (f.keyFrame && f.ptsUs <= wantStartUs) {
                startIdx = i
            } else if (f.ptsUs > wantStartUs) {
                break
            }
        }
        return list.subList(startIdx, list.size)
    }

    @Synchronized fun isEmpty(): Boolean = frames.isEmpty()

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
    }
}

/** In-memory ring buffer of encoded audio frames, evicted by the same time window. */
class AudioRingBuffer(private val windowUs: Long) {
    private val frames = ArrayDeque<AudioFrame>()

    @Synchronized
    fun add(frame: AudioFrame) {
        frames.addLast(frame)
        val cutoff = frame.ptsUs - windowUs
        while (frames.size > 1 && frames.first().ptsUs < cutoff) {
            frames.removeFirst()
        }
    }

    @Synchronized
    fun range(fromUs: Long, toUs: Long): List<AudioFrame> =
        frames.filter { it.ptsUs in fromUs..toUs }

    @Synchronized fun isEmpty(): Boolean = frames.isEmpty()

    @Synchronized fun clear() = frames.clear()
}
