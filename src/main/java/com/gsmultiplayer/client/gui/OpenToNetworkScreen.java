package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.client.GsText;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.network.portmap.PortMapService;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Open to network" - Open2Online-style direct hosting: publishes the running
 * world via the router (UPnP/NAT-PMP/PCP), shows the IP to send to friends.
 */
public final class OpenToNetworkScreen extends GsBaseScreen {

    private static final GameMode[] MODES = {GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gs-open-to-network");
        thread.setDaemon(true);
        return thread;
    });

    private enum State { SETUP, WORKING, OPEN, FAILED }

    private TextFieldWidget portField;
    private CheckboxWidget cheatsBox;
    private CheckboxWidget unlicensedBox;
    private int modeIndex;
    private State state = State.SETUP;
    private String summary = "";
    private String method = "";

    public OpenToNetworkScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.open_net_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height < 300 ? 42 : this.height / 4 + 4;

        portField = new TextFieldWidget(this.textRenderer, cx - 100, y + 22, 200, 20,
                GsText.t("gs.multiplayer.open_net_port"));
        portField.setMaxLength(5);
        portField.setTextPredicate(s -> s.matches("[0-9]*"));
        portField.setText(String.valueOf(ConfigManager.get().connection.lanPort));
        addButton(portField);

        addButton(new ButtonWidget(cx - 100, y + 46, 200, 20,
                GsText.t("gs.multiplayer.game_mode", modeName(MODES[modeIndex])),
                b -> {
                    modeIndex = (modeIndex + 1) % MODES.length;
                    b.setMessage(GsText.t("gs.multiplayer.game_mode", modeName(MODES[modeIndex])));
                }));
        cheatsBox = addButton(new CheckboxWidget(cx - 100, y + 66, 200, 20,
                GsText.t("gs.multiplayer.cheats"), false));
        unlicensedBox = addButton(new CheckboxWidget(cx - 100, y + 84, 200, 20,
                GsText.t("gs.multiplayer.allow_unlicensed"),
                com.gsmultiplayer.config.ConfigManager.get().connection.allowUnlicensed));

        openButton = addButton(new ButtonWidget(cx - 100, y + 106, 200, 20,
                GsText.t("gs.multiplayer.open_net_go"), b -> openNetwork()));
        copyButton = addButton(new ButtonWidget(cx - 100, y + 130, 200, 20,
                GsText.t("gs.multiplayer.open_net_copy"), b -> {
            GuiUtil.copy(client, summary);
            GuiUtil.toast(client, GsText.t("gs.multiplayer.title"),
                    GsText.t("gs.multiplayer.copied"));
        }));
        copyButton.visible = false;
        closeButton = addButton(new ButtonWidget(cx - 100, y + 154, 200, 20,
                GsText.t("gs.multiplayer.open_net_close"), b -> closeNetwork()));
        closeButton.visible = false;
        setInitialFocus(portField);
    }

    private ButtonWidget openButton;
    private ButtonWidget copyButton;
    private ButtonWidget closeButton;

    private static String modeName(GameMode mode) {
        // own keys - GsText never resolves vanilla translation keys
        return GsText.format("gs.multiplayer.mode." + mode.getName());
    }

    private void openNetwork() {
        IntegratedServer server = this.client == null ? null : this.client.getServer();
        if (server == null || state == State.WORKING) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (port < 1024 || port > 65535) {
            state = State.FAILED;
            method = "";
            summary = GsText.t("gs.multiplayer.error.local_port").getString();
            return;
        }
        state = State.WORKING;
        openButton.active = false;
        final GameMode mode = MODES[modeIndex];
        final boolean cheats = cheatsBox.isChecked();
        final boolean allowAny = unlicensedBox.isChecked();
        WORKER.execute(() -> {
            boolean published = false;
            try {
                published = server.openToLan(mode, cheats, port);
            } catch (Exception e) {
                GsLog.warn("openToLan failed: " + e.getMessage());
            }
            if (published && allowAny) {
                try {
                    server.setOnlineMode(false); // friends without a license can join
                    GsLog.info("LAN server: online-mode off");
                } catch (Throwable t) {
                    GsLog.warn("setOnlineMode failed: " + t.getMessage());
                }
            }
            PortMapService.Result mapping = ConfigManager.get().connection.autoPortMap
                    ? PortMapService.get().map(port)
                    : new PortMapService.Result(false, null, -1);
            String publicIp = mapping.ok ? PortMapService.publicIp() : null;
            String localIp = PortMapService.localSiteIp();

            final String pub = publicIp != null ? publicIp + ":" + mapping.externalPort : null;
            final String lan = localIp != null ? localIp + ":" + port : null;
            String primary = pub != null ? pub : lan;

            synchronized (this) {
                if (primary != null) {
                    summary = primary;
                    method = mapping.ok ? mapping.method : "LAN";
                    state = State.OPEN;
                } else {
                    summary = "";
                    method = "";
                    state = State.FAILED;
                }
            }
            final String copyText = primary;
            this.client.execute(() -> {
                if (copyText != null) {
                    GuiUtil.copy(this.client, copyText); // auto-copy like e4mc
                }
            });
            this.client.execute(() -> {
                if (this.client == null || this.client.inGameHud == null) {
                    return;
                }
                if (pub != null) {
                    GuiUtil.chat(this.client, "gs.multiplayer.open_net_chat", pub);
                }
                if (lan != null && !lan.equals(pub)) {
                    GuiUtil.chat(this.client, "gs.multiplayer.open_net_chat_lan", lan);
                }
            });
        });
    }

    private void closeNetwork() {
        PortMapService.get().unmap();
        if (this.client != null && this.client.inGameHud != null) {
            GuiUtil.chat(this.client, "gs.multiplayer.open_net_closed");
        }
        onClose();
    }

    @Override
    public void onClose() {
        // The worker pool stays alive so the screen can be reopened; the port
        // mapping itself intentionally outlives the screen (the world is still open).
        super.onClose();
    }

    @Override
    public void removed() {
        // keep the mapping active while the world runs - nothing to undo here
    }

    @Override
    public void tick() {
        portField.tick();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height < 300 ? 42 : this.height / 4 + 4;
        drawLeft(matrices, GsText.t("gs.multiplayer.open_net_port").getString(),
                cx - 100, y + 12, 0x9090B0);

        if (state == State.WORKING) {
            drawCentered(matrices, GsText.t("gs.multiplayer.open_net_working"), cx, y + 168, 0xFFFF55);
            openButton.active = false;
        } else if (state == State.OPEN) {
            drawCentered(matrices, GsText.t("gs.multiplayer.open_net_ready", method), cx, y + 168, 0x55FF55);
            drawCentered(matrices, summary, cx, y + 182, 0x55FFFF);
            if ("LAN".equals(method)) {
                drawCentered(matrices, GsText.t("gs.multiplayer.open_net_hint"), cx, y + 196, 0x808080);
            }
            copyButton.visible = true;
            closeButton.visible = true;
            openButton.visible = false;
        } else if (state == State.FAILED) {
            drawCentered(matrices, GsText.t("gs.multiplayer.open_net_fail"), cx, y + 168, 0xFF5555);
            if (summary != null && !summary.isEmpty()) {
                drawCentered(matrices, summary, cx, y + 182, 0x55FFFF);
                drawCentered(matrices, GsText.t("gs.multiplayer.open_net_hint"), cx, y + 196, 0x808080);
            } else {
                drawCentered(matrices, GsText.t("gs.multiplayer.open_net_hint"), cx, y + 182, 0x808080);
            }
            openButton.active = true;
        } else {
            drawCentered(matrices, GsText.t("gs.multiplayer.open_net_hint"), cx, y + 168, 0x808080);
        }
    }
}
