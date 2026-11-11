/**
 * webrtc-scrcpy WebRTC client management module
 */
function isPrivateLanHost(hostname) {
    if (!hostname) return true;
    return hostname === 'localhost' ||
        hostname === '127.0.0.1' ||
        hostname === '::1' ||
        hostname.endsWith('.local') ||
        /^10\./.test(hostname) ||
        /^192\.168\./.test(hostname) ||
        /^172\.(1[6-9]|2\d|3[0-1])\./.test(hostname);
}

class WebRtcClient {
    constructor(options = {}) {
        const lanHost = typeof location !== 'undefined' && isPrivateLanHost(location.hostname);
        const defaultIceServers = lanHost ? [] : [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' }
        ];
        this.options = Object.assign({
            iceServers: defaultIceServers,
            iceCandidatePoolSize: lanHost ? 0 : 2,
            onStatusChange: (status, detail) => {},
            onStatsUpdate: (stats) => {},
            onRemoteTrack: (stream) => {}
        }, options);

        this.pc = null;
        this.dataChannel = null;
        this.ws = null;
        this.statsInterval = null;
        this.lastBytesReceived = 0;
        this.lastStatsTime = 0;
    }

    /**
     * Connect signaling and start the WebRTC session
     * @param {string} wsUrl 
     */
    connect(wsUrl) {
        this.options.onStatusChange('CONNECTING', 'Connecting to signaling server...');
        
        try {
            this.ws = new WebSocket(wsUrl);
        } catch (e) {
            this.options.onStatusChange('ERROR', 'Invalid signaling URL: ' + e.message);
            return;
        }

        this.ws.onopen = () => {
            this.options.onStatusChange('SIGNALING_OK', 'Signaling connected, creating PeerConnection...');
            this.initPeerConnection();
        };

        this.ws.onmessage = async (event) => {
            try {
                const message = JSON.parse(event.data);
                await this.handleSignalingMessage(message);
            } catch (err) {
                console.error('[WebRTC] Failed to handle signaling message:', err);
            }
        };

        this.ws.onclose = () => {
            // Signaling is only used for negotiation; keep active P2P media/control channels if signaling closes
            const pcAlive = this.pc && ['connected', 'connecting'].includes(this.pc.connectionState);
            if (pcAlive) {
                console.warn('[WebRTC] Signaling disconnected, keeping active P2P connection');
                return;
            }
            this.options.onStatusChange('DISCONNECTED', 'Signaling disconnected');
            this.cleanup();
        };

        this.ws.onerror = (err) => {
            this.options.onStatusChange('ERROR', 'Signaling connection error');
        };
    }

    initPeerConnection() {
        this.pc = new RTCPeerConnection({
            iceServers: this.options.iceServers,
            iceCandidatePoolSize: this.options.iceCandidatePoolSize
        });

        // 1. Create an ordered control DataChannel to prevent fast swipe DOWN/MOVE/UP drops or reordering
        this.dataChannel = this.pc.createDataChannel("control", {
            ordered: true
        });
        this.dataChannel.binaryType = "arraybuffer";

        this.dataChannel.onopen = () => {
            this.options.onStatusChange('CONNECTED', 'Control channel ready (low latency)');
            this.startStatsMonitor();
        };

        this.dataChannel.onclose = () => {
            this.options.onStatusChange('DISCONNECTED', 'Control channel closed');
        };

        this.remoteStream = new MediaStream();

        // 2. Receive remote media tracks (merge audio/video into one MediaStream with a 20ms playout delay for low latency and Wi-Fi jitter resilience)
        this.pc.ontrack = (event) => {
            if (event.receiver) {
                try {
                    if ('playoutDelayHint' in event.receiver) {
                        event.receiver.playoutDelayHint = 0.02;
                    }
                } catch (_) {}
            }
            if (event.track && !this.remoteStream.getTracks().some(t => t.id === event.track.id)) {
                this.remoteStream.addTrack(event.track);
            }
            this.options.onRemoteTrack(this.remoteStream);
        };

        // 3. Exchange ICE candidates
        this.pc.onicecandidate = (event) => {
            if (event.candidate && this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({
                    type: 'candidate',
                    candidate: event.candidate.candidate,
                    sdpMid: event.candidate.sdpMid,
                    sdpMLineIndex: event.candidate.sdpMLineIndex
                }));
            }
        };

        this.pc.onconnectionstatechange = () => {
            console.log('[WebRTC] Connection state:', this.pc.connectionState);
            if (this.pc.connectionState === 'failed') {
                this.options.onStatusChange('ERROR', 'WebRTC connection failed; check network or firewall');
            }
        };

        // 4. Request receive-only audio and video
        this.pc.addTransceiver('video', { direction: 'recvonly' });
        this.pc.addTransceiver('audio', { direction: 'recvonly' });

        // 5. Create SDP Offer and send to Android device
        this.pc.createOffer().then(offer => {
            return this.pc.setLocalDescription(offer);
        }).then(() => {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({
                    type: 'offer',
                    sdp: this.pc.localDescription.sdp
                }));
            }
        }).catch(err => {
            console.error('[WebRTC] Create offer error:', err);
            this.options.onStatusChange('ERROR', 'Failed to create SDP Offer: ' + err.message);
        });
    }

    async handleSignalingMessage(msg) {
        if (msg.type === 'answer') {
            await this.pc.setRemoteDescription(new RTCSessionDescription({
                type: 'answer',
                sdp: msg.sdp
            }));
        } else if (msg.type === 'candidate') {
            if (msg.candidate) {
                await this.pc.addIceCandidate(new RTCIceCandidate({
                    candidate: msg.candidate,
                    sdpMid: msg.sdpMid,
                    sdpMLineIndex: msg.sdpMLineIndex
                }));
            }
        }
    }

    /**
     * Send binary control packet
     * @param {ArrayBuffer} buffer 
     */
    sendControl(buffer) {
        if (this.dataChannel && this.dataChannel.readyState === 'open') {
            this.dataChannel.send(buffer);
            return true;
        }
        return false;
    }

    startStatsMonitor() {
        this.lastStatsTime = performance.now();
        this.lastBytesReceived = 0;

        this.statsInterval = setInterval(async () => {
            if (!this.pc) return;
            try {
                const stats = await this.pc.getStats();
                let fps = 0;
                let rtt = 0;
                let bitrate = 0;
                let width = 0;
                let height = 0;
                let codec = 'H.264';
                let packetsLost = 0;

                stats.forEach(report => {
                    if (report.type === 'inbound-rtp' && report.kind === 'video') {
                        fps = report.framesPerSecond || 0;
                        packetsLost = report.packetsLost || 0;
                        width = report.frameWidth || 0;
                        height = report.frameHeight || 0;

                        const now = performance.now();
                        const timeDiff = (now - this.lastStatsTime) / 1000;
                        if (timeDiff > 0 && this.lastBytesReceived > 0) {
                            const bytesDiff = report.bytesReceived - this.lastBytesReceived;
                            bitrate = Math.round((bytesDiff * 8) / timeDiff / 1000); // kbps
                        }
                        this.lastBytesReceived = report.bytesReceived;
                        this.lastStatsTime = now;
                    }
                    if (report.type === 'candidate-pair' && report.state === 'succeeded') {
                        rtt = Math.round((report.currentRoundTripTime || 0) * 1000);
                    }
                });

                this.options.onStatsUpdate({
                    fps,
                    rtt,
                    bitrate,
                    width,
                    height,
                    codec,
                    packetsLost
                });
            } catch (e) {}
        }, 1000);
    }

    cleanup() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
            this.statsInterval = null;
        }
        if (this.dataChannel) {
            try { this.dataChannel.close(); } catch (_) {}
            this.dataChannel = null;
        }
        if (this.pc) {
            try { this.pc.close(); } catch (_) {}
            this.pc = null;
        }
        if (this.ws) {
            try {
                this.ws.onclose = null;
                this.ws.onerror = null;
                if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
                    this.ws.close(1000, 'page_unload');
                }
            } catch (_) {}
            this.ws = null;
        }
    }
}

