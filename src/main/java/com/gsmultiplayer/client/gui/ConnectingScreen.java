package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.room.RoomManager;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

import java.util.List;

/** Shown while joining a room: negotiation, punching or relay fallback in progress. */
public final class ConnectingScreen extends GsBaseScreen {

    private long openedAt = System.currentTimeMillis();

    public ConnectingScreen(Screen parent) {
        super(new TranslatableText("gs.multiplayer.connecting_title"), parent);
    }

    @Override
    protected void init() {
        addButton(new ButtonWidget(this.width / 2 - 100, this.height / 4 + 110, 200, 20,
                new TranslatableText("gs.multiplayer.cancel"), b -> {
            mod().rooms().leaveRoom();
            onClose();
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        RoomManager rooms = mod().rooms();
        RoomManager.Phase phase = rooms.getPhase();

        String statusKey;
        if (phase == RoomManager.Phase.CONNECTED) {
            statusKey = "gs.multiplayer.connecting_launch";
        } else if (phase == RoomManager.Phase.IDLE) {
            statusKey = "gs.multiplayer.connecting_stopped";
        } else {
            statusKey = "gs.multiplayer.connecting_negotiating";
        }
        drawCentered(matrices, new TranslatableText(statusKey), cx, this.height / 4 + 10, 0xFFFF55);

        // animated dots
        long dots = (System.currentTimeMillis() - openedAt) / 400 % 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dots; i++) {
            sb.append('.');
        }
        drawCentered(matrices, sb.toString(), cx, this.height / 4 + 22, 0xFFFF55);

        List<String> log = GsLog.recent(5);
        int y = this.height / 4 + 44;
        for (String line : log) {
            drawCentered(matrices, line, cx, y, 0x707070);
            y += 10;
        }
    }
}
