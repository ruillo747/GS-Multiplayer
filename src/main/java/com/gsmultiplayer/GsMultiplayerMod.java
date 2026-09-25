package com.gsmultiplayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shared mod constants. The mod is client-only; the server side lives in server/. */
public final class GsMultiplayerMod {

    public static final String MOD_ID = "gsmultiplayer";
    public static final String MOD_NAME = "GS Multiplayer";
    public static final String VERSION = "1.0.0";
    public static final int MAX_PLAYERS = 4;

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private GsMultiplayerMod() {
    }
}
