'use strict';

const config = require('../common/config');

// Ephemeral presence: gsid -> { gsid, name, status: 'online' | 'in-room', roomCode, lastSeen }.
// Nothing here is persisted; entries disappear shortly after clients stop heartbeating.
const entries = new Map();

// watcherSessionId -> Set<gsid>
const watchers = new Map();

let onChange = null;

function setOnChange(cb) {
    onChange = typeof cb === 'function' ? cb : null;
}

function touch(entry, now) {
    entry.lastSeen = now;
}

function markOnline(gsid, name) {
    if (!gsid) return;
    const now = Date.now();
    let entry = entries.get(gsid);
    if (!entry) {
        entry = { gsid, name: name || 'Player', status: 'online', roomCode: null, lastSeen: now };
        entries.set(gsid, entry);
        notify(gsid);
    } else {
        if (name) entry.name = name;
        touch(entry, now);
    }
}

function heartbeat(gsid) {
    const entry = entries.get(gsid);
    if (entry) touch(entry, Date.now());
}

function setStatus(gsid, status, roomCode) {
    const entry = entries.get(gsid);
    if (!entry) return;
    if (entry.status !== status || entry.roomCode !== roomCode) {
        entry.status = status;
        entry.roomCode = roomCode || null;
        notify(gsid);
    }
    touch(entry, Date.now());
}

function setOffline(gsid) {
    if (entries.delete(gsid)) notify(gsid);
}

function get(gsid) {
    return entries.get(gsid) || null;
}

function snapshot(gsids) {
    const out = [];
    for (const gsid of gsids) {
        const e = entries.get(gsid);
        out.push(e ? publicEntry(e) : { gsid, status: 'offline' });
    }
    return out;
}

function publicEntry(e) {
    return { gsid: e.gsid, name: e.name, status: e.status, roomCode: e.roomCode };
}

function addWatcher(sessionId, gsids) {
    watchers.set(sessionId, new Set(gsids));
}

function removeWatcher(sessionId) {
    watchers.delete(sessionId);
}

function notify(gsid) {
    if (!onChange) return;
    const e = entries.get(gsid);
    const payload = e ? publicEntry(e) : { gsid, status: 'offline' };
    for (const [sessionId, gsids] of watchers) {
        if (gsids.has(gsid)) onChange(sessionId, payload);
    }
}

// Drops stale entries, returns the gsids that expired so callers can notify watchers.
function sweepAndCollect(now) {
    const expired = [];
    for (const [gsid, e] of entries) {
        if (now - e.lastSeen > config.presenceTtlMs) {
            entries.delete(gsid);
            expired.push(gsid);
        }
    }
    for (const gsid of expired) notify(gsid);
    return expired;
}

function stats() {
    return { online: entries.size, watchers: watchers.size };
}

module.exports = {
    setOnChange,
    markOnline,
    heartbeat,
    setStatus,
    setOffline,
    get,
    snapshot,
    addWatcher,
    removeWatcher,
    sweepAndCollect,
    stats
};
