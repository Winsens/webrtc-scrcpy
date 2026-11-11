/**
 * webrtc-scrcpy binary control protocol codec
 */
class Protocol {
    static TYPE_TOUCH = 0x01;
    static TYPE_KEY = 0x02;
    static TYPE_SCROLL = 0x03;
    static TYPE_TEXT = 0x04;
    static TYPE_COMMAND = 0x05;

    static TOUCH_ACTION_DOWN = 0;
    static TOUCH_ACTION_UP = 1;
    static TOUCH_ACTION_MOVE = 2;

    static CMD_BACK = 1;
    static CMD_HOME = 2;
    static CMD_RECENTS = 3;
    static CMD_POWER = 4;
    static CMD_VOLUME_UP = 5;
    static CMD_VOLUME_DOWN = 6;
    static CMD_ROTATE = 7;

    /**
     * Encode touch event into an ArrayBuffer
     * @param {number} action 0: DOWN, 1: UP, 2: MOVE
     * @param {number} pointerId Pointer ID (0-9)
     * @param {number} normX Normalized X coordinate (0 - 10000)
     * @param {number} normY Normalized Y coordinate (0 - 10000)
     * @param {number} pressure Pressure value (0 - 1000)
     */
    static encodeTouch(action, pointerId, normX, normY, pressure = 1000) {
        const buffer = new ArrayBuffer(13);
        const view = new DataView(buffer);
        view.setUint8(0, this.TYPE_TOUCH);
        view.setUint8(1, action);
        view.setUint8(2, pointerId);
        view.setUint32(3, normX);
        view.setUint32(7, normY);
        view.setUint16(11, pressure);
        return buffer;
    }

    /**
     * Encode physical/key event
     * @param {number} action 0: DOWN, 1: UP
     * @param {number} keyCode Android KeyCode
     * @param {number} metaState Modifier key flags
     */
    static encodeKey(action, keyCode, metaState = 0) {
        const buffer = new ArrayBuffer(10);
        const view = new DataView(buffer);
        view.setUint8(0, this.TYPE_KEY);
        view.setUint8(1, action);
        view.setInt32(2, keyCode);
        view.setInt32(6, metaState);
        return buffer;
    }

    /**
     * Encode scroll wheel event
     * @param {number} normX
     * @param {number} normY
     * @param {number} deltaX
     * @param {number} deltaY
     */
    static encodeScroll(normX, normY, deltaX, deltaY) {
        const buffer = new ArrayBuffer(17);
        const view = new DataView(buffer);
        view.setUint8(0, this.TYPE_SCROLL);
        view.setUint32(1, normX);
        view.setUint32(5, normY);
        view.setInt32(9, deltaX);
        view.setInt32(13, deltaY);
        return buffer;
    }

    /**
     * Encode text input / clipboard sync
     * @param {string} text
     */
    static encodeText(text) {
        const encoder = new TextEncoder();
        const textBytes = encoder.encode(text);
        const buffer = new ArrayBuffer(3 + textBytes.length);
        const view = new DataView(buffer);
        view.setUint8(0, this.TYPE_TEXT);
        view.setUint16(1, textBytes.length);
        new Uint8Array(buffer, 3).set(textBytes);
        return buffer;
    }

    /**
     * Encode virtual navigation command (BACK, HOME, RECENTS, POWER, etc.)
     * @param {number} commandId
     */
    static encodeCommand(commandId) {
        const buffer = new ArrayBuffer(2);
        const view = new DataView(buffer);
        view.setUint8(0, this.TYPE_COMMAND);
        view.setUint8(1, commandId);
        return buffer;
    }
}

