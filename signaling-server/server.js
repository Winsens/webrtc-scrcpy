const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 9000;
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', service: 'webrtc-scrcpy-signaling' }));
});

const wss = new WebSocketServer({ server });
const rooms = new Map(); // roomId -> { phone: ws, clients: Set<ws> }

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const roomId = url.searchParams.get('room') || 'default';
    const role = url.searchParams.get('role') || 'client'; // 'phone' or 'client'

    if (!rooms.has(roomId)) {
        rooms.set(roomId, { phone: null, clients: new Set() });
    }
    const room = rooms.get(roomId);

    if (role === 'phone') {
        room.phone = ws;
        console.log(`[+] Phone connected, joined room: ${roomId}`);
    } else {
        room.clients.add(ws);
        console.log(`[+] Web client joined room: ${roomId} (total clients: ${room.clients.size})`);
    }

    ws.on('message', (message) => {
        // Relay signaling messages
        if (role === 'phone') {
            // Broadcast phone messages to all web clients in the room
            for (const client of room.clients) {
                if (client.readyState === 1) client.send(message.toString());
            }
        } else {
            // Forward web client message to the phone
            if (room.phone && room.phone.readyState === 1) {
                room.phone.send(message.toString());
            }
        }
    });

    ws.on('close', () => {
        if (role === 'phone') {
            if (room.phone === ws) room.phone = null;
            console.log(`[-] Phone disconnected from room: ${roomId}`);
        } else {
            room.clients.delete(ws);
            console.log(`[-] Web client left room: ${roomId}`);
        }
        if (!room.phone && room.clients.size === 0) {
            rooms.delete(roomId);
        }
    });
});

server.listen(PORT, () => {
    console.log(`WebRTC-Scrcpy public signaling relay server listening on port: ${PORT}`);
});

