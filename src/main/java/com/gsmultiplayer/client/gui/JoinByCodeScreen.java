package com.gsmultiplayer.client.gui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.TranslatableText;

/** Join by room code typed manually. */
public final class JoinByCodeScreen extends GsBaseScreen {

    private TextFieldWidget codeField;

    public JoinByCodeScreen(Screen parent) {
        super(new TranslatableText("gs.multiplayer.join_by_code_title"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 24;
        codeField = new TextFieldWidget(this.textRenderer, cx - 100, y, 200, 20,
                new TranslatableText("gs.multiplayer.code_hint"));
        codeField.setMaxLength(10);
        codeField.setChangedListener(s -> {
            String upper = s.toUpperCase(java.util.Locale.ROOT);
            if (!upper.equals(s)) {
                codeField.setText(upper);
            }
        });
        addButton(codeField);
        addButton(new ButtonWidget(cx - 100, y + 28, 200, 20,
                new TranslatableText("gs.multiplayer.join_button"), b -> join()));
        addButton(new ButtonWidget(cx - 100, y + 52, 200, 20,
                new TranslatableText("gui.back"), b -> onClose()));
        setInitialFocus(codeField);
    }

    private void join() {
        String code = codeField.getText().trim();
        if (code.isEmpty()) {
            return;
        }
        mod().rooms().joinRoom(code);
        client.openScreen(new ConnectingScreen(this));
    }

    @Override
    public void tick() {
        codeField.tick();
    }
}
