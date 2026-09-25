package com.gsmultiplayer.p2p.reliable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reliable, ordered message channel over UDP datagrams delivered through a {@link Sink}.
 *
 * Design: cumulative ACK + selective ACK bits (NewReno-style), receiver reorder buffer.
 *   - "ack" is cumulative: every data packet &lt;= ack has been delivered to the app.
 *   - "ackBits" bit i-1 says the peer HOLDS packet ack+i in its reorder buffer, so
 *     the sender stops retransmitting it - but only a cumulative ack retires it.
 *     A held packet cannot be lost anymore, and the oldest unheld packet is always
 *     exactly the gap the receiver is waiting for, which keeps recovery simple
 *     and provably loss-free.
 *
 * Packet layout (big-endian):
 *   u8  version
 *   u8  flags (bit0 ack-only, bit1 ping, bit2 pong, bit3 fin)
 *   u32 seq      - sender's data sequence number
 *   u32 ack      - cumulative: all data packets &lt;= ack are delivered
 *   u32 ackBits  - bit i-1 = "packet ack+i is held by the receiver"
 *   ... payload
 *
 * Ack-only packets (acks, pings, pongs, FIN) carry seq=0: the receiver filters
 * them out by flag before any buffering, so they never punch holes into the
 * data sequence space. Ping/pong provide RTT estimates plus NAT keepalives.
 */
public final class ReliableLink {

    public interface Sink {
        void send(byte[] packet);
    }

    public interface Listener {
        void onMessage(byte[] data);

        void onClosed(String reason);
    }

    public static final int MAX_CHUNK = 1100;
    private static final int HEADER_SIZE = 14;
    private static final byte PROTOCOL_VERSION = 1;
    private static final int FLAG_ACK_ONLY = 1;
    private static final int FLAG_PING = 2;
    private static final int FLAG_PONG = 4;
    private static final int FLAG_FIN = 8;

    private static final int WINDOW = 64;
    private static final int REORDER_LIMIT = 256;
    private static final long TICK_MS = 40;
    private static final long PING_INTERVAL_MS = 4000;
    private static final long STATS_WINDOW_MS = 5000;
    private static final long SEND_BACKLOG_TIMEOUT_MS = 15000;
    /** Give up when the oldest unacked packet is this old (i.e. the peer is really gone). */
    private static final long RETRY_WINDOW_MS = 30000;

    private static final class InFlight {
        final int seq;
        final byte[] data;
        final long firstSentAt;
        long lastSentAt;
        int tries = 1;
        /** The receiver holds this packet in its reorder buffer - no retransmit needed. */
        boolean held;

        InFlight(int seq, byte[] data, long now) {
            this.seq = seq;
            this.data = data;
            this.firstSentAt = now;
            this.lastSentAt = now;
        }
    }

    private final Sink sink;
    private final Object lock = new Object();
    private final ScheduledFuture<?> tickFuture;

    private final ArrayDeque<byte[]> outbox = new ArrayDeque<>();
    private final LinkedHashMap<Integer, InFlight> unacked = new LinkedHashMap<>();
    private final HashMap<Integer, byte[]> reorder = new HashMap<>();
    private int nextSeq;
    private int recvNext;

    private long srttMs = -1;
    private long lastPingAt = System.currentTimeMillis() - PING_INTERVAL_MS;
    private long lastRttMs = -1;

    private long sentTotal;
    private long retransTotal;
    private long winSent;
    private long winRetrans;
    private long winStartedAt = System.currentTimeMillis();
    private double winLossPct;

    private volatile Listener listener;
    private volatile boolean closed;
    private volatile String closeReason;

    public ReliableLink(Sink sink, ScheduledExecutorService timer, Listener listener) {
        this.sink = sink;
        this.listener = listener;
        this.tickFuture = timer.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ api

    /** Sends one message (up to MAX_CHUNK bytes). Blocks while the window is full. */
    public void send(byte[] chunk) {
        if (chunk == null || chunk.length == 0 || chunk.length > MAX_CHUNK) {
            throw new IllegalArgumentException("chunk size must be 1.." + MAX_CHUNK + " bytes");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            long deadline = System.currentTimeMillis() + SEND_BACKLOG_TIMEOUT_MS;
            while (outbox.size() + unacked.size() >= WINDOW * 2 && !closed) {
                if (System.currentTimeMillis() > deadline) {
                    closeInternal("send backlog timeout");
                    return;
                }
                try {
                    lock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (closed) {
                return;
            }
            outbox.addLast(chunk);
            pumpLocked();
        }
    }

    /** Feed one raw link packet (already unwrapped from the relay header when applicable). */
    public void onDatagram(byte[] packet) {
        if (closed || packet == null || packet.length < HEADER_SIZE) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        if (bb.get() != PROTOCOL_VERSION) {
            return;
        }
        int flags = bb.get() & 0xFF;
        int seq = bb.getInt();
        int ack = bb.getInt();
        int ackBits = bb.getInt();
        byte[] payload = new byte[packet.length - HEADER_SIZE];
        bb.get(payload);

        processAcks(ack, ackBits);
        if ((flags & FLAG_FIN) != 0) {
            closeRemote("peer closed the connection");
            return;
        }
        if ((flags & FLAG_PING) != 0) {
            sendControl(FLAG_PONG, payload);
            return;
        }
        if ((flags & FLAG_PONG) != 0) {
            handlePong(payload);
            return;
        }
        if ((flags & FLAG_ACK_ONLY) != 0) {
            return;
        }
        handleData(seq, payload);
    }

    public void close() {
        closeInternal("closed locally");
    }

    public boolean isClosed() {
        return closed;
    }

    public String getCloseReason() {
        return closeReason;
    }

    /** Latest RTT estimate in milliseconds, or -1. */
    public long getRttMs() {
        return lastRttMs;
    }

    /** Smoothed RTT in milliseconds, or -1. */
    public long getSmoothedRttMs() {
        return srttMs;
    }

    /** Estimated packet loss percentage over the recent window (0..100). */
    public double getLossPct() {
        return winLossPct;
    }

    public void setListener(Listener newListener) {
        this.listener = newListener;
    }

    // --------------------------------------------------------------- engine

    private void handleData(int seq, byte[] payload) {
        List<byte[]> toDeliver = new ArrayList<>(2);
        synchronized (lock) {
            if (seq == recvNext) {
                toDeliver.add(payload);
                recvNext++;
                byte[] next = reorder.remove(recvNext);
                while (next != null) {
                    toDeliver.add(next);
                    recvNext++;
                    next = reorder.remove(recvNext);
                }
            } else {
                int diff = seq - recvNext;
                if (diff > 0 && diff < REORDER_LIMIT) {
                    reorder.put(seq, payload);
                }
                // duplicates and far-future packets are dropped
            }
            sendAckLocked();
        }
        Listener l = listener;
        if (l != null) {
            for (byte[] data : toDeliver) {
                l.onMessage(data);
            }
        }
    }

    /**
     * Cumulative part retires delivered packets; the selective part only marks
     * packets as held (present in the peer's reorder buffer, safe from loss).
     * RTT samples follow Karn's rule: only packets retransmitted never.
     */
    private void processAcks(int ack, int ackBits) {
        synchronized (lock) {
            if (unacked.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Integer, InFlight>> it = unacked.entrySet().iterator();
            while (it.hasNext()) {
                InFlight f = it.next().getValue();
                if (f.seq <= ack) {
                    boolean unsaturated = outbox.size() + unacked.size() < WINDOW / 2;
                    if (f.tries == 1 && unsaturated) {
                        long rtt = now - f.firstSentAt;
                        if (rtt >= 0 && rtt < 3000) {
                            lastRttMs = rtt;
                            srttMs = srttMs < 0 ? rtt : (srttMs * 7 + rtt) / 8;
                        }
                    }
                    it.remove();
                } else if (f.seq - ack <= 32 && (ackBits & (1 << (f.seq - ack - 1))) != 0) {
                    f.held = true;
                }
            }
            lock.notifyAll();
        }
    }

    private void sendAckLocked() {
        sink.send(buildPacket(0, recvNext - 1, FLAG_ACK_ONLY, EMPTY_PAYLOAD, computeAckBitsLocked()));
    }

    private void sendControl(int flag, byte[] payload) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            sink.send(buildPacket(0, recvNext - 1, FLAG_ACK_ONLY | flag, payload, computeAckBitsLocked()));
        }
    }

    private void pumpLocked() {
        long now = System.currentTimeMillis();
        while (!closed && unacked.size() < WINDOW && !outbox.isEmpty()) {
            byte[] data = outbox.pollFirst();
            int seq = nextSeq++;
            unacked.put(seq, new InFlight(seq, data, now));
            sink.send(buildPacket(seq, recvNext - 1, 0, data, computeAckBitsLocked()));
            sentTotal++;
            winSent++;
        }
    }

    private void handlePong(byte[] payload) {
        if (payload.length < 8) {
            return;
        }
        long sentAt = 0;
        for (int i = 0; i < 8; i++) {
            sentAt = (sentAt << 8) | (payload[i] & 0xFF);
        }
        long rtt = System.currentTimeMillis() - sentAt;
        synchronized (lock) {
            boolean unsaturated = outbox.size() + unacked.size() < WINDOW / 2;
            if (rtt >= 0 && rtt < 3000 && unsaturated) {  // skip samples distorted by queueing
                lastRttMs = rtt;
                srttMs = srttMs < 0 ? rtt : (srttMs * 7 + rtt) / 8;
            }
        }
    }

    private void tick() {
        if (closed) {
            tickFuture.cancel(false);
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (!unacked.isEmpty()) {
                long rto = currentRtoLocked();
                InFlight gap = null;
                boolean anyDue = false;
                for (InFlight f : unacked.values()) {
                    if (!f.held) {
                        if (gap == null) {
                            gap = f; // the receiver is waiting exactly for this packet
                        }
                        if (now - f.lastSentAt >= rto) {
                            anyDue = true;
                        }
                    }
                }
                if (gap != null && anyDue) {
                    if (now - gap.firstSentAt > RETRY_WINDOW_MS) {
                        closeInternal("connection lost");
                        return;
                    }
                    // resend every not-yet-held packet; the receiver already holds the rest
                    for (InFlight f : unacked.values()) {
                        if (f.held) {
                            continue;
                        }
                        f.tries++;
                        f.lastSentAt = now;
                        sink.send(buildPacket(f.seq, recvNext - 1, 0, f.data, computeAckBitsLocked()));
                        retransTotal++;
                        winRetrans++;
                    }
                    pumpLocked();
                }
            }
        }
        if (now - lastPingAt >= PING_INTERVAL_MS) {
            lastPingAt = now;
            byte[] stamp = new byte[8];
            long t = now;
            for (int i = 7; i >= 0; i--) {
                stamp[i] = (byte) (t & 0xFF);
                t >>>= 8;
            }
            sendControl(FLAG_PING, stamp);
        }
        if (now - winStartedAt >= STATS_WINDOW_MS) {
            long total = winSent + winRetrans;
            winLossPct = total == 0 ? 0.0 : Math.min(100.0, winRetrans * 100.0 / total);
            winSent = 0;
            winRetrans = 0;
            winStartedAt = now;
        }
    }

    private long currentRtoLocked() {
        long base = srttMs <= 0 ? 400 : srttMs * 2 + 80;
        if (base < 150) {
            base = 150;
        }
        return Math.min(base, 2500);
    }

    private void closeRemote(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        closeReason = reason;
        tickFuture.cancel(false);
        synchronized (lock) {
            lock.notifyAll();
        }
        Listener l = listener;
        if (l != null) {
            l.onClosed(reason);
        }
    }

    private void closeInternal(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        closeReason = reason;
        tickFuture.cancel(false);
        synchronized (lock) {
            // three FIN copies: FIN itself is unreliable, the receiver's retry window covers the rest
            for (int i = 0; i < 3; i++) {
                sink.send(buildPacket(0, recvNext - 1, FLAG_ACK_ONLY | FLAG_FIN, EMPTY_PAYLOAD, 0));
            }
            lock.notifyAll();
        }
        Listener l = listener;
        if (l != null) {
            l.onClosed(reason);
        }
    }

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /** Bits cover ack+1..ack+32: bit i-1 set = that seq is held in the reorder buffer. */
    private int computeAckBitsLocked() {
        int bits = 0;
        for (int i = 1; i <= 32; i++) {
            if (reorder.containsKey(recvNext - 1 + i)) {
                bits |= (1 << (i - 1));
            }
        }
        return bits;
    }

    private static byte[] buildPacket(int seq, int ack, int flags, byte[] payload, int ackBits) {
        byte[] packet = new byte[HEADER_SIZE + payload.length];
        ByteBuffer bb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        bb.put(PROTOCOL_VERSION);
        bb.put((byte) flags);
        bb.putInt(seq);
        bb.putInt(ack);
        bb.putInt(ackBits);
        bb.put(payload);
        return packet;
    }
}
