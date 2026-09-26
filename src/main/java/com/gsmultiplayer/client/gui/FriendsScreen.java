package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.friends.Friend;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;
import com.gsmultiplayer.client.GsText;

/** Local friends list with temporary presence and invitations. */
public final class FriendsScreen extends GsBaseScreen {

    private static final int ROWS = 4;

    private TextFieldWidget idField;
    private TextFieldWidget nameField;
    private int offset;

    public FriendsScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.friends_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        boolean compact = this.height < 300;
        int y0 = compact ? 44 : this.height / 4 + 2;
        int left = cx - 190;   // whole content is 380 GUI units wide - fits 854x480
        int rowBottom = y0 + ROWS * 22 + 4;

        for (int i = 0; i < ROWS; i++) {
            final int index = i;
            ButtonWidget invite = addButton(new ButtonWidget(left + 282, y0 + i * 22 + 1, 50, 18,
                    GsText.t("gs.multiplayer.invite_short"), b -> inviteAt(index)));
            invite.visible = false;
            ButtonWidget remove = addButton(new ButtonWidget(left + 336, y0 + i * 22 + 1, 44, 18,
                    GsText.t("gs.multiplayer.remove_short"), b -> removeAt(index)));
            remove.visible = false;
        }

        ButtonWidget scrollUp = addButton(new ButtonWidget(left, rowBottom, 20, 18,
                GsText.t("gs.multiplayer.scroll_up"), b -> {
            if (offset > 0) {
                offset--;
                refreshRows();
            }
        }));
        ButtonWidget scrollDown = addButton(new ButtonWidget(left + 24, rowBottom, 20, 18,
                GsText.t("gs.multiplayer.scroll_down"), b -> {
            offset++;
            refreshRows();
        }));
        addButton(new ButtonWidget(left + 48, rowBottom, 160, 18,
                GsText.t("gs.multiplayer.refresh_presence"), b -> mod().rooms().watchFriends()));

        int formY = rowBottom + 36;
        idField = new TextFieldWidget(this.textRenderer, left, formY, 118, 18,
                GsText.t("gs.multiplayer.gs_id_hint"));
        idField.setMaxLength(16);
        nameField = new TextFieldWidget(this.textRenderer, left + 126, formY, 118, 18,
                GsText.t("gs.multiplayer.friend_name"));
        nameField.setMaxLength(24);
        addButton(idField);
        addButton(nameField);
        addButton(new ButtonWidget(left + 250, formY, 84, 18,
                GsText.t("gs.multiplayer.add_friend"), b -> addFriend()));
        addButton(new ButtonWidget(left + 338, formY, 42, 18,
                GsText.t("gui.back"), b -> onClose()));

        mod().rooms().watchFriends();
        refreshRows();
        scrollUp.active = offset > 0;
    }

    private List<Friend> friends() {
        return mod().friends().list();
    }

    private void refreshRows() {
        List<Friend> list = friends();
        for (int i = 0; i < ROWS; i++) {
            ButtonWidget invite = (ButtonWidget) this.buttons.get(i * 2);
            ButtonWidget remove = (ButtonWidget) this.buttons.get(i * 2 + 1);
            int row = offset + i;
            if (row < list.size()) {
                Friend friend = list.get(row);
                boolean hosting = mod().rooms().isHosting();
                invite.visible = true;
                invite.active = hosting && friend.online();
                remove.visible = true;
                remove.active = true;
            } else {
                invite.visible = false;
                remove.visible = false;
            }
        }
        // enable/disable scroll buttons
        this.buttons.get(ROWS * 2).active = offset > 0;
        this.buttons.get(ROWS * 2 + 1).active = offset + ROWS < list.size();
    }

    private void inviteAt(int row) {
        List<Friend> list = friends();
        int index = offset + row;
        if (index < list.size()) {
            mod().rooms().inviteFriend(list.get(index).gsid);
        }
    }

    private void removeAt(int row) {
        List<Friend> list = friends();
        int index = offset + row;
        if (index < list.size()) {
            mod().friends().remove(list.get(index).gsid);
            refreshRows();
        }
    }

    private void addFriend() {
        String gsid = idField.getText().trim();
        String name = nameField.getText().trim();
        if (gsid.isEmpty()) {
            return;
        }
        if (mod().friends().addOrUpdate(gsid, name)) {
            idField.setText("");
            nameField.setText("");
            mod().rooms().watchFriends();
            refreshRows();
        } else {
            idField.setText("");
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        List<Friend> list = friends();
        int cx = this.width / 2;
        boolean compact = this.height < 300;
        int y0 = compact ? 44 : this.height / 4 + 2;
        int left = cx - 190;
        int formY = y0 + ROWS * 22 + 40;
        drawLeft(matrices, GsText.t("gs.multiplayer.gs_id_hint").getString(), left, formY - 11, 0x9090B0);
        drawLeft(matrices, GsText.t("gs.multiplayer.friend_name").getString(), left + 126, formY - 11, 0x9090B0);
        if (list.isEmpty()) {
            drawCentered(matrices, GsText.t("gs.multiplayer.no_friends"),
                    cx, y0 + 4, 0xA0A0A0);
        }
        for (int i = 0; i < ROWS; i++) {
            int row = offset + i;
            if (row >= list.size()) {
                break;
            }
            Friend friend = list.get(row);
            int color = friend.online() ? 0x55FF55 : 0x808080;
            String dot = friend.inRoom() ? "\u25cf" : friend.online() ? "\u25cb" : "\u00b7";
            String line = dot + " " + friend.name + " \u00a78" + friend.gsid;
            drawLeft(matrices, line, left, y0 + i * 22 + 5, color);
        }
    }

    @Override
    public void tick() {
        idField.tick();
        nameField.tick();
    }
}
