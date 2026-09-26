package com.gsmultiplayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shared mod constants. The mod is client-only; the server side lives in server/. */
public final class GsMultiplayerMod {

    public static final String MOD_ID = "gsmultiplayer";
    public static final String MOD_NAME = "GS Multiplayer";
    /**
     * Read from the loader metadata so the value always matches fabric.mod.json
     * (a hardcoded constant drifts every release and breaks the update checker).
     */
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("0.0.0");
    public static final int MAX_PLAYERS = 4;

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private GsMultiplayerMod() {
    }
}
