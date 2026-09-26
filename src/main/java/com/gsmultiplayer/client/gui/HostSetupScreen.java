package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.GsMultiplayerMod;
import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
import com.gsmultiplayer.client.GsText;

/** Publishes the running singleplayer world and opens a room for it. */
public final class HostSetupScreen extends GsBaseScreen {

    private static final GameMode[] MODES = {GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE};

    private TextFieldWidget nameField;
    private int modeIndex;
    private CheckboxWidget cheatsBox;

    public HostSetupScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.host_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 8;

        nameField = new TextFieldWidget(this.textRenderer, cx - 100, y, 200, 20,
                GsText.t("gs.multiplayer.room_name"));
        nameField.setMaxLength(32);
        nameField.setText(defaultRoomName());
        addButton(nameField);

        ButtonWidget modeButton = addButton(new ButtonWidget(cx - 100, y + 26, 200, 20,
                GsText.t("gs.multiplayer.game_mode", modeName(MODES[modeIndex])),
                b -> {
                    modeIndex = (modeIndex + 1) % MODES.length;
                    b.setMessage(GsText.t("gs.multiplayer.game_mode",
                            modeName(MODES[modeIndex])));
                }));

        cheatsBox = addButton(new CheckboxWidget(cx - 100, y + 50, 200, 20,
                GsText.t("gs.multiplayer.cheats"), false));

        addButton(new ButtonWidget(cx - 100, y + 78, 200, 20,
                GsText.t("gs.multiplayer.open_room"), b -> openRoom()));
        addButton(new ButtonWidget(cx - 100, y + 102, 200, 20,
                GsText.t("gui.cancel"), b -> onClose()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height / 4 + 8;
        drawLeft(matrices, GsText.t("gs.multiplayer.room_name").getString(), cx - 100, y - 11, 0x9090B0);
    }

    private static String modeName(GameMode mode) {
        // own keys: GsText knows only our lang files, never vanilla ones
        return GsText.format("gs.multiplayer.mode." + mode.getName());
    }

    private String defaultRoomName() {
        String player = this.client != null && this.client.getSession() != null
                ? this.client.getSession().getUsername() : "Player";
        return GsText.format("gs.multiplayer.default_world_name", player);
    }

    private void openRoom() {
        IntegratedServer server = this.client == null ? null : this.client.getServer();
        GsMultiplayerClient mod = mod();
        if (server == null || mod == null) {
            return;
        }
        int port = ConfigManager.get().connection.lanPort;
        GameMode mode = MODES[modeIndex];
        boolean cheats = cheatsBox.isChecked();
        boolean published;
        try {
            published = server.openToLan(mode, cheats, port);
        } catch (Exception e) {
            published = false;
        }
        if (!published) {
            GuiUtil.toast(this.client,
                    GsText.t("gs.multiplayer.title"),
                    GsText.t("gs.multiplayer.lan_warn"));
            GsLog.warn("openToLan returned false; the port may already be in use");
        }
        String roomName = nameField.getText().trim();
        if (roomName.isEmpty()) {
            roomName = defaultRoomName();
        }
        mod.rooms().hostRoom(roomName, port);
        this.client.openScreen(null);
    }
}
