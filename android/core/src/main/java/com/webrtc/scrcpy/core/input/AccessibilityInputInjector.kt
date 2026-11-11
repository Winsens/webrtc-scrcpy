package com.webrtc.scrcpy.core.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.webrtc.scrcpy.core.protocol.ControlEvent
import kotlin.math.hypot

/**
 * Accessibility-based input injector (for rootless, non-ADB Android environments)
 * Supports real-time continuous gesture streaming on Android 8.0+ (API 26+) via StrokeDescription.continueStroke,
 * allowing drag gestures to follow mouse movement live without waiting for pointer release.
 */
class AccessibilityInputInjector : InputInjector {
    private val TAG = "AccessibilityInjector"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var screenWidth = 1080

    @Volatile
    private var screenHeight = 2400

    private var cachedDisplayWidth = 0
    private var cachedDisplayHeight = 0
    private var lastDisplayCheckMs = 0L

    // Active gesture state (accessed strictly on the mainHandler thread)
    private var isPointerDown = false
    private var activePointerId = 0
    private var downTimeMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var dispatchedX = 0f
    private var dispatchedY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var hasMovedFar = false
    private var isStrokeInFlight = false
    private var strokeSegmentCount = 0
    private var currentStroke: GestureDescription.StrokeDescription? = null

    companion object {
        @Volatile
        var serviceInstance: AccessibilityService? = null
        private const val STREAM_STEP_MS = 38L
    }

    override fun setScreenDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            this.screenWidth = width
            this.screenHeight = height
        }
    }

    private fun resolveDisplaySize(service: AccessibilityService): Pair<Int, Int> {
        val now = SystemClock.uptimeMillis()
        if (cachedDisplayWidth > 0 && cachedDisplayHeight > 0 && now - lastDisplayCheckMs < 2000L) {
            return cachedDisplayWidth to cachedDisplayHeight
        }
        lastDisplayCheckMs = now
        try {
            val dm = service.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    cachedDisplayWidth = metrics.widthPixels
                    cachedDisplayHeight = metrics.heightPixels
                    return cachedDisplayWidth to cachedDisplayHeight
                }
            }
        } catch (_: Exception) {
        }
        return screenWidth to screenHeight
    }

    private fun toScreenCoord(norm: Int, maxExtent: Int): Float {
        if (maxExtent <= 1) return 0f
        val raw = (norm.coerceIn(0, 10000) / 10000.0f) * (maxExtent - 1)
        return raw.coerceIn(0f, (maxExtent - 1).toFloat())
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun injectTouch(touch: ControlEvent.Touch) {
        val service = serviceInstance ?: return
        val (dispW, dispH) = resolveDisplaySize(service)
        val realX = toScreenCoord(touch.normX, dispW)
        val realY = toScreenCoord(touch.normY, dispH)
        val now = SystemClock.uptimeMillis()

        mainHandler.post {
            handleTouchOnMain(service, touch.action, touch.pointerId, realX, realY, now)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleTouchOnMain(
        service: AccessibilityService,
        action: Int,
        pointerId: Int,
        realX: Float,
        realY: Float,
        now: Long
    ) {
        when (action) {
            0 -> { // DOWN
                isPointerDown = true
                activePointerId = pointerId
                downTimeMs = now
                startX = realX
                startY = realY
                dispatchedX = realX
                dispatchedY = realY
                targetX = realX
                targetY = realY
                hasMovedFar = false
                strokeSegmentCount = 0
            }
            2 -> { // MOVE
                if (!isPointerDown || pointerId != activePointerId) return
                targetX = realX
                targetY = realY
                if (!hasMovedFar && hypot((realX - startX).toDouble(), (realY - startY).toDouble()) >= 8.0) {
                    hasMovedFar = true
                }
                // On Android 8.0+, start continuous gesture streaming immediately on drag instead of waiting for pointer release
                if (hasMovedFar && !isStrokeInFlight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startStreamingStroke(service)
                }
            }
            1 -> { // UP
                if (!isPointerDown && currentStroke == null) return
                isPointerDown = false
                targetX = realX
                targetY = realY
                val totalMove = hypot((realX - startX).toDouble(), (realY - startY).toDouble())
                if (totalMove >= 8.0) {
                    hasMovedFar = true
                }

                // If continuous gesture streaming is already active, onCompleted will append the final segment and lift the pointer
                if (isStrokeInFlight && currentStroke != null) {
                    return
                }

                val elapsed = (now - downTimeMs).coerceAtLeast(1L)
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(realX, realY)
                }
                val durationMs = if (!hasMovedFar) {
                    if (elapsed >= 450L) elapsed.coerceAtMost(1000L) else 35L
                } else {
                    // Use a short, high-velocity duration for fast flick gestures so launcher paging and list fling feel snappy
                    55L
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                service.dispatchGesture(gesture, null, mainHandler)
            }
            3 -> { // CANCEL
                isPointerDown = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startStreamingStroke(service: AccessibilityService) {
        val nextX = targetX
        val nextY = targetY
        val path = Path().apply {
            moveTo(dispatchedX, dispatchedY)
            lineTo(nextX, nextY)
        }
        dispatchedX = nextX
        dispatchedY = nextY
        val willContinue = isPointerDown
        val stroke = GestureDescription.StrokeDescription(path, 0, STREAM_STEP_MS, willContinue)
        currentStroke = stroke
        isStrokeInFlight = true
        strokeSegmentCount = 1

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onStreamingStepCompleted(service)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                resetStreamingState()
            }
        }, mainHandler)

        if (!ok) {
            resetStreamingState()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun onStreamingStepCompleted(service: AccessibilityService) {
        val prevStroke = currentStroke ?: run {
            resetStreamingState()
            return
        }

        // Finish the stroke if pointer is released with no remaining deltas, or if the max segment limit is reached
        val remainingDist = hypot((targetX - dispatchedX).toDouble(), (targetY - dispatchedY).toDouble())
        if (!isPointerDown && remainingDist < 1.5) {
            // Append a very short final segment to release the finger (willContinue = false)
            val endPath = Path().apply {
                moveTo(dispatchedX, dispatchedY)
                lineTo(dispatchedX + 0.5f, dispatchedY)
            }
            try {
                val finalStroke = prevStroke.continueStroke(endPath, 0, 18L, false)
                val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        resetStreamingState()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        resetStreamingState()
                    }
                }, mainHandler)
            } catch (_: Exception) {
                resetStreamingState()
            }
            return
        }

        val nextX = targetX
        val nextY = targetY
        val segPath = Path().apply {
            moveTo(dispatchedX, dispatchedY)
            lineTo(nextX, nextY)
        }
        dispatchedX = nextX
        dispatchedY = nextY
        strokeSegmentCount++

        val keepGoing = isPointerDown && strokeSegmentCount < 80
        try {
            val nextStroke = prevStroke.continueStroke(segPath, 0, STREAM_STEP_MS, keepGoing)
            currentStroke = if (keepGoing) nextStroke else null
            val gesture = GestureDescription.Builder().addStroke(nextStroke).build()
            val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (keepGoing) {
                        onStreamingStepCompleted(service)
                    } else {
                        resetStreamingState()
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    resetStreamingState()
                }
            }, mainHandler)
            if (!ok) {
                resetStreamingState()
            }
        } catch (e: Exception) {
            Log.w(TAG, "continueStroke error: ${e.message}")
            resetStreamingState()
        }
    }

    private fun resetStreamingState() {
        isStrokeInFlight = false
        currentStroke = null
        strokeSegmentCount = 0
    }

    override fun injectKey(key: ControlEvent.Key) {}

    @RequiresApi(Build.VERSION_CODES.N)
    override fun injectScroll(scroll: ControlEvent.Scroll) {
        val service = serviceInstance ?: return
        val (dispW, dispH) = resolveDisplaySize(service)
        val startX = toScreenCoord(scroll.normX, dispW)
        val startY = toScreenCoord(scroll.normY, dispH)

        fun scrollStepPx(delta: Int, extent: Int): Float {
            if (delta == 0) return 0f
            val dir = if (delta > 0) -1f else 1f
            val magnitude = (kotlin.math.abs(delta) / 100.0f).coerceIn(0.35f, 3.0f)
            return dir * magnitude * (extent * 0.14f)
        }

        val stepPxX = scrollStepPx(scroll.deltaX, dispW)
        val stepPxY = scrollStepPx(scroll.deltaY, dispH)
        val endX = (startX + stepPxX).coerceIn(0f, (dispW - 1).coerceAtLeast(0).toFloat())
        val endY = (startY + stepPxY).coerceIn(0f, (dispH - 1).coerceAtLeast(0).toFloat())
        if (hypot((endX - startX).toDouble(), (endY - startY).toDouble()) < 4.0) return

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 180L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    override fun injectText(text: String) {}

    override fun injectCommand(commandId: Int) {
        val service = serviceInstance ?: return
        when (commandId) {
            ControlEvent.Command.CMD_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ControlEvent.Command.CMD_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ControlEvent.Command.CMD_RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ControlEvent.Command.CMD_POWER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }
}

