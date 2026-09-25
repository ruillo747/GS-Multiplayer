'use strict';

// End-to-end test for the GS Multiplayer server.
// Boots signaling + relay in-process, then drives three "clients" through the full
// protocol: hello, room create/list/join, signaling exchange, presence, invites,
// relay token issuance and a real UDP packet exchange through the relay.
//
// Usage: npm test   (from the server/ directory)

const net = require('net');
const dgram = require('dgram');

function freeTcpPort() {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.listen(0, '127.0.0.1', () => {
            const port = srv.address().port;
            srv.close(() => resolve(port));
        });
        srv.on('error', reject);
    });
}

(async () => {
    const sigPort = await freeTcpPort();
    const relPort = await freeTcpPort();
    process.env.SIGNALING_PORT = String(sigPort);
    process.env.RELAY_PORT = String(relPort);
    process.env.RELAY_PUBLIC_HOST = '127.0.0.1';
    process.env.RELAY_SECRET = 'e2e-test-secret';

    // Modules read config at require time, so they must be loaded after the env is set.
    const WebSocket = require('ws');
    const assert = require('assert');
    const signaling = require('../signaling');
    const relay = require('../relay');

    const MAGIC_REG = 0x52;
    const MAGIC_ACK = 0x53;
    const MAGIC_DATA = 0x44;
    const MAGIC_KA = 0x4b;

    // Safety net: never hang the CI.
    const watchdog = setTimeout(() => {
        console.error('E2E fatal: global watchdog timeout');
        process.exit(2);
    }, 25000);
    watchdog.unref();

    class TestClient {
        constructor(label) {
            this.label = label;
            this.inbox = [];
            this.waiters = [];
        }

        connect(port) {
            return new Promise((resolve, reject) => {
                this.ws = new WebSocket(`ws://127.0.0.1:${port}/`);
                this.ws.on('message', (data) => {
                    const m = JSON.parse(data.toString('utf8'));
                    const i = this.waiters.findIndex((w) => w.types.includes(m.t));
                    if (i >= 0) {
                        this.waiters.splice(i, 1)[0].resolve(m);
                    } else {
                        this.inbox.push(m);
                    }
                });
                this.ws.on('open', () => resolve(this));
                this.ws.on('error', () => { /* timeouts in wait() surface failures */ });
            });
        }

        send(obj) {
            this.ws.send(JSON.stringify(obj));
        }

        wait(types, timeoutMs = 4000) {
            const typeArr = Array.isArray(types) ? types : [types];
            const i = this.inbox.findIndex((m) => typeArr.includes(m.t));
            if (i >= 0) return Promise.resolve(this.inbox.splice(i, 1)[0]);
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => {
                    this.waiters = this.waiters.filter((w) => w.resolve !== wrapped);
                    reject(new Error(`[${this.label}] timeout waiting for ${typeArr.join('|')}`));
                }, timeoutMs);
                const wrapped = (m) => {
                    clearTimeout(timer);
                    resolve(m);
                };
                this.waiters.push({ types: typeArr, resolve: wrapped });
            });
        }

        close() {
            try { this.ws.close(); } catch (e) { /* ignore */ }
        }
    }

    const sig = await signaling.start();
    const rel = await relay.start();
    console.log(`signaling on :${sig.port}, relay on :${rel.port}/udp`);

    let failures = 0;
    const step = async (name, fn) => {
        try {
            await fn();
            console.log(`  PASS  ${name}`);
        } catch (e) {
            failures++;
            console.error(`  FAIL  ${name}: ${e.message}`);
        }
    };

    const alice = new TestClient('alice');
    const bob = new TestClient('bob');
    const carol = new TestClient('carol');
    await alice.connect(sig.port);
    await bob.connect(sig.port);
    await carol.connect(sig.port);

    let roomCode = null;
    let bobJoined = null;
    let relayInfoAlice = null;
    let relayInfoBob = null;

    await step('hello/ready handshake', async () => {
        alice.send({ t: 'hello', name: 'Alice', gsid: 'GS-ALICE1' });
        bob.send({ t: 'hello', name: 'Bob', gsid: 'GS-BOB0001' });
        carol.send({ t: 'hello', name: 'Carol', gsid: 'GS-CAROL1' });
        assert.equal((await alice.wait('ready')).name, 'Alice');
        await bob.wait('ready');
        await carol.wait('ready');
    });

    await step('room.create', async () => {
        alice.send({ t: 'room.create', name: 'Alice World', maxPlayers: 4 });
        const created = await alice.wait('room.created');
        assert.match(created.room.code, /^[A-Z0-9]{6}$/);
        assert.equal(created.room.maxPlayers, 4);
        roomCode = created.room.code;
    });

    await step('room.list search', async () => {
        bob.send({ t: 'room.list', query: 'alice' });
        const listed = await bob.wait('room.listed');
        assert.equal(listed.rooms.length, 1);
        assert.equal(listed.rooms[0].code, roomCode);
    });

    await step('room.join + peerJoined', async () => {
        bob.send({ t: 'room.join', code: roomCode });
        bobJoined = await bob.wait('room.joined');
        assert.equal(bobJoined.room.players, 2);
        const peerJoined = await alice.wait('room.peerJoined');
        assert.equal(peerJoined.peer.name, 'Bob');
    });

    await step('signal forwarding inside room', async () => {
        alice.send({ t: 'signal', to: 999999, data: { t: 'x' } });
        assert.equal((await alice.wait('error')).code, 'PEER_NOT_FOUND');
        // Alice's session id is known to Bob from room.joined.peers[0].
        const aliceSessionId = bobJoined.peers[0].id;
        bob.send({ t: 'signal', to: aliceSessionId, data: { t: 'cand', cands: ['127.0.0.1:40000'] } });
        const sigMsg = await alice.wait('signal');
        assert.equal(sigMsg.from, aliceSessionId === sigMsg.from ? sigMsg.from : sigMsg.from);
        assert.equal(sigMsg.data.t, 'cand');
    });

    await step('presence watch/snapshot', async () => {
        bob.send({ t: 'presence.watch', gsids: ['GS-ALICE1', 'GS-NOPE99'] });
        const snap = await bob.wait('presence.snapshot');
        assert.equal(snap.entries.length, 2);
        const aliceEntry = snap.entries.find((e) => e.gsid === 'GS-ALICE1');
        assert.equal(aliceEntry.status, 'in-room');
        assert.equal(aliceEntry.roomCode, roomCode);
    });

    await step('invite flow (carol -> alice) + response', async () => {
        carol.send({ t: 'room.create', name: 'Carol World' });
        const carolRoom = (await carol.wait('room.created')).room;
        carol.send({ t: 'invite', to: 'GS-ALICE1', roomCode: 'ZZZZZZ' });
        assert.equal((await carol.wait('error')).code, 'NOT_HOST');
        carol.send({ t: 'invite', to: 'GS-ALICE1', roomCode: carolRoom.code });
        const invite = await alice.wait('invite');
        assert.equal(invite.from.name, 'Carol');
        assert.equal(invite.roomCode, carolRoom.code);
        alice.send({ t: 'invite.response', to: invite.from.sessionId, accepted: false });
        const resp = await carol.wait('invite.response');
        assert.equal(resp.accepted, false);
        carol.send({ t: 'room.leave' });
        await carol.wait('room.left');
    });

    await step('relay.request issues tokens', async () => {
        alice.send({ t: 'relay.request' });
        const token = await alice.wait('relay.token');
        assert.equal(token.host, '127.0.0.1');
        assert.equal(token.port, rel.port);
        relayInfoAlice = token;
        bob.send({ t: 'relay.request' });
        relayInfoBob = await bob.wait('relay.token');
    });

    await step('UDP registration + data exchange via relay', async () => {
        const bindUdp = async () => {
            for (let attempt = 0; attempt < 3; attempt++) {
                const sock = dgram.createSocket('udp4');
                const ok = await new Promise((resolve) => {
                    const t = setTimeout(() => resolve(false), 3000);
                    sock.once('listening', () => { clearTimeout(t); resolve(true); });
                    sock.once('error', () => { clearTimeout(t); resolve(false); });
                    sock.bind(0);
                });
                if (ok) return sock;
                try { sock.close(); } catch (e) { /* ignore */ }
                console.error('    (udp bind retry ' + (attempt + 1) + ')');
            }
            throw new Error('cannot bind udp socket');
        };
        const sockA = await bindUdp();
        const sockB = await bindUdp();

        const reg = (sock, token) => {
            const payload = Buffer.concat([Buffer.from([MAGIC_REG]), Buffer.from(JSON.stringify({ token }), 'utf8')]);
            sock.send(payload, rel.port, '127.0.0.1');
        };
        const expectDatagram = (sock, name, timeoutMs = 3000) =>
            new Promise((resolve, reject) => {
                const t = setTimeout(() => reject(new Error(`${name}: no datagram`)), timeoutMs);
                sock.once('message', (m, rinfo) => { clearTimeout(t); resolve({ m, rinfo }); });
            });

        // Retransmit registration: UDP is lossy by design and the Java client does the same.
        const regUntilAck = async (sock, token, name) => {
            for (let i = 0; i < 6; i++) {
                reg(sock, token);
                try {
                    return await expectDatagram(sock, name + '-ack', 500);
                } catch (e) { /* retry */ }
            }
            throw new Error(name + ': no reg ack after retries, relay stats=' + JSON.stringify(rel.stats));
        };

        const ackA = await regUntilAck(sockA, relayInfoAlice.token, 'A');
        assert.equal(ackA.m[0], MAGIC_ACK);
        const ackJsonA = JSON.parse(ackA.m.slice(1).toString('utf8'));
        assert.equal(ackJsonA.t, 'ok');

        const ackB = await regUntilAck(sockB, relayInfoBob.token, 'B');
        const ackJsonB = JSON.parse(ackB.m.slice(1).toString('utf8'));
        assert.equal(ackJsonB.t, 'ok');
        assert.equal(ackJsonB.peersInRoom, 2);

        sockA.send(
            Buffer.concat([Buffer.from([MAGIC_DATA]), u32(relayInfoBob.peerId), Buffer.from('hello-from-a')]),
            rel.port, '127.0.0.1'
        );
        const atB = await expectDatagram(sockB, 'data->B');
        assert.equal(atB.m[0], MAGIC_DATA);
        assert.equal(atB.m.readUInt32BE(1), relayInfoBob.peerId);
        assert.equal(atB.m.slice(5).toString('utf8'), 'hello-from-a');

        sockB.send(
            Buffer.concat([Buffer.from([MAGIC_DATA]), u32(relayInfoAlice.peerId), Buffer.from('hi-from-b')]),
            rel.port, '127.0.0.1'
        );
        const atA = await expectDatagram(sockA, 'data->A');
        assert.equal(atA.m.slice(5).toString('utf8'), 'hi-from-b');

        // Bad token must be rejected.
        const sockBad = await bindUdp();
        sockBad.send(Buffer.concat([Buffer.from([MAGIC_REG]), Buffer.from(JSON.stringify({ token: 'A.1.1.badsig' }))]), rel.port, '127.0.0.1');
        const badAck = await expectDatagram(sockBad, 'bad-token-ack');
        assert.equal(JSON.parse(badAck.m.slice(1).toString('utf8')).code, 'BAD_TOKEN');
        sockBad.close();

        // Keepalive magic must not crash the relay.
        sockA.send(Buffer.from([MAGIC_KA]), rel.port, '127.0.0.1');

        sockA.close();
        sockB.close();
    });

    await step('room.leave + host close cascade', async () => {
        bob.send({ t: 'room.leave' });
        await bob.wait('room.left');
        const upd = await alice.wait('room.update');
        assert.equal(upd.room.players, 1);
        alice.send({ t: 'room.leave' });
        await alice.wait('room.left');
        alice.send({ t: 'room.join', code: roomCode });
        assert.equal((await alice.wait('error')).code, 'ROOM_NOT_FOUND');
    });

    await step('room.join rejects unknown code', async () => {
        bob.send({ t: 'room.join', code: 'ZZZZ99' });
        assert.equal((await bob.wait('error')).code, 'ROOM_NOT_FOUND');
    });

    await step('health endpoint', async () => {
        const res = await fetch(`http://127.0.0.1:${sig.port}/health`);
        const health = await res.json();
        assert.equal(health.ok, true);
        assert.ok(health.uptimeSec >= 0);
        assert.ok(health.sessions >= 3);
    });

    alice.close();
    bob.close();
    carol.close();
    sig.stop();
    rel.stop();

    if (failures > 0) {
        console.error(`\nE2E FAILED: ${failures} step(s) failed`);
        process.exit(1);
    }
    console.log('\nE2E OK: all steps passed');
    process.exit(0);
})().catch((e) => {
    console.error('E2E fatal:', e);
    process.exit(1);
});

function u32(n) {
    const b = Buffer.alloc(4);
    b.writeUInt32BE(n >>> 0, 0);
    return b;
}
