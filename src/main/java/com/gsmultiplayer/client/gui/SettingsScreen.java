package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.config.GsConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

import java.util.Locale;
import com.gsmultiplayer.client.GsText;

/** Local settings: identity, server addresses, connection, UI, diagnostics. */
public final class SettingsScreen extends GsBaseScreen {

    private TextFieldWidget nicknameField;
    private TextFieldWidget signalingField;
    private TextFieldWidget relayHostField;
    private TextFieldWidget relayPortField;
    private TextFieldWidget lanPortField;
    private CheckboxWidget devModeBox;
    private CheckboxWidget updatesBox;
    private CheckboxWidget titleButtonBox;
    private CheckboxWidget pauseButtonBox;

    public SettingsScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.settings_title"), parent);
    }

    @Override
    protected void init() {
        GsConfig config = ConfigManager.get();
        int cx = this.width / 2;
        int y = this.height / 4 - 6;

        nicknameField = addField(cx, y, config.user.nickname, 24, "gs.multiplayer.nickname");
        signalingField = addField(cx, y + 26, config.signalingUrl, 128, "gs.multiplayer.signaling_url");
        relayHostField = addField(cx, y + 52, config.relayHost, 100, "gs.multiplayer.relay_host");

        relayPortField = new TextFieldWidget(this.textRenderer, cx + 104, y + 52, 96, 20,
                GsText.t("gs.multiplayer.relay_port"));
        relayPortField.setMaxLength(5);
        relayPortField.setTextPredicate(s -> s.matches("[0-9]*"));
        relayPortField.setText(String.valueOf(config.relayPort));
        addButton(relayPortField);

        lanPortField = new TextFieldWidget(this.textRenderer, cx + 104, y + 78, 96, 20,
                GsText.t("gs.multiplayer.lan_port"));
        lanPortField.setMaxLength(5);
        lanPortField.setTextPredicate(s -> s.matches("[0-9]*"));
        lanPortField.setText(String.valueOf(config.connection.lanPort));
        addButton(lanPortField);

        devModeBox = addButton(new CheckboxWidget(cx - 100, y + 84, 200, 20,
                GsText.t("gs.multiplayer.dev_mode"), config.diagnostics.devMode));
        updatesBox = addButton(new CheckboxWidget(cx - 100, y + 108, 200, 20,
                GsText.t("gs.multiplayer.update_check"), config.updates.enabled));
        titleButtonBox = addButton(new CheckboxWidget(cx - 100, y + 132, 200, 20,
                GsText.t("gs.multiplayer.title_button"), config.ui.showTitleButton));
        pauseButtonBox = addButton(new CheckboxWidget(cx - 100, y + 156, 200, 20,
                GsText.t("gs.multiplayer.pause_button"), config.ui.showPauseButton));

        addButton(new ButtonWidget(cx - 100, this.height - 54, 200, 20,
                GsText.t("gs.multiplayer.save"), b -> save()));
        addButton(new ButtonWidget(cx - 100, this.height - 30, 200, 20,
                GsText.t("gui.back"), b -> onClose()));
    }

    private TextFieldWidget addField(int cx, int y, String value, int maxLength, String key) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, cx - 100, y, 200, 20,
                GsText.t(key));
        field.setMaxLength(maxLength);
        field.setText(value == null ? "" : value);
        addButton(field);
        return field;
    }

    private void save() {
        GsConfig config = ConfigManager.get();
        config.user.nickname = nicknameField.getText().trim();
        String url = signalingField.getText().trim();
        if (url.startsWith("ws://") || url.startsWith("wss://")) {
            config.signalingUrl = url;
        }
        config.relayHost = relayHostField.getText().trim();
        config.relayPort = parsePort(relayPortField.getText(), config.relayPort);
        config.connection.lanPort = parsePort(lanPortField.getText(), config.connection.lanPort);
        config.diagnostics.devMode = devModeBox.isChecked();
        config.updates.enabled = updatesBox.isChecked();
        config.ui.showTitleButton = titleButtonBox.isChecked();
        config.ui.showPauseButton = pauseButtonBox.isChecked();
        ConfigManager.save();
        mod().applySettings();
        client.openScreen(parent);
    }

    private static int parsePort(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value >= 1 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException e) {
            // keep fallback
        }
        return fallback;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height / 4 - 6;
        drawLeft(matrices, GsText.t("gs.multiplayer.relay_port").getString(), cx + 104, y + 46, 0x808080);
        drawLeft(matrices, GsText.t("gs.multiplayer.lan_port").getString(), cx + 104, y + 72, 0x808080);
        drawCentered(matrices, GsText.t("gs.multiplayer.your_id",
                ConfigManager.get().user.gsId), cx, y + 182, 0xA0A0A0);
    }

    @Override
    public void tick() {
        nicknameField.tick();
        signalingField.tick();
        relayHostField.tick();
        relayPortField.tick();
        lanPortField.tick();
    }
}
