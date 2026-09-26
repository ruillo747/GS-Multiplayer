package com.gsmultiplayer.mixin;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.gui.GsMainScreen;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.config.GsConfig;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "GS Multiplayer" button to the main menu. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void gsmultiplayer$addButton(CallbackInfo ci) {
        GsConfig config = ConfigManager.get();
        if (!config.ui.showTitleButton) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getSession() == null) {
            return;
        }
        GsLog.setMinecraftName(client.getSession().getUsername());
        TitleScreen self = (TitleScreen) (Object) this;
        int y = self.height / 4 + 48 + 24 * 4;
        ((ScreenAccessor) self).getButtons().add(new ButtonWidget(self.width / 2 - 100, y + 16, 200, 20,
                new TranslatableText("gs.multiplayer.title"),
                button -> client.openScreen(new GsMainScreen(null))));
        if (GsMultiplayerClient.get() != null) {
            GsMultiplayerClient.get().rooms().connectToSignaling();
        }
    }
}
