/**
 * webrtc-scrcpy main application interaction logic
 */
document.addEventListener('DOMContentLoaded', () => {
    const video = document.getElementById('remote-video');
    const touchOverlay = document.getElementById('touch-overlay');
    const statusText = document.getElementById('status-text');
    const statusIndicator = document.getElementById('status-indicator');
    const statsOverlay = document.getElementById('stats-overlay');

    // Toolbar buttons
    const btnBack = document.getElementById('btn-back');
    const btnHome = document.getElementById('btn-home');
    const btnRecents = document.getElementById('btn-recents');
    const btnPower = document.getElementById('btn-power');
    const btnVolUp = document.getElementById('btn-vol-up');
    const btnVolDown = document.getElementById('btn-vol-down');
    const btnRotate = document.getElementById('btn-rotate');
    const btnClipboard = document.getElementById('btn-clipboard');
    const btnStats = document.getElementById('btn-stats');
    const btnAudio = document.getElementById('btn-audio');

    let isAudioEnabled = false;

    // Connect to the current page's WebSocket signaling endpoint by default
    const wsProto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const defaultWsUrl = `${wsProto}//${location.host}/ws`;

    const client = new WebRtcClient({
        onStatusChange: (status, detail) => {
            statusText.innerText = detail;
            statusIndicator.className = 'indicator ' + status.toLowerCase();
            if (status === 'CONNECTED') {
                statusIndicator.style.backgroundColor = '#00ff66';
            } else if (status === 'CONNECTING') {
                statusIndicator.style.backgroundColor = '#ffaa00';
            } else {
                statusIndicator.style.backgroundColor = '#ff3333';
            }
        },
        onStatsUpdate: (stats) => {
            document.getElementById('stat-fps').innerText = stats.fps;
            document.getElementById('stat-rtt').innerText = stats.rtt + ' ms';
            document.getElementById('stat-bitrate').innerText = stats.bitrate + ' kbps';
            document.getElementById('stat-res').innerText = `${stats.width}x${stats.height}`;
            document.getElementById('stat-loss').innerText = stats.packetsLost;
        },
        onRemoteTrack: (stream) => {
            if (video.srcObject !== stream) {
                video.srcObject = stream;
            }
            video.play().catch(e => console.log('Autoplay blocked:', e));
        }
    });

    // Start connection
    client.connect(defaultWsUrl);

    // ==========================================
    // 1. Touch & mouse coordinate calculation (accurately maps letterboxing/pillarboxing)
    // ==========================================
    function calculateNormalizedCoords(clientX, clientY, clampToBounds = false) {
        const rect = touchOverlay.getBoundingClientRect();
        const videoWidth = video.videoWidth || rect.width;
        const videoHeight = video.videoHeight || rect.height;
        if (!rect.width || !rect.height || !videoWidth || !videoHeight) {
            return null;
        }

        const containerAspect = rect.width / rect.height;
        const videoAspect = videoWidth / videoHeight;

        let actualRenderWidth = rect.width;
        let actualRenderHeight = rect.height;
        let offsetX = 0;
        let offsetY = 0;

        if (containerAspect > videoAspect) {
            // Horizontal black bars (Pillarbox)
            actualRenderWidth = rect.height * videoAspect;
            offsetX = (rect.width - actualRenderWidth) / 2;
        } else {
            // Vertical black bars (Letterbox)
            actualRenderHeight = rect.width / videoAspect;
            offsetY = (rect.height - actualRenderHeight) / 2;
        }

        const clickX = clientX - rect.left - offsetX;
        const clickY = clientY - rect.top - offsetY;

        if (!clampToBounds && (clickX < 0 || clickX > actualRenderWidth || clickY < 0 || clickY > actualRenderHeight)) {
            return null; // Click fell inside black bar area
        }

        const normX = Math.floor((clickX / actualRenderWidth) * 10000);
        const normY = Math.floor((clickY / actualRenderHeight) * 10000);

        return {
            x: Math.max(0, Math.min(10000, normX)),
            y: Math.max(0, Math.min(10000, normY))
        };
    }

    function sendPointerEvent(action, e, pointerId = 0, clampToBounds = false) {
        const coords = calculateNormalizedCoords(e.clientX, e.clientY, clampToBounds);
        if (!coords) return false;
        const buffer = Protocol.encodeTouch(action, pointerId, coords.x, coords.y);
        client.sendControl(buffer);
        return true;
    }

    // Mouse event bindings (throttle high-frequency mousemove to 12ms (~80Hz) to avoid flooding the channel)
    let isMouseDown = false;
    let lastMoveSentTime = 0;
    touchOverlay.addEventListener('mousedown', (e) => {
        if (e.button === 0) { // Left click
            e.preventDefault();
            if (sendPointerEvent(Protocol.TOUCH_ACTION_DOWN, e, 0, false)) {
                isMouseDown = true;
                lastMoveSentTime = 0;
            }
        } else if (e.button === 2) { // Right click triggers Android Back button
            e.preventDefault();
            client.sendControl(Protocol.encodeCommand(Protocol.CMD_BACK));
        } else if (e.button === 1) { // Middle click triggers Home button
            e.preventDefault();
            client.sendControl(Protocol.encodeCommand(Protocol.CMD_HOME));
        }
    });

    window.addEventListener('mousemove', (e) => {
        if (isMouseDown) {
            const now = performance.now();
            if (now - lastMoveSentTime >= 12) {
                lastMoveSentTime = now;
                sendPointerEvent(Protocol.TOUCH_ACTION_MOVE, e, 0, true);
            }
        }
    });

    window.addEventListener('mouseup', (e) => {
        if (isMouseDown && e.button === 0) {
            isMouseDown = false;
            sendPointerEvent(Protocol.TOUCH_ACTION_UP, e, 0, true);
        }
    });

    touchOverlay.addEventListener('contextmenu', (e) => e.preventDefault());

    // Touch screen event bindings (for mobile browsers)
    touchOverlay.addEventListener('touchstart', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = calculateNormalizedCoords(touch.clientX, touch.clientY, false);
            if (coords) {
                const buffer = Protocol.encodeTouch(Protocol.TOUCH_ACTION_DOWN, touch.identifier % 10, coords.x, coords.y);
                client.sendControl(buffer);
            }
        }
    }, { passive: false });

    touchOverlay.addEventListener('touchmove', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = calculateNormalizedCoords(touch.clientX, touch.clientY, true);
            if (coords) {
                const buffer = Protocol.encodeTouch(Protocol.TOUCH_ACTION_MOVE, touch.identifier % 10, coords.x, coords.y);
                client.sendControl(buffer);
            }
        }
    }, { passive: false });

    touchOverlay.addEventListener('touchend', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = calculateNormalizedCoords(touch.clientX, touch.clientY, true);
            if (coords) {
                const buffer = Protocol.encodeTouch(Protocol.TOUCH_ACTION_UP, touch.identifier % 10, coords.x, coords.y);
                client.sendControl(buffer);
            }
        }
    }, { passive: false });

    touchOverlay.addEventListener('touchcancel', (e) => {
        e.preventDefault();
        for (let i = 0; i < e.changedTouches.length; i++) {
            const touch = e.changedTouches[i];
            const coords = calculateNormalizedCoords(touch.clientX, touch.clientY, true);
            if (coords) {
                const buffer = Protocol.encodeTouch(Protocol.TOUCH_ACTION_UP, touch.identifier % 10, coords.x, coords.y);
                client.sendControl(buffer);
            }
        }
    }, { passive: false });

    // Mouse wheel scroll bindings
    touchOverlay.addEventListener('wheel', (e) => {
        e.preventDefault();
        const coords = calculateNormalizedCoords(e.clientX, e.clientY);
        if (!coords) return;
        const deltaX = Math.round(e.deltaX);
        const deltaY = Math.round(e.deltaY);
        client.sendControl(Protocol.encodeScroll(coords.x, coords.y, deltaX, deltaY));
    }, { passive: false });

    // ==========================================
    // 2. Keyboard event mapping (standard Android KeyCodes)
    // ==========================================
    const KEY_CODE_MAP = {
        'Enter': 66,       // KEYCODE_ENTER
        'Backspace': 67,   // KEYCODE_DEL
        'Tab': 61,         // KEYCODE_TAB
        'Escape': 111,     // KEYCODE_ESCAPE (Back)
        'ArrowLeft': 21,   // KEYCODE_DPAD_LEFT
        'ArrowUp': 19,     // KEYCODE_DPAD_UP
        'ArrowRight': 22,  // KEYCODE_DPAD_RIGHT
        'ArrowDown': 20,   // KEYCODE_DPAD_DOWN
        'Delete': 112,     // KEYCODE_FORWARD_DEL
        'Home': 122,       // KEYCODE_MOVE_HOME
        'End': 123,        // KEYCODE_MOVE_END
        'PageUp': 92,      // KEYCODE_PAGE_UP
        'PageDown': 93,    // KEYCODE_PAGE_DOWN
        'Space': 62        // KEYCODE_SPACE
    };

    window.addEventListener('keydown', (e) => {
        // Do not intercept if focus is inside an input dialog
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

        const androidCode = KEY_CODE_MAP[e.code] || KEY_CODE_MAP[e.key];
        if (androidCode) {
            e.preventDefault();
            client.sendControl(Protocol.encodeKey(0, androidCode));
        } else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey) {
            // Send printable characters directly as text input
            e.preventDefault();
            client.sendControl(Protocol.encodeText(e.key));
        }
    });

    window.addEventListener('keyup', (e) => {
        if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
        const androidCode = KEY_CODE_MAP[e.code] || KEY_CODE_MAP[e.key];
        if (androidCode) {
            e.preventDefault();
            client.sendControl(Protocol.encodeKey(1, androidCode));
        }
    });

    // ==========================================
    // 3. Virtual navigation bar button bindings
    // ==========================================
    btnBack.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_BACK)));
    btnHome.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_HOME)));
    btnRecents.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_RECENTS)));
    btnPower.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_POWER)));
    btnVolUp.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_VOLUME_UP)));
    btnVolDown.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_VOLUME_DOWN)));
    btnRotate.addEventListener('click', () => client.sendControl(Protocol.encodeCommand(Protocol.CMD_ROTATE)));

    // Quick text / clipboard input
    btnClipboard.addEventListener('click', () => {
        const text = prompt('Enter text or clipboard content to send to the device:');
        if (text) {
            client.sendControl(Protocol.encodeText(text));
        }
    });

    // Performance stats overlay toggle
    btnStats.addEventListener('click', () => {
        statsOverlay.classList.toggle('hidden');
    });

    // Audio unmute toggle (handles browser autoplay policy)
    btnAudio.addEventListener('click', () => {
        if (!isAudioEnabled) {
            video.muted = false;
            video.play().then(() => {
                isAudioEnabled = true;
                btnAudio.classList.add('active');
                btnAudio.innerText = '🔊 Audio On';
            }).catch(e => {
                alert('Failed to play audio: ' + e.message);
            });
        } else {
            video.muted = true;
            isAudioEnabled = false;
            btnAudio.classList.remove('active');
            btnAudio.innerText = '🔇 Audio Off';
        }
    });

    // Proactively close WebRTC and signaling on unload to speed up reconnection on Android side
    window.addEventListener('pagehide', () => client.cleanup());
    window.addEventListener('beforeunload', () => client.cleanup());
});

