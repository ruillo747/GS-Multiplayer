'use strict';

const crypto = require('crypto');
const config = require('./config');

function hmac(payload) {
    return crypto.createHmac('sha256', config.relaySecret).update(payload).digest('base64url');
}

// Token format: <roomCode>.<peerId>.<expiryMs>.<hmac>
// The relay verifies it without talking to the signaling server.
function issueRelayToken(roomCode, peerId) {
    const exp = Date.now() + config.relayTokenTtlMs;
    const payload = `${roomCode}.${peerId}.${exp}`;
    return `${payload}.${hmac(payload)}`;
}

function verifyRelayToken(token) {
    if (typeof token !== 'string') return null;
    const parts = token.split('.');
    if (parts.length !== 4) return null;
    const [roomCode, peerIdRaw, expRaw, sig] = parts;
    if (!/^[A-Z0-9]{1,10}$/.test(roomCode)) return null;
    const expected = hmac(`${roomCode}.${peerIdRaw}.${expRaw}`);
    const a = Buffer.from(sig);
    const b = Buffer.from(expected);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    const exp = Number(expRaw);
    if (!Number.isFinite(exp) || Date.now() > exp) return null;
    const peerId = Number(peerIdRaw);
    if (!Number.isInteger(peerId) || peerId < 0) return null;
    return { roomCode, peerId, exp };
}

module.exports = { issueRelayToken, verifyRelayToken };
