package com.webrtc.scrcpy.core.input

import com.webrtc.scrcpy.core.protocol.ControlEvent

interface InputInjector {
    fun setScreenDimensions(width: Int, height: Int)
    fun injectTouch(touch: ControlEvent.Touch)
    fun injectKey(key: ControlEvent.Key)
    fun injectScroll(scroll: ControlEvent.Scroll)
    fun injectText(text: String)
    fun injectCommand(commandId: Int)
}

