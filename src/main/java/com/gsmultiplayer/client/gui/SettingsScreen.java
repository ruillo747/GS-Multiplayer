package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsText;
import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.config.GsConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

/** Settings: world port, router/firewall helpers, UI toggles, updates. */
public final class SettingsScreen extends GsBaseScreen {

    private TextFieldWidget lanPortField;
    private CheckboxWidget upnpBox;
    private CheckboxWidget unlicensedBox;
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
        boolean compact = this.height < 300;
        int y0 = compact ? 40 : this.height / 4 - 6;

        lanPortField = new TextFieldWidget(this.textRenderer, cx - 100, y0, 200, 20,
                GsText.t("gs.multiplayer.lan_port"));
        lanPortField.setMaxLength(5);
        lanPortField.setTextPredicate(s -> s.matches("[0-9]*"));
        lanPortField.setText(String.valueOf(config.connection.lanPort));
        addButton(lanPortField);

        unlicensedBox = addButton(new CheckboxWidget(cx - 100, y0 + 26, 200, 20,
                GsText.t("gs.multiplayer.allow_unlicensed"), config.connection.allowUnlicensed));
        upnpBox = addButton(new CheckboxWidget(cx - 100, y0 + 46, 200, 20,
                GsText.t("gs.multiplayer.settings_upnp"), config.connection.autoPortMap));
        updatesBox = addButton(new CheckboxWidget(cx - 100, y0 + 66, 200, 20,
                GsText.t("gs.multiplayer.update_check"), config.updates.enabled));
        titleButtonBox = addButton(new CheckboxWidget(cx - 100, y0 + 86, 200, 20,
                GsText.t("gs.multiplayer.title_button"), config.ui.showTitleButton));
        pauseButtonBox = addButton(new CheckboxWidget(cx - 100, y0 + 106, 200, 20,
                GsText.t("gs.multiplayer.pause_button"), config.ui.showPauseButton));
        devModeBox = addButton(new CheckboxWidget(cx - 100, y0 + 126, 200, 20,
                GsText.t("gs.multiplayer.dev_mode"), config.diagnostics.devMode));

        addButton(new ButtonWidget(cx - 100, y0 + 150, 98, 20,
                GsText.t("gs.multiplayer.save"), b -> save()));
        addButton(new ButtonWidget(cx + 2, y0 + 150, 98, 20,
                GsText.t("gs.multiplayer.firewall"), b -> {
            boolean ok = com.gsmultiplayer.util.FirewallFix.recreateRules();
            GuiUtil.toast(client, GsText.t("gs.multiplayer.title"),
                    GsText.t(ok ? "gs.multiplayer.firewall_done"
                            : "gs.multiplayer.firewall_unavailable"));
        }));
    }

    private void save() {
        GsConfig config = ConfigManager.get();
        config.connection.lanPort = parsePort(lanPortField.getText(), config.connection.lanPort);
        config.connection.allowUnlicensed = unlicensedBox.isChecked();
        config.connection.autoPortMap = upnpBox.isChecked();
        config.updates.enabled = updatesBox.isChecked();
        config.ui.showTitleButton = titleButtonBox.isChecked();
        config.ui.showPauseButton = pauseButtonBox.isChecked();
        config.diagnostics.devMode = devModeBox.isChecked();
        ConfigManager.save();
        mod().applySettings();
        onClose();
    }

    private static int parsePort(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value >= 1024 && value <= 65535) {
                return value;
            }
        } catch (NumberFormatException e) {
            // keep fallback
        }
        return fallback;
    }

    @Override
    public void tick() {
        lanPortField.tick();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        boolean compact = this.height < 300;
        int y0 = compact ? 40 : this.height / 4 - 6;
        drawLeft(matrices, GsText.t("gs.multiplayer.lan_port").getString(), cx - 100, y0 - 10, 0x9090B0);
        drawLeft(matrices, GsText.t("gs.multiplayer.esc_hint").getString(), cx - 100, y0 + 172, 0x606080);
    }
}
