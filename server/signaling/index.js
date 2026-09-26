'use strict';

const http = require('http');
const { WebSocketServer } = require('ws');

const config = require('../common/config');
const { issueRelayToken } = require('../common/tokens');
const presence = require('./presence');
const rooms = require('./rooms');

const CODE_RE = /^[A-Z0-9]{1,10}$/;
const RATE_LIMIT = 40;          // messages per window
const RATE_WINDOW_MS = 5000;

let nextSessionId = 1;

function start(options = {}) {
    const sessions = new Map(); // sessionId -> session

    // ---- presence wiring -------------------------------------------------
    presence.setOnChange((watcherSessionId, entry) => {
        const target = sessions.get(watcherSessionId);
        if (target) send(target, { t: 'presence.update', entry });
    });

    // ---- http (health/stats) ---------------------------------------------
    const startedAt = Date.now();
    const server = http.createServer((req, res) => {
        let pathname = '/';
        try {
            pathname = new URL(req.url, 'http://localhost').pathname;
        } catch (e) { /* fallthrough */ }
        if (pathname === '/') {
            return html(res, 200, statusPage({
                uptimeSec: Math.floor((Date.now() - startedAt) / 1000),
                sessions: sessions.size,
                rooms: rooms.stats(),
                presence: presence.stats(),
                relayEnabled: !!config.relayPublicHost,
                relayHost: config.relayPublicHost || '(not configured)',
                relayPort: config.relayPort
            }));
        }
        if (pathname === '/health') {
            return json(res, 200, {
                ok: true,
                service: 'gs-signaling',
                version: '1.0.0',
                uptimeSec: Math.floor(process.uptime()),
                sessions: sessions.size,
                rooms: rooms.count(),
                presence: presence.stats()
            });
        }
        if (pathname === '/stats') {
            return json(res, 200, {
                service: 'gs-signaling',
                uptimeSec: Math.floor(process.uptime()),
                sessions: sessions.size,
                presence: presence.stats(),
                rooms: rooms.stats(),
                roomList: rooms.list(20)
            });
        }
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('not found\n');
    });

    // ---- websocket --------------------------------------------------------
    const wss = new WebSocketServer({ server, maxPayload: 64 * 1024 });

    wss.on('connection', (socket, req) => {
        const session = {
            id: nextSessionId++,
            socket,
            ip: req.socket.remoteAddress || 'unknown',
            name: 'Player',
            gsid: null,
            roomCode: null,
            msgs: 0,
            msgsWindow: Date.now()
        };
        sessions.set(session.id, session);
        socket.on('message', (data) => onMessage(session, data));
        socket.on('close', () => onDisconnect(session));
        socket.on('error', () => { /* close handler does the cleanup */ });
        send(session, {
            t: 'welcome',
            sessionId: session.id,
            heartbeatMs: config.heartbeatIntervalMs,
            maxPlayers: config.maxPlayers
        });
    });

    // ---- message handlers -------------------------------------------------
    const handlers = {
        hello(session, m) {
            session.name = cleanName(m.name, 'Player');
            session.gsid = cleanGsid(m.gsid);
            if (session.gsid) presence.markOnline(session.gsid, session.name);
            send(session, { t: 'ready', name: session.name });
        },

        'room.create'(session, m) {
            leaveRoom(session);
            const room = rooms.create(session, m.name, m.maxPlayers);
            session.roomCode = room.code;
            if (session.gsid) presence.setStatus(session.gsid, 'in-room', room.code);
            send(session, { t: 'room.created', room: rooms.roomInfo(room) });
        },

        'room.join'(session, m) {
            const code = String(m.code || '').toUpperCase();
            if (!CODE_RE.test(code)) throw 'BAD_CODE';
            const room = rooms.get(code);
            if (!room) throw 'ROOM_NOT_FOUND';
            leaveRoom(session);
            try {
                rooms.join(room, session);
            } catch (e) {
                throw typeof e === 'string' ? e : 'ROOM_FULL';
            }
            session.roomCode = room.code;
            if (session.gsid) presence.setStatus(session.gsid, 'in-room', room.code);
            send(session, {
                t: 'room.joined',
                room: rooms.roomInfo(room),
                peers: rooms.peerList(room, session.id)
            });
            for (const peer of rooms.peerList(room, session.id)) {
                const target = sessions.get(peer.id);
                if (target) send(target, { t: 'room.peerJoined', peer: { id: session.id, name: session.name, gsid: session.gsid }, room: rooms.roomInfo(room) });
            }
        },

        'room.leave'(session) {
            leaveRoom(session);
            send(session, { t: 'room.left' });
        },

        'room.list'(session, m) {
            const q = typeof m.query === 'string' ? m.query.trim().toLowerCase() : '';
            let list = rooms.list(50);
            if (q) list = list.filter((r) => r.name.toLowerCase().includes(q) || r.code.includes(q.toUpperCase()));
            send(session, { t: 'room.listed', rooms: list });
        },

        signal(session, m) {
            if (!session.roomCode) throw 'NOT_IN_ROOM';
            const to = sessions.get(Number(m.to));
            if (!to || to.roomCode !== session.roomCode) throw 'PEER_NOT_FOUND';
            if (m.data === null || typeof m.data !== 'object') throw 'BAD_DATA';
            send(to, { t: 'signal', from: session.id, name: session.name, data: m.data });
        },

        invite(session, m) {
            const code = String(m.roomCode || '').toUpperCase();
            const room = rooms.get(code);
            if (!room || room.hostId !== session.id) throw 'NOT_HOST';
            const target = findByGsid(String(m.to || ''));
            if (!target || target.id === session.id) throw 'TARGET_OFFLINE';
            send(target, {
                t: 'invite',
                from: { sessionId: session.id, name: session.name, gsid: session.gsid },
                roomCode: room.code,
                roomName: room.name
            });
        },

        'invite.response'(session, m) {
            const to = sessions.get(Number(m.to));
            if (!to) return;
            send(to, {
                t: 'invite.response',
                from: { sessionId: session.id, name: session.name, gsid: session.gsid },
                accepted: !!m.accepted
            });
        },

        'presence.watch'(session, m) {
            const gsids = (Array.isArray(m.gsids) ? m.gsids : [])
                .slice(0, 64)
                .map((g) => cleanGsid(g))
                .filter(Boolean);
            presence.addWatcher(session.id, gsids);
            send(session, { t: 'presence.snapshot', entries: presence.snapshot(gsids) });
        },

        heartbeat(session) {
            if (session.gsid) presence.heartbeat(session.gsid);
        },

        'relay.request'(session) {
            if (!session.roomCode) throw 'NOT_IN_ROOM';
            if (!config.relayPublicHost) throw 'RELAY_DISABLED';
            send(session, {
                t: 'relay.token',
                host: config.relayPublicHost,
                port: config.relayPort,
                token: issueRelayToken(session.roomCode, session.id),
                roomCode: session.roomCode,
                peerId: session.id,
                ttlMs: config.relayTokenTtlMs
            });
        },

        ping(session, m) {
            send(session, { t: 'pong', clientTime: m.t, serverTime: Date.now() });
        }
    };

    // ---- internals ----------------------------------------------------------
    function onMessage(session, raw) {
        const now = Date.now();
        if (now - session.msgsWindow > RATE_WINDOW_MS) {
            session.msgsWindow = now;
            session.msgs = 0;
        }
        if (++session.msgs > RATE_LIMIT) {
            session.socket.close(1008, 'rate limit');
            return;
        }
        let m;
        try {
            m = JSON.parse(raw.toString('utf8'));
        } catch (e) {
            return;
        }
        if (!m || typeof m.t !== 'string') return;
        const handler = handlers[m.t];
        if (!handler) return sendError(session, m.t, 'UNKNOWN_TYPE');
        try {
            handler(session, m);
        } catch (e) {
            if (typeof e === 'string') return sendError(session, m.t, e);
            console.error('[signaling] handler error:', e);
        }
    }

    function leaveRoom(session) {
        if (!session.roomCode) return;
        session.roomCode = null;
        if (session.gsid) presence.setStatus(session.gsid, 'online', null);
        const left = rooms.leaveBySession(session);
        if (!left) return;
        const { room, wasHost } = left;
        if (wasHost || room.players.size === 0) {
            rooms.removeRoom(room.code);
            broadcast(room, { t: 'room.closed', code: room.code, reason: 'host_left' });
        } else {
            broadcast(room, { t: 'room.update', room: rooms.roomInfo(room) });
        }
    }

    function broadcast(room, msg) {
        for (const peerId of room.players.keys()) {
            const target = sessions.get(peerId);
            if (target) send(target, msg);
        }
    }

    function findByGsid(gsid) {
        if (!gsid) return null;
        for (const s of sessions.values()) {
            if (s.gsid === gsid && s.socket.readyState === 1) return s;
        }
        return null;
    }

    function onDisconnect(session) {
        sessions.delete(session.id);
        presence.removeWatcher(session.id);
        if (session.gsid) presence.setOffline(session.gsid);
        leaveRoom(session);
    }

    const sweeper = setInterval(() => presence.sweepAndCollect(Date.now()), 10000);
    if (sweeper.unref) sweeper.unref();

    return new Promise((resolve) => {
        server.listen(options.port !== undefined ? options.port : config.signalingPort, options.host || '0.0.0.0', () => {
            resolve({
                server,
                wss,
                port: server.address().port,
                stop() {
                    clearInterval(sweeper);
                    for (const s of sessions.values()) {
                        try { s.socket.close(1001, 'server stopping'); } catch (e) { /* ignore */ }
                    }
                    server.close();
                }
            });
        });
    });
}

// ---- helpers ---------------------------------------------------------------
function send(session, obj) {
    if (session.socket.readyState === 1) {
        session.socket.send(JSON.stringify(obj));
    }
}

function sendError(session, of, code) {
    send(session, { t: 'error', of, code });
}

function json(res, code, obj) {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
}

function html(res, code, body) {
    res.writeHead(code, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(body);
}

// Minimal self-contained status dashboard for the live preview / quick checks.
function statusPage(s) {
    const esc = (t) => String(t).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    return '<!DOCTYPE html><html><head><meta charset="utf-8">'
        + '<meta http-equiv="refresh" content="5">'
        + '<title>GS Multiplayer server</title></head>'
        + '<body style="margin:0;font-family:Segoe UI,Roboto,sans-serif;background:#14162b;color:#e8eaf6;'
        + 'display:flex;align-items:center;justify-content:center;min-height:100vh">'
        + '<div style="max-width:460px;width:92%;background:#1d2040;border-radius:14px;padding:28px 32px;'
        + 'box-shadow:0 8px 40px rgba(0,0,0,.45)">'
        + '<div style="font-size:13px;letter-spacing:.14em;color:#8f9bd4;text-transform:uppercase">GS Multiplayer</div>'
        + '<h1 style="margin:6px 0 18px;font-size:26px">Signaling server</h1>'
        + '<div style="display:flex;align-items:center;gap:9px;margin-bottom:20px">'
        + '<span style="width:11px;height:11px;border-radius:50%;background:#3ddc84;box-shadow:0 0 10px #3ddc84"></span>'
        + '<span style="font-size:15px;color:#b6c0f2">online &middot; uptime ' + s.uptimeSec + 's</span></div>'
        + '<table style="width:100%;border-collapse:collapse;font-size:14px">'
        + row('Sessions', s.sessions)
        + row('Rooms / players', s.rooms.rooms + ' / ' + s.rooms.players)
        + row('Presence online', s.presence.online)
        + row('Presence watchers', s.presence.watchers)
        + row('Relay fallback', s.relayEnabled ? 'enabled &middot; ' + esc(s.relayHost) + ':' + s.relayPort + '/udp' : 'disabled (set RELAY_PUBLIC_HOST)')
        + '</table>'
        + '<div style="margin-top:20px;font-size:12.5px;color:#7f8ac9;line-height:1.7">'
        + 'WebSocket: <code style="color:#9fb4ff">ws://&lt;this-host&gt;:35500/</code><br>'
        + 'Endpoints: <code style="color:#9fb4ff">/health</code> &middot; <code style="color:#9fb4ff">/stats</code>'
        + ' &middot; relay UDP ' + s.relayPort
        + '<br>Auto-refresh every 5s</div>'
        + '</div></body></html>';

    function row(label, value) {
        return '<tr><td style="padding:7px 0;color:#8f9bd4">' + label
            + '</td><td style="padding:7px 0;text-align:right;font-weight:600">' + value + '</td></tr>';
    }
}

function cleanName(raw, fallback) {
    const s = String(raw === undefined || raw === null ? '' : raw).replace(/[\u0000-\u001f\u007f]/g, '').trim();
    return (s.slice(0, 24) || fallback);
}

function cleanGsid(raw) {
    const s = String(raw === undefined || raw === null ? '' : raw).trim().toUpperCase();
    return /^GS-[A-Z0-9]{4,16}$/.test(s) ? s : null;
}

if (require.main === module) {
    start().then(({ port }) => {
        console.log(`[signaling] listening on 0.0.0.0:${port}`);
        if (config.relaySecretEphemeral) {
            console.warn('[signaling] WARNING: RELAY_SECRET is not set, using an ephemeral secret.');
        }
        if (!config.relayPublicHost) {
            console.warn('[signaling] WARNING: RELAY_PUBLIC_HOST is not set, relay fallback is disabled.');
        }
    });
}

module.exports = { start };
