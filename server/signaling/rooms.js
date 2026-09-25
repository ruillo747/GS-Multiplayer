'use strict';

const crypto = require('crypto');
const config = require('../common/config');

const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';

// code -> { code, name, maxPlayers, hostId, players: Map<sessionId, {id, name, gsid}>, createdAt }
const rooms = new Map();

function cleanName(raw) {
    const s = String(raw === undefined || raw === null ? '' : raw)
        .replace(/[\u0000-\u001f\u007f]/g, '')
        .trim();
    return s.slice(0, 32) || 'Minecraft World';
}

function generateCode() {
    let code = '';
    do {
        code = '';
        for (let i = 0; i < config.roomCodeLength; i++) {
            code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
        }
    } while (rooms.has(code));
    return code;
}

function create(session, name, maxPlayers) {
    const room = {
        code: generateCode(),
        name: cleanName(name),
        maxPlayers: Math.max(2, Math.min(config.maxPlayers, Number.isFinite(+maxPlayers) && +maxPlayers > 0 ? +maxPlayers : config.maxPlayers)),
        hostId: session.id,
        players: new Map(),
        createdAt: Date.now()
    };
    room.players.set(session.id, { id: session.id, name: session.name, gsid: session.gsid });
    rooms.set(room.code, room);
    return room;
}

function get(code) {
    if (typeof code !== 'string') return null;
    return rooms.get(code.toUpperCase()) || null;
}

function join(room, session) {
    if (room.players.has(session.id)) return room;
    if (room.players.size >= room.maxPlayers) throw 'ROOM_FULL';
    room.players.set(session.id, { id: session.id, name: session.name, gsid: session.gsid });
    return room;
}

// Returns true if the leaver was the host.
function leave(room, session) {
    room.players.delete(session.id);
    return room.hostId === session.id;
}

function removeRoom(code) {
    rooms.delete(code);
}

// Removes the session from whatever room it is in. Returns { room, wasHost } or null.
function leaveBySession(session) {
    for (const room of rooms.values()) {
        if (room.players.has(session.id)) {
            const wasHost = leave(room, session);
            return { room, wasHost };
        }
    }
    return null;
}

function roomInfo(room) {
    const host = room.players.get(room.hostId);
    return {
        code: room.code,
        name: room.name,
        players: room.players.size,
        maxPlayers: room.maxPlayers,
        hostId: room.hostId,
        hostName: host ? host.name : 'Player'
    };
}

function peerList(room, excludeSessionId) {
    const out = [];
    for (const p of room.players.values()) {
        if (p.id !== excludeSessionId) out.push(p);
    }
    return out;
}

function list(limit) {
    const all = [...rooms.values()].sort((a, b) => b.createdAt - a.createdAt);
    return all.slice(0, limit || 50).map(roomInfo);
}

function count() {
    return rooms.size;
}

function stats() {
    let players = 0;
    for (const room of rooms.values()) players += room.players.size;
    return { rooms: rooms.size, players };
}

module.exports = {
    create,
    get,
    join,
    leave,
    leaveBySession,
    removeRoom,
    roomInfo,
    peerList,
    list,
    count,
    stats
};
