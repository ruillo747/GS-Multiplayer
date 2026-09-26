package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.GuiUtil;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;
import com.gsmultiplayer.client.GsText;

/** Live room view for the host: code, players, invites. */
public final class HostActiveScreen extends GsBaseScreen {

    private TextFieldWidget friendField;

    public HostActiveScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.host_active_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 24;
        addButton(new ButtonWidget(cx - 100, y, 200, 20,
                GsText.t("gs.multiplayer.copy_code"), b -> {
            String code = mod().rooms().getRoomCode();
            if (code != null) {
                GuiUtil.copy(client, code);
                GuiUtil.toast(client, GsText.t("gs.multiplayer.title"),
                        GsText.t("gs.multiplayer.copied"));
            }
        }));

        friendField = new TextFieldWidget(this.textRenderer, cx - 100, y + 44, 200, 20,
                GsText.t("gs.multiplayer.gs_id_hint"));
        friendField.setMaxLength(16);
        addButton(friendField);
        addButton(new ButtonWidget(cx - 100, y + 68, 200, 20,
                GsText.t("gs.multiplayer.invite_button"), b -> {
            String gsid = friendField.getText().trim();
            if (!gsid.isEmpty()) {
                mod().rooms().inviteFriend(gsid);
                friendField.setText("");
            }
        }));
        addButton(new ButtonWidget(cx - 100, this.height - 54, 200, 20,
                GsText.t("gs.multiplayer.close_room"), b -> {
            mod().rooms().leaveRoom();
            this.client.openScreen(null);
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        GsMultiplayerClient mod = mod();
        String code = mod.rooms().getRoomCode();
        drawCentered(matrices, code == null ? "…" : code, cx, this.height / 4 - 4, 0x55FFFF);

        if (code == null) {
            drawCentered(matrices, GsText.t("gs.multiplayer.room_closing"),
                    cx, this.height / 4 + 16, 0xFF5555);
            return;
        }
        List<com.gsmultiplayer.room.RoomManager.PeerDiagnostics> peers = mod.rooms().peerDiagnostics();
        int y = this.height / 4 + 100;
        if (peers.isEmpty()) {
            drawCentered(matrices, GsText.t("gs.multiplayer.waiting_players"),
                    cx, y, 0xA0A0A0);
        } else {
            for (com.gsmultiplayer.room.RoomManager.PeerDiagnostics peer : peers) {
                String line = peer.name + " — " + transportLabel(peer.transport)
                        + ", " + (peer.rttMs >= 0 ? peer.rttMs + " ms" : "…");
                drawCentered(matrices, line, cx, y, 0x55FF55);
                y += 12;
            }
        }
    }

    private static String transportLabel(String transport) {
        if ("P2P".equals(transport)) {
            return "P2P";
        }
        if ("RELAY".equals(transport)) {
            return "Relay";
        }
        return "…";
    }

    @Override
    public void tick() {
        friendField.tick();
    }
}
