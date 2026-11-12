package com.webrtc.scrcpy.core.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display

/**
 * Display size helpers shared by the capturers and the input injectors, so the
 * captured frame always has the same aspect ratio as the surface touches are mapped onto.
 */
object ScreenGeometry {
    const val DEFAULT_MAX_SHORT_SIDE = 720

    /** Real pixel size of the default display in its current rotation, or null if unavailable. */
    fun realDisplaySize(context: Context): Size? {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                Size(metrics.widthPixels, metrics.heightPixels)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Capture size with the display's aspect ratio, downscaled so the short side is at most
     * [maxShortSide]. Dimensions are rounded down to even numbers, as video encoders require.
     */
    fun captureSize(display: Size, maxShortSide: Int = DEFAULT_MAX_SHORT_SIDE): Size {
        val shortSide = minOf(display.width, display.height)
        val scale = if (shortSide > maxShortSide) maxShortSide.toFloat() / shortSide else 1f
        val width = (Math.round(display.width * scale) and 1.inv()).coerceAtLeast(2)
        val height = (Math.round(display.height * scale) and 1.inv()).coerceAtLeast(2)
        return Size(width, height)
    }
}
