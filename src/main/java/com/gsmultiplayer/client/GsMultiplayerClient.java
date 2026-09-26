package com.gsmultiplayer.client;

import com.gsmultiplayer.GsMultiplayerMod;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.config.GsConfig;
import com.gsmultiplayer.friends.FriendsManager;
import com.gsmultiplayer.room.RoomManager;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;

import java.util.concurrent.Executor;

/**
 * Client entry point: wires config, friends, the room manager and the update checker.
 */
@Environment(EnvType.CLIENT)
public final class GsMultiplayerClient implements ClientModInitializer {

    private static GsMultiplayerClient instance;

    private FriendsManager friends;
    private RoomManager rooms;
    private final UpdateChecker updates = new UpdateChecker();

    private final Executor minecraftThreadExecutor = task -> MinecraftClient.getInstance().execute(task);

    public static GsMultiplayerClient get() {
        return instance;
    }

    public FriendsManager friends() {
        return friends;
    }

    public RoomManager rooms() {
        return rooms;
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
            } else {
                GsMultiplayerMod.LOGGER.info("[gs] {}", (Object) entry.message);
            }
        });

        friends = new FriendsManager(ConfigManager.dataDir());
        rooms = new RoomManager(config, friends, minecraftThreadExecutor);
        rooms.addListener(new GlobalUiListener());

        Runtime.getRuntime().addShutdownHook(new Thread(rooms::shutdown, "gs-shutdown"));

        if (config.updates.enabled && config.updates.checkOnStartup) {
            updates.checkAsync(config.updates.repoOwner, config.updates.repoName, GsMultiplayerMod.VERSION);
        }
        rooms.connectToSignaling();

        GsLog.info("GS Multiplayer " + GsMultiplayerMod.VERSION + " initialized");
    }

    /** Re-reads mutable settings after the settings screen saves them. */
    public void applySettings() {
        GsConfig config = ConfigManager.get();
        GsLog.setVerboseNetwork(config.diagnostics.verboseNetwork);
    }

    public void checkForUpdatesNow() {
        GsConfig config = ConfigManager.get();
        updates.checkAsync(config.updates.repoOwner, config.updates.repoName, GsMultiplayerMod.VERSION);
    }

    /**
     * Cross-cutting room events that must work from any screen:
     * auto-connect when the tunnel is ready, chat/toast notifications.
     */
    private final class GlobalUiListener implements RoomManager.UiListener {
        @Override
        public void onRoomChanged() {
        }

        @Override
        public void onChat(String key, Object[] args) {
            GuiUtil.chatOrToast(MinecraftClient.getInstance(), key, args);
        }

        @Override
        public void onInvite(com.gsmultiplayer.room.Invite invite) {
            GuiUtil.chatOrToast(MinecraftClient.getInstance(), "gs.multiplayer.chat.invite",
                    invite.fromName, invite.roomCode);
        }

        @Override
        public void onInviteResponse(String fromName, boolean accepted) {
            GuiUtil.chatOrToast(MinecraftClient.getInstance(),
                    accepted ? "gs.multiplayer.chat.invite_accepted" : "gs.multiplayer.chat.invite_declined", fromName);
        }

        @Override
        public void onReadyToPlay(String hostName, int localPort) {
            MinecraftClient client = MinecraftClient.getInstance();
            GsLog.info("Tunnel ready, connecting to 127.0.0.1:" + localPort);
            GuiUtil.connectLocal(client, new TitleScreen(), "127.0.0.1", localPort);
        }

        @Override
        public void onError(String key, Object[] args) {
            GuiUtil.chatOrToast(MinecraftClient.getInstance(), key, args);
        }

        @Override
        public void onSignalingState(boolean connected) {
        }

        @Override
        public void onFriendsUpdated() {
        }
    }
}
