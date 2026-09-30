package com.gsmultiplayer.client;

import com.gsmultiplayer.GsMultiplayerMod;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.config.GsConfig;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/**
 * Client entry point: wires config and the update checker.
 * The mod is fully serverless - worlds are shared by direct IP:port.
 */
@Environment(EnvType.CLIENT)
public final class GsMultiplayerClient implements ClientModInitializer {

    private static GsMultiplayerClient instance;

    private final UpdateChecker updates = new UpdateChecker();

    public static GsMultiplayerClient get() {
        return instance;
    }

    public UpdateChecker updates() {
        return updates;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        // NOTE: client entrypoints run while MinecraftClient is still being constructed.
        // MinecraftClient.getInstance()/getSession() are NOT usable here - touching them
        // crashes the game before the title screen. Menu mixins capture the name later.

        ConfigManager.init(FabricLoader.getInstance().getGameDir());
        GsConfig config = ConfigManager.get();
        GsLog.setVerboseNetwork(config.diagnostics.verboseNetwork);
        GsLog.setSink(entry -> {
            if (entry.level == GsLog.Level.ERROR) {
                GsMultiplayerMod.LOGGER.error("[gs] {}", (Object) entry.message);
            } else if (entry.level == GsLog.Level.WARN) {
                GsMultiplayerMod.LOGGER.warn("[gs] {}", (Object) entry.message);
            } else if (entry.level == GsLog.Level.DEBUG) {
                GsMultiplayerMod.LOGGER.debug("[gs] {}", (Object) entry.message);
            } else {
                GsMultiplayerMod.LOGGER.info("[gs] {}", (Object) entry.message);
            }
        });

        if (config.updates.enabled && config.updates.checkOnStartup) {
            updates.checkAsync(config.updates.repoOwner, config.updates.repoName, GsMultiplayerMod.VERSION);
        }

        GsLog.info("GS Multiplayer " + GsMultiplayerMod.VERSION + " initialized");
    }

    /** Re-reads mutable settings after the settings screen saves them. */
    public void applySettings() {
        GsLog.setVerboseNetwork(ConfigManager.get().diagnostics.verboseNetwork);
    }

    public void checkForUpdatesNow() {
        GsConfig config = ConfigManager.get();
        updates.checkAsync(config.updates.repoOwner, config.updates.repoName, GsMultiplayerMod.VERSION);
    }
}
