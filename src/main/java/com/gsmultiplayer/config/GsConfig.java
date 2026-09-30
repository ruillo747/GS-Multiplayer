package com.gsmultiplayer.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Client configuration, persisted as config/gsmultiplayer.json.
 * The mod is serverless: worlds are shared by direct IP:port, so there is
 * no signaling/relay/friends state here at all.
 */
public class GsConfig {

    /** Last addresses joined by IP (most recent first, max 5). Powers the quick-join field. */
    public List<String> recentAddresses = new ArrayList<>();

    public Connection connection = new Connection();
    public Ui ui = new Ui();
    public Updates updates = new Updates();
    public Diagnostics diagnostics = new Diagnostics();

    public static class Connection {
        /** TCP/UDP port used when publishing the running world. */
        public int lanPort = 25575;

        /** Try to open the LAN port on the router automatically (UPnP/NAT-PMP/PCP). */
        public boolean autoPortMap = true;

        /** Let friends without a licensed Minecraft account join the published world. */
        public boolean allowUnlicensed = true;
    }

    public static class Ui {
        public boolean showTitleButton = true;
        public boolean showPauseButton = true;
    }

    public static class Updates {
        public boolean enabled = true;
        public boolean checkOnStartup = true;
        /** GitHub repository that publishes releases. */
        public String repoOwner = "ruillo747";
        public String repoName = "GS-Multiplayer";
    }

    public static class Diagnostics {
        /** Enables verbose network logging. */
        public boolean devMode = false;
        /** Extra per-packet logging for network debugging. */
        public boolean verboseNetwork = false;
        /** Number of log lines kept in the in-memory GS Multiplayer Log. */
        public int logLines = 500;
    }
}
