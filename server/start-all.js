'use strict';

/**
 * Starts the relay as a child process and signaling in this one.
 * For hosts that expose a single startup command (Pterodactyl and similar panels):
 *   node start-all.js
 * Both processes share the environment (SIGNALING_PORT, RELAY_PORT, RELAY_SECRET...).
 */

const { spawn } = require('child_process');
const path = require('path');
const config = require('./common/config');

const relay = spawn(process.execPath, [path.join(__dirname, 'relay', 'index.js')], {
    stdio: 'inherit',
    env: process.env
});
relay.on('exit', (code) => {
    console.error(`[start-all] relay exited with code ${code}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
        relay.kill(signal);
        process.exit(0);
    });
}

require('./signaling/index.js').start().then(({ port }) => {
    console.log(`[start-all] signaling on 0.0.0.0:${port}, relay on udp/${config.relayPort}`);
}).catch((err) => {
    console.error('[start-all] signaling failed:', err);
    relay.kill('SIGTERM');
    process.exit(1);
});
