package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.GsText;
import com.gsmultiplayer.room.RoomEntry;
import com.gsmultiplayer.room.RoomManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;

/** Searches rooms on the signaling server and joins the picked one. */
public final class RoomBrowserScreen extends GsBaseScreen {

    private static final int SLOTS = 4;

    private List<RoomEntry> rooms = null;
    private boolean loading = true;
    private int page;
    private ButtonWidget pageLabel;
    private ButtonWidget prevButton;
    private ButtonWidget nextButton;

    public RoomBrowserScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.browse_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        boolean compact = this.height < 300;
        int y = compact ? 42 : this.height / 4 + 8;

        for (int i = 0; i < SLOTS; i++) {
            final int index = i;
            ButtonWidget button = addButton(new ButtonWidget(cx - 150, y + i * 22, 300, 20,
                    GsText.t("gs.multiplayer.empty_slot"), b -> joinRoomAt(page * SLOTS + index)));
            button.visible = false;
            button.active = false;
        }

        int pageY = y + SLOTS * 22 + 4;
        prevButton = addButton(new ButtonWidget(cx - 150, pageY, 30, 20,
                new net.minecraft.text.LiteralText("<"), b -> {
            if (page > 0) {
                page--;
                updateRoomButtons();
            }
        }));
        pageLabel = addButton(new ButtonWidget(cx - 116, pageY, 232, 20,
                GsText.t("gs.multiplayer.page", 1, 1), b -> { }));
        pageLabel.active = false;
        nextButton = addButton(new ButtonWidget(cx + 120, pageY, 30, 20,
                new net.minecraft.text.LiteralText(">"), b -> {
            page++;
            updateRoomButtons();
        }));

        addButton(new ButtonWidget(cx - 150, pageY + 24, 300, 20,
                GsText.t("gs.multiplayer.join_ip_short"), b -> client.openScreen(new JoinByIpScreen(this))));
        addButton(new ButtonWidget(cx - 150, pageY + 48, 148, 20,
                GsText.t("gs.multiplayer.refresh"), b -> refresh()));
        addButton(new ButtonWidget(cx + 2, pageY + 48, 148, 20,
                GsText.t("gui.back"), b -> onClose()));

        refresh();
    }

    private void refresh() {
        loading = true;
        rooms = null;
        page = 0;
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
        int total = list == null ? 0 : list.size();
        int pages = Math.max(1, (total + SLOTS - 1) / SLOTS);
        if (page >= pages) {
            page = pages - 1;
        }
        pageLabel.setMessage(GsText.t("gs.multiplayer.page", page + 1, pages));
        prevButton.active = page > 0;
        nextButton.active = page < pages - 1;
        boolean canJoin = canJoin();
        for (int i = 0; i < SLOTS; i++) {
            ButtonWidget button = (ButtonWidget) this.buttons.get(i);
            int index = page * SLOTS + i;
            if (index < total) {
                RoomEntry room = list.get(index);
                button.setMessage(GsText.t("gs.multiplayer.room_entry",
                        room.name, room.players, room.maxPlayers, room.code));
                button.visible = true;
                button.active = canJoin;
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
            drawCentered(matrices, GsText.t("gs.multiplayer.loading"),
                    this.width / 2, this.height < 300 ? 34 : this.height / 4 - 4, 0xA0A0A0);
        } else if (rooms != null && rooms.isEmpty()) {
            drawCentered(matrices, GsText.t("gs.multiplayer.no_rooms"),
                    this.width / 2, this.height < 300 ? 34 : this.height / 4 - 4, 0xA0A0A0);
        }
    }

    @Override
    public void tick() {
        updateRoomButtons();
    }
}
