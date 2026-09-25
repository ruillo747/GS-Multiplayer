package com.gsmultiplayer.p2p;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * UDP NAT hole punching. Both peers spam small probe packets to each other's
 * candidate addresses; the first probe (or ack) that arrives wins and fixes
 * the working address pair.
 *
 * Wire format: 0x47 | ver | 6-byte room code | u32 senderId | role (1=probe, 2=ack)
 */
public final class Puncher {

    public static final byte MAGIC = 0x47;
    public static final byte VERSION = 1;
    private static final byte ROLE_PROBE = 1;
    private static final byte ROLE_ACK = 2;
    private static final int PACKET_SIZE = 13;

    private final UdpEndpoint endpoint;
    private final String roomCode;
    private final int selfId;

    private volatile InetSocketAddress workingAddress;
    private volatile boolean done;

    public Puncher(UdpEndpoint endpoint, String roomCode, int selfId) {
        this.endpoint = endpoint;
        this.roomCode = roomCode;
        this.selfId = selfId;
    }

    /**
     * Blocking punch attempt. Returns the working remote address, or null on timeout.
     * Must be called from a worker thread; installs itself as the endpoint handler
     * and keeps the handler installed for a short grace period after success.
     */
    public InetSocketAddress punch(List<InetSocketAddress> remoteCandidates, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        CompletableFuture<InetSocketAddress> result = new CompletableFuture<>();

        endpoint.setHandler((data, from) -> {
            if (done || data == null || data.length < PACKET_SIZE || data[0] != MAGIC) {
                return;
            }
            if (data[1] != VERSION || !codeMatches(data) || readId(data) == selfId) {
                return;
            }
            byte role = data[12];
            if (role == ROLE_PROBE) {
                byte[] ack = buildPacket(ROLE_ACK);
                endpoint.sendTo(from, ack);
            }
            if (!done && (role == ROLE_PROBE || role == ROLE_ACK)) {
                done = true;
                workingAddress = from;
                result.complete(from);
            }
        });

        byte[] probe = buildPacket(ROLE_PROBE);
        int round = 0;
        while (!done && System.currentTimeMillis() < deadline) {
            for (InetSocketAddress candidate : remoteCandidates) {
                if (done) {
                    break;
                }
                endpoint.sendTo(candidate, probe);
            }
            round++;
            try {
                Thread.sleep(round < 3 ? 150 : 300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        InetSocketAddress address = result.getNow(null);
        if (address != null) {
            // keep replying briefly so the peer that lost the race also converges
            long graceEnd = System.currentTimeMillis() + 800;
            byte[] ack = buildPacket(ROLE_ACK);
            while (System.currentTimeMillis() < graceEnd) {
                endpoint.sendTo(address, ack);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return address;
    }

    private boolean codeMatches(byte[] data) {
        byte[] expected = roomCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < 6; i++) {
            if (data[2 + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readId(byte[] data) {
        return ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16) | ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
    }

    private byte[] buildPacket(byte role) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = MAGIC;
        packet[1] = VERSION;
        byte[] code = roomCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(code, 0, packet, 2, 6);
        packet[8] = (byte) ((selfId >> 24) & 0xFF);
        packet[9] = (byte) ((selfId >> 16) & 0xFF);
        packet[10] = (byte) ((selfId >> 8) & 0xFF);
        packet[11] = (byte) (selfId & 0xFF);
        packet[12] = role;
        return packet;
    }
}
