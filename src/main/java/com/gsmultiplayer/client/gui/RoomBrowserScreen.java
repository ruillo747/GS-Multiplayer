package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.room.RoomEntry;
import com.gsmultiplayer.room.RoomManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

import java.util.List;

/** Searches rooms on the signaling server and joins the picked one. */
public final class RoomBrowserScreen extends GsBaseScreen {

    private static final int SLOTS = 6;

    private List<RoomEntry> rooms = null;
    private boolean loading = true;

    public RoomBrowserScreen(Screen parent) {
        super(new TranslatableText("gs.multiplayer.browse_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 16;
        for (int i = 0; i < SLOTS; i++) {
            final int index = i;
            ButtonWidget button = addButton(new ButtonWidget(cx - 150, y + i * 24, 300, 20,
                    new TranslatableText("gs.multiplayer.empty_slot"), b -> joinRoomAt(index)));
            button.visible = false;
            button.active = false;
        }
        addButton(new ButtonWidget(cx - 150, y + SLOTS * 24 + 8, 148, 20,
                new TranslatableText("gs.multiplayer.refresh"), b -> refresh()));
        addButton(new ButtonWidget(cx + 2, y + SLOTS * 24 + 8, 148, 20,
                new TranslatableText("gui.back"), b -> onClose()));
        refresh();
    }

    private void refresh() {
        loading = true;
        rooms = null;
        GsMultiplayerClient mod = mod();
        if (mod == null) {
            return;
        }
        mod.rooms().listRooms("", result -> {
            rooms = result;
            loading = false;
            updateRoomButtons();
        });
    }

    private void updateRoomButtons() {
        List<RoomEntry> list = rooms;
        int shown = list == null ? 0 : Math.min(SLOTS, list.size());
        for (int i = 0; i < SLOTS; i++) {
            ButtonWidget button = (ButtonWidget) this.buttons.get(i);
            if (i < shown) {
                RoomEntry room = list.get(i);
                button.setMessage(new TranslatableText("gs.multiplayer.room_entry",
                        room.name, room.players, room.maxPlayers, room.code));
                button.visible = true;
                button.active = canJoin();
            } else {
                button.visible = false;
                button.active = false;
            }
        }
    }

    private boolean canJoin() {
        GsMultiplayerClient mod = mod();
        return mod != null && !mod.rooms().isHosting()
                && mod.rooms().getPhase() != RoomManager.Phase.CONNECTED;
    }

    private void joinRoomAt(int index) {
        List<RoomEntry> list = rooms;
        if (list == null || index >= list.size()) {
            return;
        }
        GsMultiplayerClient mod = mod();
        if (mod == null) {
            return;
        }
        mod.rooms().joinRoom(list.get(index).code);
        client.openScreen(new ConnectingScreen(this));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        if (loading) {
            drawCentered(matrices, new TranslatableText("gs.multiplayer.loading"),
                    this.width / 2, this.height / 4 + 4, 0xA0A0A0);
        } else if (rooms != null && rooms.isEmpty()) {
            drawCentered(matrices, new TranslatableText("gs.multiplayer.no_rooms"),
                    this.width / 2, this.height / 4 + 4, 0xA0A0A0);
        }
    }

    @Override
    public void tick() {
        updateRoomButtons();
    }
}
