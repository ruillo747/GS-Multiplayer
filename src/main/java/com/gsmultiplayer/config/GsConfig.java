package com.gsmultiplayer.config;

/**
 * Client configuration, persisted as config/gsmultiplayer.json.
 * Field defaults are the values used for a fresh install.
 */
public class GsConfig {

    /** Signaling server WebSocket endpoint, e.g. ws://host:35500 or wss://host/ws. */
    public String signalingUrl = "ws://localhost:35500";

    /** Public relay address. Empty = use the host:port advertised by the signaling server. */
    public String relayHost = "";

    /** UDP port of the relay; used together with relayHost, or as fallback if the server omits it. */
    public int relayPort = 35501;

    public Connection connection = new Connection();
    public User user = new User();
    public Ui ui = new Ui();
    public Updates updates = new Updates();
    public Diagnostics diagnostics = new Diagnostics();

    public static class Connection {
        /** How long to try NAT hole punching before falling back to the relay (ms). */
        public int punchTimeoutMs = 6000;
        /** Signaling WebSocket connect timeout (ms). */
        public int signalingTimeoutMs = 10000;
        /** Tunnel keepalive/ping interval (ms); also refreshes relay sessions. */
        public int pingIntervalMs = 4000;
        /** TCP port used when publishing the integrated server for the room. */
        public int lanPort = 25575;

        /** Try to open the LAN port on the router automatically (UPnP/NAT-PMP/PCP). */
        public boolean autoPortMap = true;
    }

    public static class User {
        /** Display name inside GS Multiplayer. Empty = Minecraft player name. */
        public String nickname = "";
        /** Stable local identity, e.g. GS-7K2M9QX4. Generated on first launch. */
        public String gsId = "";
    }

    public static class Ui {
        public boolean showTitleButton = true;
        public boolean showPauseButton = true;
    }

    public static class Updates {
        public boolean enabled = true;
        public boolean checkOnStartup = true;
        /** GitHub repository that publishes releases, e.g. "ruillo747/GS-Multiplayer". */
        public String repoOwner = "ruillo747";
        public String repoName = "GS-Multiplayer";
    }

    public static class Diagnostics {
        /** Enables the technical P2P/latency/relay/packet-loss panel. */
        public boolean devMode = false;
        /** Extra per-packet logging for network debugging. */
        public boolean verboseNetwork = false;
        /** Number of log lines kept in the in-memory GS Multiplayer Log. */
        public int logLines = 500;
    }
}
