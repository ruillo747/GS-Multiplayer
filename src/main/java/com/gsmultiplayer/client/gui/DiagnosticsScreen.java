package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.GsMultiplayerMod;
import com.gsmultiplayer.client.GsText;
import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;

/** Version info and the GS Multiplayer Log. */
public final class DiagnosticsScreen extends GsBaseScreen {

    private static final int LOG_LINES = 9;

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

        boolean inWorld = this.client != null && this.client.getServer() != null;
        drawLeft(matrices, GsText.t("gs.multiplayer.diag_summary", GsMultiplayerMod.VERSION).getString(),
                left, 32, 0xA0A0A0);
        drawLeft(matrices, GsText.format(inWorld
                        ? "gs.multiplayer.diag_world_open" : "gs.multiplayer.diag_world_closed",
                String.valueOf(ConfigManager.get().connection.lanPort)), left, 44, 0xA0A0A0);

        UpdateChecker.Result result = mod().updates().getResult();
        if (result.status == UpdateChecker.Status.AVAILABLE) {
            drawLeft(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion).getString(),
                    left, 46, 0xFFFF55);
        }

        int y = 64;
        drawLeft(matrices, "GS Multiplayer Log", left, y, 0x55FFFF);
        y += 12;
        List<String> log = GsLog.recent(LOG_LINES);
        for (String line : log) {
            drawLeft(matrices, line, left, y, 0x707070);
            y += 10;
        }
    }
}
