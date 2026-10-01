package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.GsText;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.update.UpdateChecker;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

/** Main menu: quick join by address, host a world, settings, diagnostics. */
public final class GsMainScreen extends GsBaseScreen {

    private TextFieldWidget quickField;

    public GsMainScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        boolean compact = this.height < 320;   // 854x480 windowed at scale 2
        int settingsY;
        int quickY;
        int hostY;
        int doneY;
        if (compact) {
            settingsY = 48;              // Настройки / Диагностика
            quickY = settingsY + 44;     // поле адреса — ниже, без наложений
            hostY = quickY + 44;         // Открыть в сеть (только в мире)
            doneY = hostY + 22;          // Готово
        } else {
            settingsY = this.height / 4;
            quickY = settingsY + 48;
            hostY = quickY + 48;
            doneY = this.height - 28;
        }

        addButton(new ButtonWidget(cx - 100, settingsY, 200, 20,
                GsText.t("gs.multiplayer.settings"), b -> client.openScreen(new SettingsScreen(this))));
        addButton(new ButtonWidget(cx - 100, settingsY + 22, 200, 20,
                GsText.t("gs.multiplayer.diagnostics"), b -> client.openScreen(new DiagnosticsScreen(this))));

        // Quick join: paste the address a friend sent, press one button (or Enter).
        String recent = ConfigManager.get().recentAddresses.isEmpty()
                ? "" : ConfigManager.get().recentAddresses.get(0);
        quickField = new TextFieldWidget(this.textRenderer, cx - 100, quickY, 200, 20,
                GsText.t("gs.multiplayer.join_ip_hint"));
        quickField.setMaxLength(255);
        if (!recent.isEmpty()) {
            quickField.setText(recent);
        }
        addButton(quickField);
        addButton(new ButtonWidget(cx - 100, quickY + 22, 200, 20,
                GsText.t("gs.multiplayer.join_button"), b -> quickJoin()));
        setInitialFocus(quickField);

        // Host: only while a world is running.
        if (this.client != null && this.client.getServer() != null) {
            addButton(new ButtonWidget(cx - 100, hostY, 200, 20,
                    GsText.t("gs.multiplayer.open_net_short"),
                    b -> client.openScreen(new OpenToNetworkScreen(this))));
        }
        addButton(new ButtonWidget(cx - 100, doneY, 200, 20,
                GsText.t("gui.done"), b -> onClose()));
    }

    private void quickJoin() {
        String raw = quickField.getText().trim();
        if (raw.isEmpty()) {
            return;
        }
        String[] parts = com.gsmultiplayer.client.GuiUtil.splitAddress(raw, 25565);
        JoinByIpScreen.remember(raw);
        client.openScreen(new net.minecraft.client.gui.screen.ConnectScreen(this, client,
                parts[0], Integer.parseInt(parts[1])));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (quickField != null && quickField.isFocused()
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            quickJoin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected int titleY() {
        return this.height < 320 ? 4 : 14;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        GsMultiplayerClient mod = mod();
        if (mod == null) {
            return;
        }
        UpdateChecker.Result result = mod.updates().getResult();
        if (result.status == UpdateChecker.Status.AVAILABLE) {
            drawCentered(matrices, GsText.t("gs.multiplayer.update_available", result.latestVersion),
                    this.width / 2, this.height - 14, 0xFFFF55);
        }
    }

    @Override
    public void tick() {
        if (quickField != null) {
            quickField.tick();
        }
    }
}
