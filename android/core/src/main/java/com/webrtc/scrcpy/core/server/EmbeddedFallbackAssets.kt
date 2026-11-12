package com.webrtc.scrcpy.core.server

object EmbeddedFallbackAssets {
    const val FALLBACK_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>WebRTC-Scrcpy Remote</title>
    <style>
        * { margin:0; padding:0; box-sizing:border-box; }
        body { background:#0d1117; color:#c9d1d9; font-family:sans-serif; height:100vh; display:flex; flex-direction:column; }
        #header { height:40px; background:#161b22; display:flex; align-items:center; justify-content:space-between; padding:0 12px; font-size:12px; }
        #view { flex:1; position:relative; display:flex; align-items:center; justify-content:center; }
        video { max-width:100%; max-height:100%; object-fit:contain; }
        #overlay { position:absolute; top:0; left:0; width:100%; height:100%; cursor:crosshair; touch-action:none; }
        #nav { height:48px; background:#161b22; display:flex; justify-content:space-around; align-items:center; }
        button { background:#21262d; border:1px solid #30363d; color:#c9d1d9; padding:6px 14px; border-radius:4px; cursor:pointer; }
    </style>
</head>
<body>
    <div id="header">
        <span>WebRTC-Scrcpy (Built-in Fallback)</span>
        <span id="status">Connecting signaling...</span>
    </div>
    <div id="view">
        <video id="v" autoplay playsinline muted></video>
        <div id="overlay"></div>
    </div>
    <div id="nav">
        <button onclick="sendCmd(1)">Back</button>
        <button onclick="sendCmd(2)">Home</button>
        <button onclick="sendCmd(3)">Recents</button>
    </div>
    <script>
        const v = document.getElementById('v');
        const st = document.getElementById('status');
        const ov = document.getElementById('overlay');
        const token = new URLSearchParams(location.search).get('token') || '';
        const ws = new WebSocket((location.protocol==='https:'?'wss:':'ws:') + '//' + location.host + '/ws?token=' + encodeURIComponent(token));
        let pc, dc;

        ws.onopen = () => {
            st.innerText = 'Signaling connected';
            pc = new RTCPeerConnection({ iceServers:[{urls:'stun:stun.l.google.com:19302'}] });
            dc = pc.createDataChannel("control", { ordered:true });
            dc.binaryType = "arraybuffer";
            dc.onopen = () => { st.innerText = 'Control ready'; };
            pc.ontrack = (e) => {
                if (e.streams && e.streams[0]) {
                    v.srcObject = e.streams[0];
                } else if (e.track) {
                    v.srcObject = new MediaStream([e.track]);
                }
                v.play().catch(() => {});
            };
            pc.onicecandidate = (e) => {
                if(e.candidate) ws.send(JSON.stringify({ type:'candidate', candidate:e.candidate.candidate, sdpMid:e.candidate.sdpMid, sdpMLineIndex:e.candidate.sdpMLineIndex }));
            };
            pc.addTransceiver('video', {direction:'recvonly'});
            pc.createOffer().then(o => pc.setLocalDescription(o)).then(() => {
                ws.send(JSON.stringify({ type:'offer', sdp:pc.localDescription.sdp }));
            });
        };
        ws.onmessage = async (e) => {
            const m = JSON.parse(e.data);
            if(m.type==='answer') await pc.setRemoteDescription(new RTCSessionDescription({type:'answer', sdp:m.sdp}));
            else if(m.type==='candidate') await pc.addIceCandidate(new RTCIceCandidate({candidate:m.candidate, sdpMid:m.sdpMid, sdpMLineIndex:m.sdpMLineIndex}));
        };
        function sendTouch(act, e) {
            if(!dc || dc.readyState!=='open') return;
            const r = ov.getBoundingClientRect();
            const nx = Math.floor(Math.max(0, Math.min(1, (e.clientX - r.left)/r.width)) * 10000);
            const ny = Math.floor(Math.max(0, Math.min(1, (e.clientY - r.top)/r.height)) * 10000);
            const b = new ArrayBuffer(13);
            const dv = new DataView(b);
            dv.setUint8(0, 1); dv.setUint8(1, act); dv.setUint8(2, 0); dv.setUint32(3, nx); dv.setUint32(7, ny); dv.setUint16(11, 1000);
            dc.send(b);
        }
        ov.onmousedown = (e) => sendTouch(0, e);
        window.onmouseup = (e) => sendTouch(1, e);
        ov.onmousemove = (e) => { if(e.buttons===1) sendTouch(2, e); };
        window.sendCmd = (id) => {
            if(!dc || dc.readyState!=='open') return;
            const b = new ArrayBuffer(2);
            new DataView(b).setUint8(0, 5);
            new DataView(b).setUint8(1, id);
            dc.send(b);
        };
    </script>
</body>
</html>"""
}

