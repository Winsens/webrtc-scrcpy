package com.webrtc.scrcpy.core.capture

/**
 * A capturer that can re-emit its latest frame on demand. SurfaceTextureHelper only produces
 * frames when the screen content changes, so a viewer that connects while the screen is static
 * would otherwise see nothing until something redraws.
 */
interface RefreshableCapturer {
    fun requestRefreshFrame()
}
