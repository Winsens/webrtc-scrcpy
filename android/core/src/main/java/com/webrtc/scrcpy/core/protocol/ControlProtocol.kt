package com.webrtc.scrcpy.core.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Control protocol definitions and parser
 */
sealed class ControlEvent {
    data class Touch(
        val action: Int,      // 0: DOWN, 1: UP, 2: MOVE
        val pointerId: Int,   // 0 - 9
        val normX: Int,       // 0 - 10000
        val normY: Int,       // 0 - 10000
        val pressure: Int     // 0 - 1000
    ) : ControlEvent()

    data class Key(
        val action: Int,      // 0: DOWN, 1: UP
        val keyCode: Int,     // Android KeyCode
        val metaState: Int
    ) : ControlEvent()

    data class Scroll(
        val normX: Int,
        val normY: Int,
        val deltaX: Int,
        val deltaY: Int
    ) : ControlEvent()

    data class Text(
        val text: String
    ) : ControlEvent()

    data class Command(
        val commandId: Int
    ) : ControlEvent() {
        companion object {
            const val CMD_BACK = 1
            const val CMD_HOME = 2
            const val CMD_RECENTS = 3
            const val CMD_POWER = 4
            const val CMD_VOLUME_UP = 5
            const val CMD_VOLUME_DOWN = 6
            const val CMD_ROTATE = 7
        }
    }
}

object ControlProtocolDecoder {
    private const val TYPE_TOUCH: Byte = 0x01
    private const val TYPE_KEY: Byte = 0x02
    private const val TYPE_SCROLL: Byte = 0x03
    private const val TYPE_TEXT: Byte = 0x04
    private const val TYPE_COMMAND: Byte = 0x05

    fun decode(buffer: ByteBuffer): ControlEvent? {
        if (!buffer.hasRemaining()) return null
        val type = buffer.get()

        return when (type) {
            TYPE_TOUCH -> {
                if (buffer.remaining() < 12) return null
                val action = buffer.get().toInt() and 0xFF
                val pointerId = buffer.get().toInt() and 0xFF
                val normX = buffer.int
                val normY = buffer.int
                val pressure = if (buffer.remaining() >= 2) buffer.short.toInt() and 0xFFFF else 1000
                ControlEvent.Touch(action, pointerId, normX, normY, pressure)
            }
            TYPE_KEY -> {
                if (buffer.remaining() < 9) return null
                val action = buffer.get().toInt() and 0xFF
                val keyCode = buffer.int
                val metaState = buffer.int
                ControlEvent.Key(action, keyCode, metaState)
            }
            TYPE_SCROLL -> {
                if (buffer.remaining() < 16) return null
                val normX = buffer.int
                val normY = buffer.int
                val deltaX = buffer.int
                val deltaY = buffer.int
                ControlEvent.Scroll(normX, normY, deltaX, deltaY)
            }
            TYPE_TEXT -> {
                if (buffer.remaining() < 2) return null
                val length = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < length) return null
                val bytes = ByteArray(length)
                buffer.get(bytes)
                val text = String(bytes, StandardCharsets.UTF_8)
                ControlEvent.Text(text)
            }
            TYPE_COMMAND -> {
                if (!buffer.hasRemaining()) return null
                val cmdId = buffer.get().toInt() and 0xFF
                ControlEvent.Command(cmdId)
            }
            else -> null
        }
    }
}

