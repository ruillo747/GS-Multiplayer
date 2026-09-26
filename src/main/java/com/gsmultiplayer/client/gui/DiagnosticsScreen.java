package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.GsMultiplayerMod;
import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.room.RoomManager;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;
import com.gsmultiplayer.client.GsText;

/** "Settings → Diagnostics": GS Multiplayer Log plus the developer-mode stats panel. */
public final class DiagnosticsScreen extends GsBaseScreen {

    private static final int LOG_LINES = 12;

    public DiagnosticsScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.diagnostics_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bottom = this.height - 30;
        addButton(new ButtonWidget(cx - 204, bottom, 132, 20,
                GsText.t("gs.multiplayer.check_updates"), b -> mod().checkForUpdatesNow()));
        addButton(new ButtonWidget(cx - 66, bottom, 132, 20,
                GsText.t("gs.multiplayer.copy_log"), b -> {
            GuiUtil.copy(client, GsLog.dump());
            GuiUtil.toast(client, GsText.t("gs.multiplayer.title"),
                    GsText.t("gs.multiplayer.copied"));
        }));
        addButton(new ButtonWidget(cx + 72, bottom, 132, 20,
                GsText.t("gui.back"), b -> onClose()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int left = cx - 204;
        RoomManager rooms = mod().rooms();

        String state = GsText.t(rooms.isSignalingOnline()
                ? "gs.multiplayer.status_online" : "gs.multiplayer.status_offline").getString();
        drawLeft(matrices, GsText.t("gs.multiplayer.diag_summary",
                GsMultiplayerMod.VERSION, state, rooms.getPhase().name()).getString(), left, 32, 0xA0A0A0);
        drawLeft(matrices, GsText.t("gs.multiplayer.your_id",
                ConfigManager.get().user.gsId).getString(), left, 44, 0xA0A0A0);

        int y = 60;
        if (ConfigManager.get().diagnostics.devMode) {
            drawLeft(matrices, GsText.t("gs.multiplayer.dev_panel").getString(), left, y, 0xFFFF55);
            y += 12;
            List<RoomManager.PeerDiagnostics> peers = rooms.peerDiagnostics();
            if (peers.isEmpty()) {
                drawLeft(matrices, GsText.t("gs.multiplayer.no_tunnels").getString(), left, y, 0x808080);
                y += 12;
            }
            for (RoomManager.PeerDiagnostics peer : peers) {
                String transport = "RELAY".equals(peer.transport) ? "Active" : "Standby";
                String line = peer.name + ":  " + peer.state
                        + "  |  " + GsText.t("gs.multiplayer.diag_latency").getString() + ": "
                        + (peer.rttMs >= 0 ? peer.rttMs + " ms" : "n/a")
                        + "  |  Relay: " + transport
                        + "  |  " + GsText.t("gs.multiplayer.diag_loss").getString() + ": "
                        + String.format(java.util.Locale.ROOT, "%.0f%%", peer.lossPct);
                drawLeft(matrices, line, left, y, 0x55FF55);
                y += 12;
            }
            y += 4;
        } else {
            drawLeft(matrices, GsText.t("gs.multiplayer.dev_hint").getString(), left, y, 0x606060);
            y += 12;
        }

        y += 4;
        drawLeft(matrices, "GS Multiplayer Log", left, y, 0x55FFFF);
        y += 12;
        List<String> log = GsLog.recent(LOG_LINES);
        for (String line : log) {
            drawLeft(matrices, line, left, y, 0x707070);
            y += 10;
        }

        UpdateChecker.Result result = mod().updates().getResult();
        if (result.status == UpdateChecker.Status.AVAILABLE) {
            drawLeft(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion).getString(),
                    cx - 204, this.height - 40, 0xFFFF55);
        }
    }
}
