package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GuiUtil;
import com.gsmultiplayer.client.GsText;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Direct connection by IP:port - the join side of "Open to network".
 * Also accepts a tunnel address like 127.0.0.1:25575 received by code.
 */
public final class JoinByIpScreen extends GsBaseScreen {

    private TextFieldWidget addressField;

    public JoinByIpScreen(Screen parent) {
        super(GsText.t("gs.multiplayer.join_ip_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height < 300 ? 52 : this.height / 4 + 16;

        addressField = new TextFieldWidget(this.textRenderer, cx - 100, y + 12, 200, 20,
                GsText.t("gs.multiplayer.join_ip_hint"));
        addressField.setMaxLength(255);
        addButton(addressField);

        addButton(new ButtonWidget(cx - 100, y + 38, 200, 20,
                GsText.t("gs.multiplayer.join_button"), b -> join()));
        addButton(new ButtonWidget(cx - 100, y + 62, 200, 20,
                GsText.t("gui.back"), b -> onClose()));
        setInitialFocus(addressField);
    }

    private void join() {
        String raw = addressField.getText().trim();
        if (raw.isEmpty()) {
            return;
        }
        String[] parts = GuiUtil.splitAddress(raw, 25565);
        remember(raw);
        GuiUtil.connectLocal(client, parent, parts[0], Integer.parseInt(parts[1]));
    }

    /** Keeps the address at the top of the quick-join history (max 5). */
    static void remember(String raw) {
        try {
            com.gsmultiplayer.config.GsConfig config = com.gsmultiplayer.config.ConfigManager.get();
            config.recentAddresses.remove(raw);
            config.recentAddresses.add(0, raw);
            while (config.recentAddresses.size() > 5) {
                config.recentAddresses.remove(config.recentAddresses.size() - 1);
            }
            com.gsmultiplayer.config.ConfigManager.save();
        } catch (Exception ignored) {
            // history is a convenience, never a blocker
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            join();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        addressField.tick();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height < 300 ? 52 : this.height / 4 + 16;
        drawLeft(matrices, GsText.t("gs.multiplayer.join_ip_hint").getString(),
                cx - 100, y + 2, 0x9090B0);
        drawCentered(matrices, GsText.t("gs.multiplayer.join_ip_note"), cx, y + 88, 0x606080);
    }
}
