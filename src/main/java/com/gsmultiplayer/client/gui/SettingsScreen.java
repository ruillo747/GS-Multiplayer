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
import com.gsmultiplayer.client.GuiUtil;

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
    private CheckboxWidget upnpBox;

    public SettingsScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.settings_title"), parent);
    }

    @Override
    protected void init() {
        GsConfig config = ConfigManager.get();
        int cx = this.width / 2;
        boolean compact = this.height < 300;   // 854x480 windowed at scale 2
        int y0 = compact ? 40 : this.height / 4 - 6;
        int lx = cx - 150;   // left column
        int rx = cx + 2;     // right column

        nicknameField = addField(lx, y0, 148, config.user.nickname, 24, "gs.multiplayer.nickname");

        relayPortField = new TextFieldWidget(this.textRenderer, rx, y0, 70, 20,
                GsText.t("gs.multiplayer.relay_port"));
        relayPortField.setMaxLength(5);
        relayPortField.setTextPredicate(s -> s.matches("[0-9]*"));
        relayPortField.setText(String.valueOf(config.relayPort));
        addButton(relayPortField);

        lanPortField = new TextFieldWidget(this.textRenderer, rx + 78, y0, 70, 20,
                GsText.t("gs.multiplayer.lan_port"));
        lanPortField.setMaxLength(5);
        lanPortField.setTextPredicate(s -> s.matches("[0-9]*"));
        lanPortField.setText(String.valueOf(config.connection.lanPort));
        addButton(lanPortField);

        signalingField = addField(lx, y0 + 34, 148, config.signalingUrl, 128, "gs.multiplayer.signaling_url");
        relayHostField = addField(rx, y0 + 34, 148, config.relayHost, 100, "gs.multiplayer.relay_host");

        // Checkbox labels are drawn by the widget itself; 4 rows fit easily now.
        devModeBox = addButton(new CheckboxWidget(lx, y0 + 62, 300, 20,
                GsText.t("gs.multiplayer.dev_mode"), config.diagnostics.devMode));
        updatesBox = addButton(new CheckboxWidget(lx, y0 + 80, 300, 20,
                GsText.t("gs.multiplayer.update_check"), config.updates.enabled));
        titleButtonBox = addButton(new CheckboxWidget(lx, y0 + 98, 300, 20,
                GsText.t("gs.multiplayer.title_button"), config.ui.showTitleButton));
        upnpBox = addButton(new CheckboxWidget(lx, y0 + 116, 300, 20,
                GsText.t("gs.multiplayer.settings_upnp"), config.connection.autoPortMap));

        addButton(new ButtonWidget(lx, y0 + 142, 148, 20,
                GsText.t("gs.multiplayer.save"), b -> save()));
        addButton(new ButtonWidget(rx, y0 + 142, 148, 20,
                GsText.t("gs.multiplayer.firewall"), b -> {
            boolean ok = com.gsmultiplayer.util.FirewallFix.recreateRules();
            GuiUtil.toast(client, GsText.t("gs.multiplayer.title"),
                    GsText.t(ok ? "gs.multiplayer.firewall_done"
                            : "gs.multiplayer.firewall_unavailable"));
        }));
        // Esc closes the screen without saving - no extra Back button needed.
    }

    private TextFieldWidget addField(int x, int y, int width, String value, int maxLength, String key) {
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, width, 20,
                GsText.t(key));
        field.setMaxLength(maxLength);
        field.setText(value == null ? "" : value);
        addButton(field);
        return field;
    }

    private void save() {
        GsConfig config = ConfigManager.get();
        String previousUrl = config.signalingUrl;
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
        config.connection.autoPortMap = upnpBox.isChecked();
        ConfigManager.save();
        mod().applySettings();
        if (!previousUrl.equals(config.signalingUrl)) {
            mod().rooms().reconnectSignaling();
        }
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
        boolean compact = this.height < 300;
        int y0 = compact ? 40 : this.height / 4 - 6;
        int lx = cx - 150;
        int rx = cx + 2;
        label(matrices, lx, y0 - 10, "gs.multiplayer.nickname");
        label(matrices, rx, y0 - 10, "gs.multiplayer.relay_port");
        label(matrices, rx + 78, y0 - 10, "gs.multiplayer.lan_port");
        label(matrices, lx, y0 + 24, "gs.multiplayer.signaling_url");
        label(matrices, rx, y0 + 24, "gs.multiplayer.relay_host");
        drawLeft(matrices, GsText.t("gs.multiplayer.esc_hint").getString(),
                lx, y0 + 168, 0x606080);
    }

    private void label(MatrixStack matrices, int x, int y, String key) {
        drawLeft(matrices, GsText.t(key).getString(), x, y, 0x9090B0);
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
