'use strict';

const dgram = require('dgram');
const config = require('../common/config');
const { verifyRelayToken } = require('../common/tokens');

// Wire format (all multi-byte integers big-endian):
//   reg  0x52 | <utf8 json { token }>       first packet from a peer
//   ack  0x53 | <utf8 json>                 relay -> peer reply to reg
//   ka   0x4B                               keepalive
//   data 0x44 | <u32 destId> | <payload>    forwarded verbatim to the destination peer
const MAGIC_REG = 0x52;
const MAGIC_ACK = 0x53;
const MAGIC_KA = 0x4b;
const MAGIC_DATA = 0x44;

function start(options = {}) {
    // peerId -> { roomId, addr, port, lastSeen }
    const peers = new Map();
    // "addr:port" -> peerId, for O(1) lookup of the sender of an incoming datagram
    const addrIndex = new Map();

    const socket = dgram.createSocket({ type: 'udp4' });

    socket.on('error', (err) => {
        console.error('[relay] socket error:', err);
    });

    socket.on('message', (msg, rinfo) => {
        if (msg.length < 1) return;
        const magic = msg[0];
        const key = `${rinfo.address}:${rinfo.port}`;

        if (magic === MAGIC_REG) {
            let parsed = null;
            try {
                parsed = JSON.parse(msg.slice(1).toString('utf8'));
            } catch (e) { /* fallthrough */ }
            const info = parsed ? verifyRelayToken(parsed.token) : null;
            if (!info) {
                sendJson(rinfo, { t: 'error', code: 'BAD_TOKEN' });
                return;
            }
            const prev = peers.get(info.peerId);
            if (prev && addrIndex.get(key) !== info.peerId) {
                // Address changed or id reused: drop stale address index entry.
                if (prev.addrKey && prev.addrKey !== key) addrIndex.delete(prev.addrKey);
            }
            const entry = {
                peerId: info.peerId,
                roomId: info.roomCode,
                addr: rinfo.address,
                port: rinfo.port,
                addrKey: key,
                lastSeen: Date.now()
            };
            peers.set(info.peerId, entry);
            addrIndex.set(key, info.peerId);
            sendJson(rinfo, { t: 'ok', peerId: info.peerId, roomId: info.roomCode, peersInRoom: countRoom(info.roomCode) });
            return;
        }

        const senderId = addrIndex.get(key);
        if (senderId === undefined) return; // not registered

        if (magic === MAGIC_KA) {
            const p = peers.get(senderId);
            if (p) p.lastSeen = Date.now();
            return;
        }

        if (magic === MAGIC_DATA) {
            if (msg.length < 5) return;
            const sender = peers.get(senderId);
            if (sender) sender.lastSeen = Date.now();
            const dest = peers.get(msg.readUInt32BE(1));
            if (!dest) return; // unknown destination: silently drop, clients detect via their own timeouts
            socket.send(msg, dest.port, dest.addr, (err) => {
                if (err) stats.drops++;
            });
            stats.forwarded++;
        }
    });

    const stats = { forwarded: 0, drops: 0 };

    function countRoom(roomId) {
        let n = 0;
        for (const p of peers.values()) if (p.roomId === roomId) n++;
        return n;
    }

    function sendJson(rinfo, obj) {
        const payload = Buffer.concat([Buffer.from([MAGIC_ACK]), Buffer.from(JSON.stringify(obj), 'utf8')]);
        socket.send(payload, rinfo.port, rinfo.address);
    }

    const sweeper = setInterval(() => {
        const now = Date.now();
        for (const [peerId, p] of peers) {
            if (now - p.lastSeen > config.relaySessionTtlMs) {
                peers.delete(peerId);
                if (addrIndex.get(p.addrKey) === peerId) addrIndex.delete(p.addrKey);
            }
        }
    }, 20000);
    if (sweeper.unref) sweeper.unref();

    return new Promise((resolve) => {
        socket.bind(options.port !== undefined ? options.port : config.relayPort, options.host || '0.0.0.0', () => {
            const port = socket.address().port;
            console.log(`[relay] listening on 0.0.0.0:${port}/udp`);
            resolve({
                socket,
                port,
                stats,
                stop() {
                    clearInterval(sweeper);
                    try { socket.close(); } catch (e) { /* ignore */ }
                }
            });
        });
    });
}

if (require.main === module) {
    start();
}

module.exports = { start };
