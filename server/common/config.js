'use strict';

const crypto = require('crypto');

function num(name, def) {
    const raw = process.env[name];
    if (raw === undefined || raw === '') return def;
    const n = Number(raw);
    if (!Number.isFinite(n) || n < 0) throw new Error(`Invalid value for ${name}: ${raw}`);
    return n;
}

function str(name, def) {
    const raw = process.env[name];
    return raw === undefined || raw === '' ? def : raw;
}

const config = {
    // Pterodactyl and similar panels always provide SERVER_PORT (the allocation);
    // it is the natural default for signaling when SIGNALING_PORT is not set.
    signalingPort: num('SIGNALING_PORT', num('SERVER_PORT', 35500)),
    relayPort: num('RELAY_PORT', 35501),
    // Public IP/hostname advertised to clients in relay tokens (must be reachable from outside).
    relayPublicHost: str('RELAY_PUBLIC_HOST', ''),
    // Shared HMAC secret used to sign relay tokens. Generate: node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
    relaySecret: str('RELAY_SECRET', ''),
    maxPlayers: num('MAX_PLAYERS', 4),
    roomCodeLength: 6,
    presenceTtlMs: num('PRESENCE_TTL_MS', 60000),
    heartbeatIntervalMs: 15000,
    relaySessionTtlMs: num('RELAY_SESSION_TTL_MS', 120000),
    relayTokenTtlMs: num('RELAY_TOKEN_TTL_MS', 2 * 60 * 60 * 1000)
};

// A random per-process secret keeps the relay usable for single-process deployments
// where signaling and relay share the environment, but production setups MUST set RELAY_SECRET.
if (!config.relaySecret) {
    config.relaySecret = crypto.randomBytes(32).toString('hex');
    config.relaySecretEphemeral = true;
}

module.exports = config;
