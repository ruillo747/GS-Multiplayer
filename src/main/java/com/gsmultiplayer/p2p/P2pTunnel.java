package com.gsmultiplayer.p2p;

import com.gsmultiplayer.p2p.reliable.ReliableLink;
import com.gsmultiplayer.relay.RelayAccess;
import com.gsmultiplayer.util.GsLog;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * One host<->guest connection: punches UDP through NAT (P2P transport) and,
 * when that fails, falls back to the relay server (RELAY transport).
 * Owns a dedicated UDP socket for the whole lifetime of the connection.
 */
public final class P2pTunnel {

    public enum Transport { P2P, RELAY }

    public enum State { NEW, PREPARED, PUNCHING, OPEN, CLOSED }

    private final int selfId;
    private final int peerId;
    private final String peerName;
    private final String roomCode;
    private final long punchTimeoutMs;
    private final ScheduledExecutorService timer;

    private DatagramSocket rawSocket;
    private UdpEndpoint endpoint;
    private ReliableLink link;
    private State state = State.NEW;
    private Transport transport;
    private InetSocketAddress relayAddress;
    private int relayDestId = -1;

    public P2pTunnel(int selfId, int peerId, String peerName, String roomCode,
                     long punchTimeoutMs, ScheduledExecutorService timer) {
        this.selfId = selfId;
        this.peerId = peerId;
        this.peerName = peerName;
        this.roomCode = roomCode;
        this.punchTimeoutMs = punchTimeoutMs;
        this.timer = timer;
    }

    /**
     * Binds the local socket and resolves the external address via STUN.
     * Returns the candidate list to send to the peer ("ip:port" strings).
     * Runs on a worker thread; blocks for up to a few seconds.
     */
    public synchronized List<String> prepare() {
        if (state != State.NEW) {
            throw new IllegalStateException("prepare() twice");
        }
        try {
            rawSocket = new DatagramSocket(0);
            rawSocket.setReceiveBufferSize(512 * 1024);
            rawSocket.setSendBufferSize(256 * 1024);
        } catch (Exception e) {
            throw new IllegalStateException("cannot bind UDP socket: " + e.getMessage(), e);
        }
        List<String> candidates = new ArrayList<>();
        for (InetSocketAddress local : Candidate.localAddresses(rawSocket.getLocalPort())) {
            candidates.add(Candidate.encode(local));
        }
        InetSocketAddress external = StunResolver.queryAny(rawSocket, 2500);
        if (external != null) {
            candidates.add(Candidate.encode(external));
        }
        endpoint = new UdpEndpoint(rawSocket);
        state = State.PREPARED;
        GsLog.debug("Tunnel to " + peerName + " prepared, " + candidates.size() + " candidates");
        return candidates;
    }

    /** Blocking punch attempt; call with the peer's candidate list. Returns true on success. */
    public synchronized boolean punch(List<InetSocketAddress> remoteCandidates) {
        if (state != State.PREPARED) {
            return false;
        }
        state = State.PUNCHING;
        InetSocketAddress address = new Puncher(endpoint, roomCode, selfId).punch(remoteCandidates, punchTimeoutMs);
        if (address == null) {
            GsLog.info("P2P punch with " + peerName + " failed, will use relay");
            return false;
        }
        GsLog.info("P2P punch with " + peerName + " succeeded via " + address);
        openLink(Transport.P2P, address);
        return true;
    }

    /** Relay fallback: registers with the relay and switches the link to it. */
    public synchronized boolean connectRelay(RelayAccess.RelayInfo info) {
        if (state != State.PREPARED && state != State.PUNCHING) {
            return false;
        }
        try {
            InetSocketAddress relayAddr = new InetSocketAddress(info.host, info.port);
            RelayAccess.register(rawSocket, relayAddr, info.token);
            this.relayAddress = relayAddr;
            this.relayDestId = peerId;
            openLink(Transport.RELAY, relayAddr);
            GsLog.info("Relay connection with " + peerName + " established");
            return true;
        } catch (Exception e) {
            GsLog.warn("Relay fallback failed for " + peerName + ": " + e.getMessage());
            return false;
        }
    }

    private void openLink(Transport via, InetSocketAddress target) {
        final boolean viaRelay = via == Transport.RELAY;
        final int dest = relayDestId;
        ReliableLink.Sink sink = packet -> {
            if (viaRelay) {
                endpoint.sendTo(relayAddress, RelayAccess.wrapData(dest, packet));
            } else {
                endpoint.sendTo(target, packet);
            }
        };
        link = new ReliableLink(sink, timer, linkListener);
        endpoint.setHandler((data, from) -> {
            if (data == null || data.length < 1) {
                return;
            }
            if (data[0] == Puncher.MAGIC) {
                return; // late punch probe
            }
            if (viaRelay) {
                if (from.equals(relayAddress) && data[0] == RelayAccess.MAGIC_DATA && data.length > 5) {
                    byte[] inner = new byte[data.length - 5];
                    System.arraycopy(data, 5, inner, 0, inner.length);
                    if (link != null) {
                        link.onDatagram(inner);
                    }
                }
                return;
            }
            if (from.equals(target) && link != null) {
                link.onDatagram(data);
            }
        });
        transport = via;
        state = State.OPEN;
    }

    public synchronized void close(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        if (link != null && !link.isClosed()) {
            link.close();
        }
        if (endpoint != null) {
            endpoint.close();
        }
        GsLog.info("Tunnel to " + peerName + " closed (" + reason + ")");
        Runnable callback = onClose;
        if (callback != null) {
            callback.run();
        }
    }

    // ------------------------------------------------------------- getters

    public synchronized ReliableLink getLink() {
        return link;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized Transport getTransport() {
        return transport;
    }

    public int getPeerId() {
        return peerId;
    }

    public String getPeerName() {
        return peerName;
    }

    private volatile ReliableLink.Listener linkListener;
    private volatile Runnable onClose;

    public void setLinkListener(ReliableLink.Listener listener) {
        this.linkListener = listener;
        ReliableLink l = link;
        if (l != null) {
            l.setListener(listener);
        }
    }

    /** Invoked exactly once when this tunnel is closed for any reason. */
    public void setOnClose(Runnable callback) {
        this.onClose = callback;
    }
}
