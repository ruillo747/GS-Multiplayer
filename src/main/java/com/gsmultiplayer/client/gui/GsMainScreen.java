package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.config.GsConfig;
import com.gsmultiplayer.room.Invite;
import com.gsmultiplayer.room.RoomManager;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import com.gsmultiplayer.client.GsText;

/** GS Multiplayer main menu: rooms, friends, settings, diagnostics, invites, updates. */
public final class GsMainScreen extends GsBaseScreen {

    public GsMainScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.main_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;
        int bottom = this.height - 28;

        addButton(new ButtonWidget(cx - 100, y, 200, 20,
                GsText.t("gs.multiplayer.browse"), b -> client.openScreen(new RoomBrowserScreen(this))));
        addButton(new ButtonWidget(cx - 100, y + 24, 200, 20,
                GsText.t("gs.multiplayer.join_by_code"), b -> client.openScreen(new JoinByCodeScreen(this))));
        addButton(new ButtonWidget(cx - 100, y + 48, 200, 20,
                GsText.t("gs.multiplayer.friends"), b -> client.openScreen(new FriendsScreen(this))));
        addButton(new ButtonWidget(cx - 100, y + 72, 200, 20,
                GsText.t("gs.multiplayer.settings"), b -> client.openScreen(new SettingsScreen(this))));
        addButton(new ButtonWidget(cx - 100, y + 96, 200, 20,
                GsText.t("gs.multiplayer.diagnostics"), b -> client.openScreen(new DiagnosticsScreen(this))));
        addButton(new ButtonWidget(cx - 100, bottom, 200, 20,
                GsText.t("gui.done"), b -> onClose()));

        // Pending invitation: one at a time, most recent first.
        GsMultiplayerClient mod = mod();
        if (mod != null) {
            java.util.List<Invite> invites = mod.rooms().getInvites();
            if (!invites.isEmpty()) {
                Invite invite = invites.get(invites.size() - 1);
                int iy = y + 128;
                addButton(new ButtonWidget(cx - 100, iy, 130, 20,
                        GsText.t("gs.multiplayer.invite_from", invite.fromName),
                        b -> quiet(() -> mod.rooms().respondToInvite(invite, true))));
                addButton(new ButtonWidget(cx + 34, iy, 66, 20,
                        GsText.t("gs.multiplayer.decline"),
                        b -> quiet(() -> mod.rooms().respondToInvite(invite, false))));
            }
        }

        // Update banner.
        GsConfig config = com.gsmultiplayer.config.ConfigManager.get();
        if (config.updates.enabled && mod != null) {
            UpdateChecker.Result result = mod.updates().getResult();
            if (result.status == UpdateChecker.Status.AVAILABLE) {
                int uy = bottom - 26;
                addButton(new ButtonWidget(cx - 100, uy, 98, 20,
                        GsText.t("gs.multiplayer.update_details"),
                        b -> GuiUtil.openUrl(result.releaseUrl)));
                addButton(new ButtonWidget(cx + 2, uy, 98, 20,
                        GsText.t("gs.multiplayer.update_download"),
                        b -> GuiUtil.openUrl(result.jarUrl != null ? result.jarUrl : result.releaseUrl)));
            }
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        GsMultiplayerClient mod = mod();
        if (mod == null) {
            return;
        }
        RoomManager rooms = mod.rooms();
        int y = this.height / 4 - 26;
        int color = rooms.isSignalingOnline() ? 0x55FF55 : 0xFF5555;
        drawCentered(matrices, GsText.t(rooms.isSignalingOnline()
                ? "gs.multiplayer.status_online" : "gs.multiplayer.status_offline"), cx, y, color);
        drawCentered(matrices, GsText.t("gs.multiplayer.your_id",
                com.gsmultiplayer.config.ConfigManager.get().user.gsId), cx, y + 10, 0xA0A0A0);

        UpdateChecker.Result result = mod.updates().getResult();
        if (result.status == UpdateChecker.Status.AVAILABLE) {
            drawCentered(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion),
                    cx, this.height - 44, 0xFFFF55);
        } else if (result.status == UpdateChecker.Status.ERROR
                && !"repo not configured".equals(result.message)) {
            GsLog.debug("update check: " + result.message);
        }
    }

    @Override
    public void tick() {
        // refresh invitation/update rows occasionally
        if (this.client != null && (this.client.world == null)) {
            // cheap no-op; init() is re-run when returning from child screens
        }
    }
}
