package com.gsmultiplayer.client.gui;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/** Common chrome for all GS Multiplayer screens: background, title, back navigation. */
public abstract class GsBaseScreen extends Screen {

    protected final Screen parent;

    protected GsBaseScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        if (this.client != null) {
            com.gsmultiplayer.client.GsText.sync(this.client);
        }
    }

    @Override
    public void onClose() {
        if (this.client != null) {
            this.client.openScreen(parent);
        }
    }

    protected void drawCentered(MatrixStack matrices, Text text, int centerX, int y, int color) {
        this.textRenderer.drawWithShadow(matrices, text,
                centerX - this.textRenderer.getWidth(text) / 2.0F, y, color);
    }

    protected void drawCentered(MatrixStack matrices, String text, int centerX, int y, int color) {
        this.textRenderer.drawWithShadow(matrices, text,
                centerX - this.textRenderer.getWidth(text) / 2.0F, y, color);
    }

    protected void drawLeft(MatrixStack matrices, String text, int x, int y, int color) {
        this.textRenderer.drawWithShadow(matrices, text, x, y, color);
    }

    /** Vertical position of the screen title; compact screens may override. */
    protected int titleY() {
        return 14;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCentered(matrices, this.title, this.width / 2, titleY(), 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    protected GsMultiplayerClient mod() {
        return GsMultiplayerClient.get();
    }

    protected static void quiet(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            GsLog.error("UI action failed: " + e);
        }
    }
}
