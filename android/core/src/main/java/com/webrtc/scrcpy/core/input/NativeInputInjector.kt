package com.webrtc.scrcpy.core.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.webrtc.scrcpy.core.protocol.ControlEvent
import java.lang.reflect.Method

/**
 * Native input injector implementation (for Shell / Root / AOSP cloud / Shizuku environments)
 * Provides zero-latency asynchronous injection (INJECT_INPUT_EVENT_MODE_ASYNC) with system privileges
 */
class NativeInputInjector(private val context: Context? = null) : InputInjector {
    private val TAG = "NativeInputInjector"

    private var injectInputEventMethod: Method? = null
    private var inputManagerInstance: Any? = null

    private var screenWidth = 1080
    private var screenHeight = 2400

    // Active touch pointers (supports multi-touch)
    private val pointerCoordsMap = mutableMapOf<Int, MotionEvent.PointerCoords>()
    private val pointerPropertiesMap = mutableMapOf<Int, MotionEvent.PointerProperties>()

    init {
        try {
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance")
            getInstanceMethod.isAccessible = true
            inputManagerInstance = getInstanceMethod.invoke(null)

            injectInputEventMethod = inputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectInputEventMethod?.isAccessible = true
            Log.i(TAG, "InputManager.injectInputEvent reflection ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NativeInputInjector: ${e.message}")
        }
    }

    override fun setScreenDimensions(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
    }

    override fun injectTouch(touch: ControlEvent.Touch) {
        val realX = (touch.normX / 10000.0f) * screenWidth
        val realY = (touch.normY / 10000.0f) * screenHeight
        val now = SystemClock.uptimeMillis()

        val pointerId = touch.pointerId

        val coords = pointerCoordsMap.getOrPut(pointerId) {
            MotionEvent.PointerCoords().apply {
                pressure = (touch.pressure / 1000.0f).coerceIn(0.1f, 1.0f)
                size = 1.0f
            }
        }
        coords.x = realX
        coords.y = realY

        val prop = pointerPropertiesMap.getOrPut(pointerId) {
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }

        val actionMasked = when (touch.action) {
            0 -> if (pointerPropertiesMap.size <= 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
            1 -> if (pointerPropertiesMap.size <= 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
            else -> MotionEvent.ACTION_MOVE
        }

        val pointerIndex = pointerPropertiesMap.keys.indexOf(pointerId)
        val finalAction = if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            actionMasked or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            actionMasked
        }

        val propArray = pointerPropertiesMap.values.toTypedArray()
        val coordsArray = pointerCoordsMap.values.toTypedArray()

        val event = MotionEvent.obtain(
            now, now, finalAction, propArray.size, propArray, coordsArray,
            0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )

        injectEvent(event)
        event.recycle()

        if (touch.action == 1) { // UP
            pointerPropertiesMap.remove(pointerId)
            pointerCoordsMap.remove(pointerId)
        }
    }

    override fun injectKey(key: ControlEvent.Key) {
        val now = SystemClock.uptimeMillis()
        val action = if (key.action == 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val event = KeyEvent(now, now, action, key.keyCode, 0, key.metaState)
        injectEvent(event)
    }

    override fun injectScroll(scroll: ControlEvent.Scroll) {
        val realX = (scroll.normX / 10000.0f) * screenWidth
        val realY = (scroll.normY / 10000.0f) * screenHeight
        val now = SystemClock.uptimeMillis()

        val prop = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = realX
            y = realY
            setAxisValue(MotionEvent.AXIS_HSCROLL, -scroll.deltaX.toFloat())
            setAxisValue(MotionEvent.AXIS_VSCROLL, -scroll.deltaY.toFloat())
        }

        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(prop), arrayOf(coords),
            0, 0, 1.0f, 1.0f, 0, 0, 0, 0
        )
        injectEvent(event)
        event.recycle()
    }

    override fun injectText(text: String) {
        context?.let { ctx ->
            try {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("webrtc-scrcpy", text)
                clipboard.setPrimaryClip(clip)

                // Inject paste shortcut (Ctrl + V)
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON)
                val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON)
                injectEvent(down)
                injectEvent(up)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject text via clipboard; falling back: ${e.message}")
            }
        }
    }

    override fun injectCommand(commandId: Int) {
        val keyCode = when (commandId) {
            ControlEvent.Command.CMD_BACK -> KeyEvent.KEYCODE_BACK
            ControlEvent.Command.CMD_HOME -> KeyEvent.KEYCODE_HOME
            ControlEvent.Command.CMD_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            ControlEvent.Command.CMD_POWER -> KeyEvent.KEYCODE_POWER
            ControlEvent.Command.CMD_VOLUME_UP -> KeyEvent.KEYCODE_VOLUME_UP
            ControlEvent.Command.CMD_VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
            else -> return
        }

        val now = SystemClock.uptimeMillis()
        injectEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        injectEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun injectEvent(event: InputEvent) {
        try {
            // Mode 0 is INJECT_INPUT_EVENT_MODE_ASYNC to avoid blocking the caller thread
            injectInputEventMethod?.invoke(inputManagerInstance, event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent error: ${e.message}")
        }
    }
}

