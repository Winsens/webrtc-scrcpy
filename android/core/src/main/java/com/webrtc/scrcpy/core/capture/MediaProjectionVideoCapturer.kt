package com.webrtc.scrcpy.core.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Screen capturer based on MediaProjection and WebRTC SurfaceTextureHelper
 */
class MediaProjectionVideoCapturer(
    private val mediaProjectionPermissionResultData: Intent,
    private val mediaProjectionCallback: MediaProjection.Callback? = null
) : VideoCapturer {
    private val TAG = "MediaProjectionCapturer"

    private var context: Context? = null
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var width = 1080
    private var height = 2400
    private var fps = 30
    private var isDisposed = false

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.context = applicationContext
        this.capturerObserver = capturerObserver
        this.mediaProjectionManager = applicationContext?.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    private var frameCount = 0L

    @Volatile
    private var isCapturing = false

    @Volatile
    private var lastFrameTimeMs = 0L

    private var captureHandler: Handler? = null

    private val idleKeepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isCapturing || isDisposed) return
            val elapsed = SystemClock.uptimeMillis() - lastFrameTimeMs
            if (lastFrameTimeMs > 0L && elapsed >= 450L) {
                surfaceTextureHelper?.forceFrame()
            }
            captureHandler?.postDelayed(this, 500L)
        }
    }

    /**
     * Re-emit the latest texture frame for IDR keyframe encoding when a browser connects or refreshes, avoiding prolonged 0 FPS on static screens
     */
    fun requestRefreshFrame() {
        val helper = surfaceTextureHelper ?: return
        val h = captureHandler ?: return
        if (!isCapturing || isDisposed) return
        h.post { helper.forceFrame() }
        h.postDelayed({ if (isCapturing && !isDisposed) helper.forceFrame() }, 120L)
        h.postDelayed({ if (isCapturing && !isDisposed) helper.forceFrame() }, 350L)
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        this.width = width
        this.height = height
        this.fps = framerate

        surfaceTextureHelper?.setTextureSize(width, height)

        val mgr = mediaProjectionManager ?: run {
            Log.e(TAG, "MediaProjectionManager is not ready")
            return
        }

        mediaProjection = mgr.getMediaProjection(android.app.Activity.RESULT_OK, mediaProjectionPermissionResultData)
        val handler = Handler(surfaceTextureHelper!!.handler.looper)
        this.captureHandler = handler
        this.isCapturing = true
        this.lastFrameTimeMs = SystemClock.uptimeMillis()

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                mediaProjectionCallback?.onStop()
            }
        }, handler)

        val surface = android.view.Surface(surfaceTextureHelper!!.surfaceTexture)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "webrtc-scrcpy-display",
            width, height, 400,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            handler
        )

        surfaceTextureHelper?.startListening { videoFrame ->
            frameCount++
            lastFrameTimeMs = SystemClock.uptimeMillis()
            if (frameCount == 1L || frameCount % 300L == 0L) {
                Log.d(TAG, "Captured screen frame #$frameCount: ${videoFrame.rotatedWidth}x${videoFrame.rotatedHeight}")
            }
            capturerObserver?.onFrameCaptured(videoFrame)
        }
        handler.removeCallbacks(idleKeepAliveRunnable)
        handler.postDelayed(idleKeepAliveRunnable, 500L)
        Log.i(TAG, "Started VirtualDisplay screen capture: ${width}x${height} @ ${framerate}fps")
    }

    override fun stopCapture() {
        isCapturing = false
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler = null
        surfaceTextureHelper?.stopListening()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        stopCapture()
        startCapture(width, height, framerate)
    }

    override fun dispose() {
        isDisposed = true
        stopCapture()
    }

    override fun isScreencast(): Boolean = true
}

