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
        boolean compact = this.height < 320;   // e.g. 854x480 windowed at scale 2
        int actionY;
        int doneY;
        int inviteY;
        if (compact) {
            // 2-column grid: the whole menu must fit 240 GUI units.
            // 148-wide columns: long RU labels ("Подключиться по коду") must fit
            int y0 = 48;
            addButton(new ButtonWidget(cx - 150, y0, 148, 20,
                    GsText.t("gs.multiplayer.browse"), b -> client.openScreen(new RoomBrowserScreen(this))));
            addButton(new ButtonWidget(cx + 2, y0, 148, 20,
                    GsText.t("gs.multiplayer.join_by_code"), b -> client.openScreen(new JoinByCodeScreen(this))));
            addButton(new ButtonWidget(cx - 150, y0 + 22, 148, 20,
                    GsText.t("gs.multiplayer.join_by_ip"), b -> client.openScreen(new JoinByIpScreen(this))));
            addButton(new ButtonWidget(cx + 2, y0 + 22, 148, 20,
                    GsText.t("gs.multiplayer.friends"), b -> client.openScreen(new FriendsScreen(this))));
            addButton(new ButtonWidget(cx - 150, y0 + 44, 148, 20,
                    GsText.t("gs.multiplayer.settings"), b -> client.openScreen(new SettingsScreen(this))));
            addButton(new ButtonWidget(cx + 2, y0 + 44, 148, 20,
                    GsText.t("gs.multiplayer.diagnostics"), b -> client.openScreen(new DiagnosticsScreen(this))));
            actionY = y0 + 66;
            doneY = y0 + 90;
            inviteY = y0 + 116;
        } else {
            int y = this.height / 4;
            int step = 24;
            String[] keys = {"gs.multiplayer.browse", "gs.multiplayer.join_by_code",
                    "gs.multiplayer.join_by_ip", "gs.multiplayer.friends",
                    "gs.multiplayer.settings", "gs.multiplayer.diagnostics"};
            for (int i = 0; i < keys.length; i++) {
                final int row = i;
                addButton(new ButtonWidget(cx - 100, y + i * step, 200, 20,
                        GsText.t(keys[i]), b -> openRow(row)));
            }
            actionY = y + keys.length * step;
            doneY = this.height - 28;
            inviteY = y + keys.length * step + 28;
        }

        // Context action: disconnect when in a room, "open to network" in a world.
        GsMultiplayerClient mod = mod();
        boolean connected = mod != null && (mod.rooms().isHosting()
                || mod.rooms().getPhase() == RoomManager.Phase.CONNECTED);
        boolean inWorld = this.client != null && this.client.getServer() != null;
        if (connected) {
            addButton(new ButtonWidget(cx - 100, actionY, 200, 20,
                    GsText.t("gs.multiplayer.disconnect"), b -> {
                mod.rooms().leaveRoom();
                this.init(this.client, this.width, this.height);
            }));
        } else if (inWorld) {
            addButton(new ButtonWidget(cx - 100, actionY, 200, 20,
                    GsText.t("gs.multiplayer.open_net_short"),
                    b -> client.openScreen(new OpenToNetworkScreen(this))));
        }
        addButton(new ButtonWidget(cx - 100, doneY, 200, 20,
                GsText.t("gui.done"), b -> onClose()));

        // Pending invitation: one at a time, most recent first.
        if (mod != null && this.height >= 300) {
            java.util.List<Invite> invites = mod.rooms().getInvites();
            if (!invites.isEmpty()) {
                Invite invite = invites.get(invites.size() - 1);
                addButton(new ButtonWidget(cx - 100, inviteY, 130, 20,
                        GsText.t("gs.multiplayer.invite_from", invite.fromName),
                        b -> quiet(() -> {
                            mod.rooms().respondToInvite(invite, true);
                            this.init(this.client, this.width, this.height);
                        })));
                addButton(new ButtonWidget(cx + 34, inviteY, 66, 20,
                        GsText.t("gs.multiplayer.decline"),
                        b -> quiet(() -> {
                            mod.rooms().respondToInvite(invite, false);
                            this.init(this.client, this.width, this.height);
                        })));
            }
        }
    }

    private void openRow(int row) {
        switch (row) {
            case 0:
                client.openScreen(new RoomBrowserScreen(this));
                break;
            case 1:
                client.openScreen(new JoinByCodeScreen(this));
                break;
            case 2:
                client.openScreen(new JoinByIpScreen(this));
                break;
            case 3:
                client.openScreen(new FriendsScreen(this));
                break;
            case 4:
                client.openScreen(new SettingsScreen(this));
                break;
            default:
                client.openScreen(new DiagnosticsScreen(this));
                break;
        }
    }

    @Override
    protected int titleY() {
        return this.height < 320 ? 4 : 14;
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
        boolean compact = this.height < 320;
        int y = compact ? 16 : this.height / 4 - 26;
        int color = rooms.isSignalingOnline() ? 0x55FF55 : 0xFF5555;
        drawCentered(matrices, GsText.t(rooms.isSignalingOnline()
                ? "gs.multiplayer.status_online" : "gs.multiplayer.status_offline"), cx, y, color);
        drawCentered(matrices, GsText.t("gs.multiplayer.your_id",
                com.gsmultiplayer.config.ConfigManager.get().user.gsId), cx, y + 10, 0xA0A0A0);

        UpdateChecker.Result result = mod.updates().getResult();
        if (result.status == UpdateChecker.Status.AVAILABLE) {
            boolean invitePending = !mod.rooms().getInvites().isEmpty();
            if (compact) {
                if (!invitePending) {
                    drawCentered(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion),
                            cx, 190, 0xFFFF55);
                }
            } else {
                drawCentered(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion),
                        cx, this.height - 44, 0xFFFF55);
            }
        } else if (result.status == UpdateChecker.Status.ERROR
                && !"repo not configured".equals(result.message)) {
            GsLog.debug("update check: " + result.message);
        }
    }

    private int lastInviteCount = -1;
    private boolean lastUpdateSeen;

    @Override
    public void tick() {
        // Rebuild rows only when something actually changed (invite answered,
        // update check finished) - rebuilding every tick would flicker.
        GsMultiplayerClient mod = mod();
        if (mod == null || this.client == null) {
            return;
        }
        int invites = mod.rooms().getInvites().size();
        boolean updateSeen = mod.updates().getResult().status
                != com.gsmultiplayer.update.UpdateChecker.Status.CHECKING;
        if (invites != lastInviteCount || updateSeen != lastUpdateSeen) {
            lastInviteCount = invites;
            lastUpdateSeen = updateSeen;
            this.init(this.client, this.width, this.height);
        }
    }
}
