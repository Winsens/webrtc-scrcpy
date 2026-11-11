package com.webrtc.scrcpy.core.capture

import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import java.lang.reflect.Method

/**
 * Silent screen capturer using SurfaceControl hidden APIs (for AOSP cloud devices / Root / Shell app_process)
 * Requires zero permission dialogs and mirrors the system main display layer directly
 */
class SurfaceControlCapturer(
    private val deviceWidth: Int = 1080,
    private val deviceHeight: Int = 1920
) : VideoCapturer {
    private val TAG = "SurfaceControlCapturer"

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var displayToken: IBinder? = null

    private var createDisplayMethod: Method? = null
    private var openTransactionMethod: Method? = null
    private var closeTransactionMethod: Method? = null
    private var setDisplaySurfaceMethod: Method? = null
    private var setDisplayProjectionMethod: Method? = null
    private var setDisplayLayerStackMethod: Method? = null
    private var setDisplaySizeMethod: Method? = null
    private var destroyDisplayMethod: Method? = null

    init {
        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            createDisplayMethod = scClass.getDeclaredMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
            openTransactionMethod = scClass.getDeclaredMethod("openTransaction")
            closeTransactionMethod = scClass.getDeclaredMethod("closeTransaction")
            setDisplaySurfaceMethod = scClass.getDeclaredMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
            setDisplayLayerStackMethod = scClass.getDeclaredMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
            setDisplaySizeMethod = scClass.getDeclaredMethod("setDisplaySize", IBinder::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setDisplayProjectionMethod = scClass.getDeclaredMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
            destroyDisplayMethod = scClass.getDeclaredMethod("destroyDisplay", IBinder::class.java)
            Log.i(TAG, "SurfaceControl reflection methods initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SurfaceControl reflection: ${e.message}")
        }
    }

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, context: android.content.Context?, capturerObserver: CapturerObserver?) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val helper = surfaceTextureHelper ?: run {
            Log.e(TAG, "SurfaceTextureHelper is null; cannot start capture")
            return
        }
        helper.setTextureSize(width, height)
        val surface = Surface(helper.surfaceTexture)

        try {
            // 1. Create virtual display
            val token = createDisplayMethod?.invoke(null, "webrtc-scrcpy-mirror", false) as? IBinder
            displayToken = token
            Log.i(TAG, "Created virtual display: token=$token")

            val layerStackRect = Rect(0, 0, deviceWidth, deviceHeight)
            val displayRect = Rect(0, 0, width, height)

            // 2. Configure transaction: bind Surface + LayerStack 0 + viewport projection
            openTransactionMethod?.invoke(null)
            try {
                setDisplaySurfaceMethod?.invoke(null, token, surface)
                setDisplayProjectionMethod?.invoke(null, token, 0, layerStackRect, displayRect)
                setDisplayLayerStackMethod?.invoke(null, token, 0)
                setDisplaySizeMethod?.invoke(null, token, width, height)
            } finally {
                closeTransactionMethod?.invoke(null)
            }

            helper.startListening { videoFrame ->
                capturerObserver?.onFrameCaptured(videoFrame)
            }
            Log.i(TAG, "SurfaceControl mirror capture started: ${deviceWidth}x${deviceHeight} -> ${width}x${height} @ ${framerate}fps, LayerStack: 0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SurfaceControl capture: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun stopCapture() {
        surfaceTextureHelper?.stopListening()
        displayToken?.let { token ->
            try {
                destroyDisplayMethod?.invoke(null, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy DisplayToken: ${e.message}")
            }
            displayToken = null
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        stopCapture()
        startCapture(width, height, framerate)
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = true
}
