package com.webrtc.scrcpy.core.capture

import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * Forwards captured frames with strictly increasing timestamps.
 *
 * SurfaceTextureHelper.forceFrame() re-delivers the last texture with that texture's original
 * timestamp, and WebRTC's encoder drops any frame that is not newer than the previous one. Without
 * re-stamping, the refresh and idle keep-alive frames are all discarded and a static screen sends
 * no video at all. Each frame is stamped with its real arrival time (monotonic clock), because the
 * encoder's frame-rate limiter also drops frames whose timestamps are unrealistically close.
 */
class MonotonicFrameForwarder(private val observer: () -> CapturerObserver?) {
    private var lastTimestampNs = 0L

    /** Must be called on the SurfaceTextureHelper thread (from its listener). */
    fun forward(frame: VideoFrame) {
        val target = observer() ?: return
        val timestampNs = maxOf(System.nanoTime(), lastTimestampNs + MIN_STEP_NS)
        lastTimestampNs = timestampNs
        // Same buffer, new timestamp. The new frame holds its own reference to the buffer.
        frame.buffer.retain()
        val restamped = VideoFrame(frame.buffer, frame.rotation, timestampNs)
        try {
            target.onFrameCaptured(restamped)
        } finally {
            restamped.release()
        }
    }

    private companion object {
        const val MIN_STEP_NS = 1_000_000L // 1 ms
    }
}
