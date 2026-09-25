package com.gsmultiplayer.room;

import com.gsmultiplayer.config.GsConfig;
import com.gsmultiplayer.friends.FriendsManager;
import com.gsmultiplayer.network.SignalApi;
import com.gsmultiplayer.network.SignalConnection;
import com.gsmultiplayer.p2p.Candidate;
import com.gsmultiplayer.p2p.P2pTunnel;
import com.gsmultiplayer.p2p.bridge.StreamBridge;
import com.gsmultiplayer.p2p.reliable.ReliableLink;
import com.gsmultiplayer.relay.RelayAccess;
import com.gsmultiplayer.security.Codes;
import com.gsmultiplayer.util.GsLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Orchestrates signaling, rooms, presence, invites and P2P/relay tunnels.
 * Deliberately free of Minecraft imports: UI callbacks are marshalled through
 * the injected main-thread executor.
 */
public final class RoomManager {

    public enum Phase { IDLE, HOSTING, JOINING, CONNECTED }

    public interface UiListener {
        void onRoomChanged();

        void onChat(String key, Object[] args);

        void onInvite(Invite invite);

        void onInviteResponse(String fromName, boolean accepted);

        void onReadyToPlay(String hostName, int localPort);

        void onError(String key, Object[] args);

        void onSignalingState(boolean connected);

        void onFriendsUpdated();
    }

    public static final class PeerDiagnostics {
        public final String name;
        public final String state;
        public final String transport;
        public final long rttMs;
        public final double lossPct;
        public final int streams;

        PeerDiagnostics(String name, String state, String transport, long rttMs, double lossPct, int streams) {
            this.name = name;
            this.state = state;
            this.transport = transport;
            this.rttMs = rttMs;
            this.lossPct = lossPct;
            this.streams = streams;
        }
    }

    private static final class PeerCtx {
        final PeerInfo peer;
        final P2pTunnel tunnel;
        volatile StreamBridge bridge;
        volatile boolean connecting;
        volatile boolean relayTried;

        PeerCtx(PeerInfo peer, P2pTunnel tunnel) {
            this.peer = peer;
            this.tunnel = tunnel;
        }
    }

    private final GsConfig config;
    private final FriendsManager friends;
    private final Executor mainThread;
    private final SignalConnection signaling = new SignalConnection();
    private final SignalApi api = new SignalApi(signaling);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(GsLog.daemonThreadFactory("gs-timer"));
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(GsLog.daemonThreadFactory("gs-worker"));
    private final List<UiListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, PeerCtx> peers = new ConcurrentHashMap<>();
    private final List<Invite> invites = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile int selfSessionId = -1;
    private volatile Phase phase = Phase.IDLE;
    private volatile RoomEntry room;
    private volatile int lanPort = -1;
    private volatile RelayAccess.RelayInfo relayInfo;
    private volatile Consumer<List<RoomEntry>> pendingRoomList;
    private volatile boolean signalingOnline;
    private ScheduledFuture<?> heartbeatFuture;
    private int reconnectAttempt;

    public RoomManager(GsConfig config, FriendsManager friends, Executor mainThread) {
        this.config = config;
        this.friends = friends;
        this.mainThread = mainThread;
        registerHandlers();
        signaling.setListener(new SignalConnection.Listener() {
            @Override
            public void onSignalingConnected() {
                onSignalingUp();
            }

            @Override
            public void onSignalingDisconnected(String reason) {
                onSignalingDown(reason);
            }
        });
    }

    // ------------------------------------------------------------- listeners

    public void addListener(UiListener listener) {
        listeners.add(listener);
    }

    public void removeListener(UiListener listener) {
        listeners.remove(listener);
    }

    private void fire(Consumer<UiListener> action) {
        mainThread.execute(() -> {
            for (UiListener l : listeners) {
                try {
                    action.accept(l);
                } catch (Exception e) {
                    GsLog.error("UI listener failed: " + e);
                }
            }
        });
    }

    private void fireChat(String key, Object... args) {
        fire(l -> l.onChat(key, args));
    }

    private void fireError(String key, Object... args) {
        GsLog.warn(key);
        fire(l -> l.onError(key, args));
    }

    // ------------------------------------------------------------ public api

    public void connectToSignaling() {
        if (signaling.isConnected() || shuttingDown.get()) {
            return;
        }
        worker.execute(this::openSignalingQuietly);
    }

    public Phase getPhase() {
        return phase;
    }

    public RoomEntry getRoom() {
        return room;
    }

    public String getRoomCode() {
        RoomEntry r = room;
        return r != null ? r.code : null;
    }

    public boolean isHosting() {
        return phase == Phase.HOSTING && room != null;
    }

    public boolean isSignalingOnline() {
        return signalingOnline;
    }

    public List<Invite> getInvites() {
        return new ArrayList<>(invites);
    }

    public void hostRoom(String name, int publishedLanPort) {
        this.lanPort = publishedLanPort;
        worker.execute(() -> {
            try {
                ensureSignaling();
                api.createRoom(Codes.sanitizeText(name, 32), 4);
                GsLog.info("Creating room, LAN port " + publishedLanPort);
            } catch (IOException e) {
                fireError("gs.multiplayer.error.create", e.getMessage());
            }
        });
    }

    public void joinRoom(String rawCode) {
        String code = Codes.normalizeRoomCode(rawCode);
        if (!Codes.isValidRoomCode(code)) {
            fireError("gs.multiplayer.error.bad_code");
            return;
        }
        phase = Phase.JOINING;
        fire(UiListener::onRoomChanged);
        worker.execute(() -> {
            try {
                ensureSignaling();
                api.joinRoom(code);
            } catch (IOException e) {
                phase = Phase.IDLE;
                fire(UiListener::onRoomChanged);
                fireError("gs.multiplayer.error.join", e.getMessage());
            }
        });
    }

    public void leaveRoom() {
        worker.execute(() -> {
            try {
                api.leaveRoom();
            } catch (IOException e) {
                GsLog.debug("leaveRoom failed: " + e.getMessage());
            }
            teardownAll("left the room");
            room = null;
            phase = Phase.IDLE;
            fire(UiListener::onRoomChanged);
        });
    }

    public void inviteFriend(String friendGsId) {
        RoomEntry r = room;
        if (r == null) {
            fireError("gs.multiplayer.error.room_not_open");
            return;
        }
        worker.execute(() -> {
            try {
                api.invite(Codes.normalizeRoomCode(friendGsId), r.code);
                GsLog.info("Invite sent to " + friendGsId);
            } catch (IOException e) {
                fireError("gs.multiplayer.error.invite_send", e.getMessage());
            }
        });
    }

    public void respondToInvite(Invite invite, boolean accept) {
        invites.remove(invite);
        if (accept) {
            worker.execute(() -> {
                try {
                    api.inviteResponse(invite.fromSessionId, true);
                } catch (IOException e) {
                    GsLog.debug("invite response failed: " + e.getMessage());
                }
                joinRoom(invite.roomCode);
            });
        } else {
            worker.execute(() -> {
                try {
                    api.inviteResponse(invite.fromSessionId, false);
                } catch (IOException e) {
                    GsLog.debug("invite response failed: " + e.getMessage());
                }
            });
        }
        fire(UiListener::onRoomChanged);
    }

    public void listRooms(String query, Consumer<List<RoomEntry>> callback) {
        pendingRoomList = callback;
        worker.execute(() -> {
            try {
                ensureSignaling();
                api.listRooms(query);
            } catch (IOException e) {
                pendingRoomList = null;
                List<RoomEntry> empty = new ArrayList<>();
                mainThread.execute(() -> callback.accept(empty));
                fireError("gs.multiplayer.error.list", e.getMessage());
            }
        });
    }

    public void watchFriends() {
        worker.execute(() -> {
            try {
                api.presenceWatch(friends.gsids());
            } catch (IOException e) {
                GsLog.debug("presence.watch failed: " + e.getMessage());
            }
        });
    }

    public List<PeerDiagnostics> peerDiagnostics() {
        List<PeerDiagnostics> out = new ArrayList<>();
        for (PeerCtx ctx : peers.values()) {
            ReliableLink link = ctx.tunnel.getLink();
            out.add(new PeerDiagnostics(
                    ctx.peer.name,
                    ctx.tunnel.getState().name(),
                    ctx.tunnel.getTransport() == null ? "-" : ctx.tunnel.getTransport().name(),
                    link == null ? -1 : link.getRttMs(),
                    link == null ? 0.0 : link.getLossPct(),
                    ctx.bridge == null ? 0 : ctx.bridge.connectedStreams()));
        }
        return out;
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.submit(() -> {
                try {
                    api.leaveRoom();
                } catch (IOException e) {
                    // best effort during shutdown
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // best effort during shutdown
        }
        teardownAll("shutdown");
        signaling.close();
        heartbeatCancel();
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    // ------------------------------------------------------ signaling events

    private void registerHandlers() {
        signaling.on("welcome", m -> selfSessionId = RoomEntry.intOf(m, "sessionId", -1));
        signaling.on("ready", m -> GsLog.debug("Signaling ready"));
        signaling.on("room.created", m -> {
            room = RoomEntry.fromJson(m.getAsJsonObject("room"));
            phase = Phase.HOSTING;
            fire(UiListener::onRoomChanged);
            fireChat("gs.multiplayer.chat.room_opened", room.code);
        });
        signaling.on("room.joined", m -> {
            room = RoomEntry.fromJson(m.getAsJsonObject("room"));
            PeerInfo host = null;
            JsonElement peersEl = m.get("peers");
            if (peersEl != null && peersEl.isJsonArray()) {
                for (JsonElement e : peersEl.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        host = PeerInfo.fromJson(e.getAsJsonObject());
                        break;
                    }
                }
            }
            fire(UiListener::onRoomChanged);
            if (host != null) {
                startPeerConnection(host);
            } else {
                phase = Phase.IDLE;
                fire(UiListener::onRoomChanged);
                fireError("gs.multiplayer.error.host_unavailable");
            }
        });
        signaling.on("room.update", m -> {
            room = RoomEntry.fromJson(m.getAsJsonObject("room"));
            fire(UiListener::onRoomChanged);
        });
        signaling.on("room.peerJoined", m -> {
            JsonObject peerObj = m.getAsJsonObject("peer");
            PeerInfo peer = PeerInfo.fromJson(peerObj);
            if (m.has("room") && m.get("room").isJsonObject()) {
                room = RoomEntry.fromJson(m.getAsJsonObject("room"));
            }
            fireChat("gs.multiplayer.chat.player_joined", peer.name);
            fire(UiListener::onRoomChanged);
            if (isHosting()) {
                startPeerConnection(peer);
            }
        });
        signaling.on("room.peerLeft", m -> {
            int id = RoomEntry.intOf(m, "peerId", -1);
            PeerCtx ctx = peers.get(id);
            if (m.has("room") && m.get("room").isJsonObject()) {
                room = RoomEntry.fromJson(m.getAsJsonObject("room"));
            }
            if (ctx != null) {
                fireChat("gs.multiplayer.chat.player_left", ctx.peer.name);
                ctx.tunnel.close("peer left the room");
            }
            fire(UiListener::onRoomChanged);
        });
        signaling.on("room.closed", m -> {
            teardownAll("room closed");
            room = null;
            phase = Phase.IDLE;
            fire(UiListener::onRoomChanged);
            fireChat("gs.multiplayer.chat.host_closed");
        });
        signaling.on("signal", m -> {
            int from = RoomEntry.intOf(m, "from", -1);
            JsonElement dataEl = m.get("data");
            if (from < 0 || dataEl == null || !dataEl.isJsonObject()) {
                return;
            }
            JsonObject data = dataEl.getAsJsonObject();
            if ("cand".equals(RoomEntry.string(data, "t", ""))) {
                List<String> cands = new ArrayList<>();
                JsonElement arr = data.get("cands");
                if (arr != null && arr.isJsonArray()) {
                    for (JsonElement e : arr.getAsJsonArray()) {
                        if (e.isJsonPrimitive()) {
                            cands.add(e.getAsString());
                        }
                    }
                }
                handleCandidates(from, cands);
            }
        });
        signaling.on("invite", m -> {
            Invite invite = Invite.fromJson(m);
            invites.add(invite);
            fireChat("gs.multiplayer.chat.invite", invite.fromName, invite.roomCode);
            fire(l -> l.onInvite(invite));
        });
        signaling.on("invite.response", m -> {
            boolean accepted = m.has("accepted") && m.get("accepted").isJsonPrimitive() && m.get("accepted").getAsBoolean();
            String from = "Игрок";
            JsonElement fromEl = m.get("from");
            if (fromEl != null && fromEl.isJsonObject()) {
                from = RoomEntry.string(fromEl.getAsJsonObject(), "name", from);
            }
            final String fromName = from;
            fire(l -> l.onInviteResponse(fromName, accepted));
        });
        signaling.on("presence.snapshot", m -> {
            JsonElement arr = m.get("entries");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement e : arr.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        applyPresence(e.getAsJsonObject());
                    }
                }
                fire(UiListener::onFriendsUpdated);
            }
        });
        signaling.on("presence.update", m -> {
            JsonElement e = m.get("entry");
            if (e != null && e.isJsonObject()) {
                applyPresence(e.getAsJsonObject());
                fire(UiListener::onFriendsUpdated);
            }
        });
        signaling.on("relay.token", m -> {
            String host = RoomEntry.string(m, "host", "");
            int port = RoomEntry.intOf(m, "port", config.relayPort);
            if (!config.relayHost.isEmpty()) {
                host = config.relayHost;
                port = config.relayPort;
            }
            String roomCode = getRoomCode();
            relayInfo = new RelayAccess.RelayInfo(
                    host, port,
                    RoomEntry.string(m, "token", ""),
                    RoomEntry.intOf(m, "peerId", selfSessionId),
                    roomCode == null ? "" : roomCode);
            GsLog.debug("Relay token received: " + host + ":" + port);
        });
        signaling.on("room.listed", m -> {
            Consumer<List<RoomEntry>> cb = pendingRoomList;
            pendingRoomList = null;
            if (cb == null) {
                return;
            }
            JsonElement arr = m.get("rooms");
            List<RoomEntry> rooms = arr != null && arr.isJsonArray()
                    ? RoomEntry.listFrom(arr.getAsJsonArray()) : new ArrayList<>();
            mainThread.execute(() -> cb.accept(rooms));
        });
        signaling.on("error", m -> {
            String of = RoomEntry.string(m, "of", "?");
            String code = RoomEntry.string(m, "code", "UNKNOWN");
            GsLog.warn("Signaling error " + of + ": " + code);
            if ("ROOM_FULL".equals(code) || "ROOM_NOT_FOUND".equals(code) || "BAD_CODE".equals(code)) {
                phase = Phase.IDLE;
                fire(UiListener::onRoomChanged);
            }
            String key = serverErrorKey(code);
            if ("gs.multiplayer.server.generic".equals(key)) {
                fireError(key, code);
            } else {
                fireError(key);
            }
        });
        signaling.on("pong", m -> GsLog.debug("signaling rtt "
                + (System.currentTimeMillis() - RoomEntry.intOf(m, "clientTime", 0)) + "ms"));
    }

    private static String serverErrorKey(String code) {
        switch (code) {
            case "ROOM_NOT_FOUND": return "gs.multiplayer.server.room_not_found";
            case "ROOM_FULL": return "gs.multiplayer.server.room_full";
            case "BAD_CODE": return "gs.multiplayer.server.bad_code";
            case "NOT_HOST": return "gs.multiplayer.server.not_host";
            case "TARGET_OFFLINE": return "gs.multiplayer.server.target_offline";
            case "NOT_IN_ROOM": return "gs.multiplayer.server.not_in_room";
            case "RELAY_DISABLED": return "gs.multiplayer.server.relay_disabled";
            default: return "gs.multiplayer.server.generic";
        }
    }

    private void applyPresence(JsonObject entry) {
        String gsid = RoomEntry.string(entry, "gsid", "");
        String status = RoomEntry.string(entry, "status", "offline");
        String roomCode = entry.has("roomCode") && !entry.get("roomCode").isJsonNull()
                ? entry.get("roomCode").getAsString() : null;
        friends.updatePresence(gsid, status, roomCode);
    }

    // ------------------------------------------------------------ connection

    private void ensureSignaling() throws IOException {
        if (!signaling.isConnected()) {
            openSignaling();
        }
    }

    private void openSignalingQuietly() {
        try {
            openSignaling();
        } catch (IOException e) {
            GsLog.warn("Signaling unavailable: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void openSignaling() throws IOException {
        signaling.connect(config.signalingUrl, config.connection.signalingTimeoutMs);
        String nickname = config.user.nickname == null || config.user.nickname.isEmpty()
                ? GsLog.minecraftName() : config.user.nickname;
        api.hello(nickname, config.user.gsId);
    }

    private void onSignalingUp() {
        signalingOnline = true;
        reconnectAttempt = 0;
        try {
            api.presenceWatch(friends.gsids());
        } catch (IOException e) {
            GsLog.debug("presence.watch failed: " + e.getMessage());
        }
        heartbeatCancel();
        long interval = Math.max(5000, config.connection.pingIntervalMs);
        heartbeatFuture = scheduler.scheduleWithFixedDelay(() -> {
            try {
                api.heartbeat();
                api.ping(System.currentTimeMillis());
            } catch (IOException e) {
                GsLog.debug("heartbeat failed: " + e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        fire(l -> l.onSignalingState(true));
    }

    private void onSignalingDown(String reason) {
        if (shuttingDown.get()) {
            return;
        }
        signalingOnline = false;
        heartbeatCancel();
        relayInfo = null;
        fire(l -> l.onSignalingState(false));
        if (isHosting()) {
            RoomEntry r = room;
            worker.execute(() -> {
                try {
                    api.createRoom(r.name, 4);
                    fireChat("gs.multiplayer.chat.reconnected");
                } catch (IOException e) {
                    GsLog.debug("room re-create failed: " + e.getMessage());
                }
            });
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shuttingDown.get()) {
            return;
        }
        int attempt = ++reconnectAttempt;
        long delay = Math.min(30000, 2000L << Math.min(4, attempt));
        GsLog.debug("Signaling reconnect in " + delay + "ms (attempt " + attempt + ")");
        scheduler.schedule(() -> {
            if (!shuttingDown.get() && !signaling.isConnected()) {
                worker.execute(() -> {
                    try {
                        openSignaling();
                    } catch (IOException e) {
                        GsLog.debug("reconnect failed: " + e.getMessage());
                        scheduleReconnect();
                    }
                });
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void heartbeatCancel() {
        ScheduledFuture<?> f = heartbeatFuture;
        heartbeatFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    // -------------------------------------------------------------- tunnels

    private void startPeerConnection(PeerInfo peer) {
        worker.execute(() -> {
            if (peers.containsKey(peer.id) || selfSessionId < 0 || room == null) {
                return;
            }
            P2pTunnel tunnel = new P2pTunnel(selfSessionId, peer.id, peer.name, room.code,
                    config.connection.punchTimeoutMs, scheduler);
            PeerCtx ctx = new PeerCtx(peer, tunnel);
            peers.put(peer.id, ctx);
            tunnel.setOnClose(() -> onTunnelClosed(ctx));
            List<String> candidates;
            try {
                candidates = tunnel.prepare();
            } catch (Exception e) {
                fireError("gs.multiplayer.error.network", e.getMessage());
                tunnel.close("prepare failed");
                return;
            }
            try {
                api.signalTo(peer.id, candidatesMessage(candidates));
                requestRelayToken();
            } catch (IOException e) {
                fireError("gs.multiplayer.error.candidates", e.getMessage());
            }
            // Safety net: if the peer never answers with candidates, try the relay anyway.
            scheduler.schedule(() -> {
                PeerCtx c = peers.get(peer.id);
                if (c != null && c.tunnel.getState() != P2pTunnel.State.OPEN) {
                    connectRelayFallback(c);
                }
            }, config.connection.punchTimeoutMs + 2500, TimeUnit.MILLISECONDS);
        });
    }

    private void handleCandidates(int fromPeer, List<String> cands) {
        PeerCtx ctx = peers.get(fromPeer);
        if (ctx == null || cands.isEmpty()) {
            return;
        }
        List<InetSocketAddress> addresses = Candidate.parseAll(cands);
        if (addresses.isEmpty()) {
            return;
        }
        worker.execute(() -> {
            if (ctx.connecting || ctx.tunnel.getState() == P2pTunnel.State.OPEN) {
                return;
            }
            ctx.connecting = true;
            GsLog.info("Punching to " + ctx.peer.name + " (" + addresses.size() + " candidates)");
            boolean ok = ctx.tunnel.punch(addresses);
            if (ok) {
                onTunnelOpen(ctx);
            } else {
                connectRelayFallback(ctx);
            }
        });
    }

    private void connectRelayFallback(PeerCtx ctx) {
        worker.execute(() -> {
            if (ctx.relayTried || ctx.tunnel.getState() == P2pTunnel.State.OPEN) {
                return;
            }
            ctx.relayTried = true;
            RelayAccess.RelayInfo info = awaitRelayInfo(3);
            if (info == null) {
                fireError("gs.multiplayer.error.no_route", ctx.peer.name);
                ctx.tunnel.close("no relay available");
                return;
            }
            GsLog.info("Falling back to relay for " + ctx.peer.name);
            if (ctx.tunnel.connectRelay(info)) {
                onTunnelOpen(ctx);
            } else {
                fireError("gs.multiplayer.error.relay_failed", ctx.peer.name);
                ctx.tunnel.close("relay failed");
            }
        });
    }

    private RelayAccess.RelayInfo awaitRelayInfo(int seconds) {
        RelayAccess.RelayInfo info = relayInfo;
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (info == null && System.currentTimeMillis() < deadline) {
            try {
                api.requestRelay();
            } catch (IOException e) {
                GsLog.debug("relay.request failed: " + e.getMessage());
                break;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            info = relayInfo;
        }
        return info;
    }

    private void requestRelayToken() {
        try {
            api.requestRelay();
        } catch (IOException e) {
            GsLog.debug("relay.request failed: " + e.getMessage());
        }
    }

    private void onTunnelOpen(PeerCtx ctx) {
        P2pTunnel tunnel = ctx.tunnel;
        if (isHosting() || phase == Phase.HOSTING) {
            ctx.bridge = StreamBridge.host(tunnel.getLink(), () -> {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", lanPort), 5000);
                socket.setTcpNoDelay(true);
                return socket;
            });
            fireChat("gs.multiplayer.chat.peer_connected", ctx.peer.name,
                    tunnel.getTransport() == P2pTunnel.Transport.P2P ? "P2P" : "Relay");
            fire(UiListener::onRoomChanged);
        } else {
            try {
                ctx.bridge = StreamBridge.guest(tunnel.getLink());
            } catch (IOException e) {
                fireError("gs.multiplayer.error.local_port", e.getMessage());
                tunnel.close("bridge failed");
                return;
            }
            phase = Phase.CONNECTED;
            fire(UiListener::onRoomChanged);
            String hostName = room != null ? room.hostName : "хост";
            fire(l -> l.onReadyToPlay(hostName, ctx.bridge.localPort()));
        }
    }

    private void onTunnelClosed(PeerCtx ctx) {
        peers.remove(ctx.peer.id);
        StreamBridge bridge = ctx.bridge;
        ctx.bridge = null;
        if (bridge != null) {
            bridge.shutdown();
        }
        if (ctx.peer.name != null && phase == Phase.HOSTING) {
            fireChat("gs.multiplayer.chat.lost", ctx.peer.name);
        }
        if (phase == Phase.CONNECTED) {
            phase = Phase.IDLE;
            room = null;
            fireError("gs.multiplayer.error.host_lost");
            fire(UiListener::onRoomChanged);
        } else {
            fire(UiListener::onRoomChanged);
        }
    }

    private void teardownAll(String reason) {
        for (PeerCtx ctx : peers.values()) {
            ctx.tunnel.close(reason);
            StreamBridge bridge = ctx.bridge;
            if (bridge != null) {
                bridge.shutdown();
            }
        }
        peers.clear();
        invites.clear();
    }

    private static JsonObject candidatesMessage(List<String> candidates) {
        JsonObject data = new JsonObject();
        data.addProperty("t", "cand");
        JsonArray arr = new JsonArray();
        for (String c : candidates) {
            arr.add(c);
        }
        data.add("cands", arr);
        return data;
    }
}
