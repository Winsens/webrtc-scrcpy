package com.webrtc.scrcpy.core.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.IOException

/**
 * Low-latency hardware screen stream encoder controller
 */
class ScreenStreamEncoder {
    private val TAG = "ScreenStreamEncoder"

    private var mediaCodec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    var isRunning = false
        private set

    @Throws(IOException::class)
    fun initCodec(width: Int, height: Int, bitRate: Int, fps: Int): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 2-second keyframe interval fallback
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            // Restrict to Baseline Profile for broad hardware decoding compatibility in modern browsers
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

            // Ultra-low latency options (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        this.mediaCodec = codec
        this.inputSurface = surface
        this.isRunning = true
        Log.i(TAG, "MediaCodec initialized: ${width}x${height} @ ${bitRate / 1000}kbps, ${fps}fps")
        return surface
    }

    /**
     * Request an immediate IDR keyframe in response to WebRTC RTCP PLI/FIR feedback
     */
    fun requestKeyFrame() {
        val codec = mediaCodec
        if (codec != null && isRunning) {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            Log.d(TAG, "Requested immediate keyframe (IDR)")
        }
    }

    /**
     * Dynamically adjust encoder bitrate in response to WebRTC congestion control feedback
     */
    fun updateBitrate(targetBitrateBps: Int) {
        val codec = mediaCodec
        if (codec != null && isRunning) {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBitrateBps)
            }
            codec.setParameters(params)
            Log.d(TAG, "Updated encoder bitrate: ${targetBitrateBps / 1000} kbps")
        }
    }

    fun release() {
        isRunning = false
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec: ${e.message}")
        } finally {
            mediaCodec = null
            inputSurface = null
        }
    }
}

