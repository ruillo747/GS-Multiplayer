package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.friends.Friend;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

import java.util.List;

/** Local friends list with temporary presence and invitations. */
public final class FriendsScreen extends GsBaseScreen {

    private static final int ROWS = 5;

    private TextFieldWidget idField;
    private TextFieldWidget nameField;
    private int offset;

    public FriendsScreen(Screen parent) {
        super(new TranslatableText("gs.multiplayer.friends_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 8;
        int rowBottom = y + ROWS * 22 + 4;

        for (int i = 0; i < ROWS; i++) {
            final int index = i;
            ButtonWidget invite = addButton(new ButtonWidget(cx + 128, y + i * 22, 76, 18,
                    new TranslatableText("gs.multiplayer.invite_short"), b -> inviteAt(index)));
            invite.visible = false;
            ButtonWidget remove = addButton(new ButtonWidget(cx + 208, y + i * 22, 42, 18,
                    new TranslatableText("gs.multiplayer.remove_short"), b -> removeAt(index)));
            remove.visible = false;
        }

        ButtonWidget scrollUp = addButton(new ButtonWidget(cx - 250, rowBottom, 20, 18,
                new TranslatableText("gs.multiplayer.scroll_up"), b -> {
            if (offset > 0) {
                offset--;
                refreshRows();
            }
        }));
        ButtonWidget scrollDown = addButton(new ButtonWidget(cx - 226, rowBottom, 20, 18,
                new TranslatableText("gs.multiplayer.scroll_down"), b -> {
            offset++;
            refreshRows();
        }));
        addButton(new ButtonWidget(cx - 200, rowBottom, 148, 18,
                new TranslatableText("gs.multiplayer.refresh_presence"), b -> mod().rooms().watchFriends()));

        int formY = rowBottom + 24;
        idField = new TextFieldWidget(this.textRenderer, cx - 250, formY, 120, 18,
                new TranslatableText("gs.multiplayer.gs_id_hint"));
        idField.setMaxLength(16);
        nameField = new TextFieldWidget(this.textRenderer, cx - 122, formY, 128, 18,
                new TranslatableText("gs.multiplayer.friend_name"));
        nameField.setMaxLength(24);
        addButton(idField);
        addButton(nameField);
        addButton(new ButtonWidget(cx + 14, formY, 106, 18,
                new TranslatableText("gs.multiplayer.add_friend"), b -> addFriend()));
        addButton(new ButtonWidget(cx + 128, formY, 122, 18,
                new TranslatableText("gui.back"), b -> onClose()));

        mod().rooms().watchFriends();
        refreshRows();
        scrollUp.active = offset > 0;
    }

    private List<Friend> friends() {
        return mod().friends().list();
    }

    private void refreshRows() {
        List<Friend> list = friends();
        int y = this.height / 4 + 8;
        int cx = this.width / 2;
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
        int y = this.height / 4 + 8;
        if (list.isEmpty()) {
            drawCentered(matrices, new TranslatableText("gs.multiplayer.no_friends"),
                    cx, y + 8, 0xA0A0A0);
        }
        for (int i = 0; i < ROWS; i++) {
            int row = offset + i;
            if (row >= list.size()) {
                break;
            }
            Friend friend = list.get(row);
            int color = friend.online() ? 0x55FF55 : 0x808080;
            String dot = friend.inRoom() ? "●" : friend.online() ? "○" : "·";
            String line = dot + " " + friend.name + " §8" + friend.gsid;
            drawLeft(matrices, line, cx - 250, y + i * 22 + 5, color);
        }
    }

    @Override
    public void tick() {
        idField.tick();
        nameField.tick();
    }
}
