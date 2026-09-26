package com.gsmultiplayer.mixin;

import com.gsmultiplayer.client.GsMultiplayerClient;
import com.gsmultiplayer.client.gui.HostActiveScreen;
import com.gsmultiplayer.client.gui.HostSetupScreen;
import com.gsmultiplayer.config.ConfigManager;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.gsmultiplayer.client.GsText;

/** Adds the GS Multiplayer button to the pause (game menu) screen while in a singleplayer world. */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void gsmultiplayer$addButton(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getServer() == null) {
            return; // multiplayer worlds are not hosted by this mod
        }
        if (!ConfigManager.get().ui.showPauseButton) {
            return;
        }
        if (client.getSession() != null) {
            GsLog.setMinecraftName(client.getSession().getUsername());
        }
        com.gsmultiplayer.client.GsText.sync(client);
        GameMenuScreen self = (GameMenuScreen) (Object) this;
        ((ScreenAddButtonInvoker) self).gsmultiplayer$addButton(new ButtonWidget(
                self.width / 2 - 100, self.height / 4 + 120 + 8, 200, 20,
                GsText.t("gs.multiplayer.title"),
                button -> {
                    GsMultiplayerClient mod = GsMultiplayerClient.get();
                    if (mod != null && mod.rooms().isHosting()) {
                        client.openScreen(new HostActiveScreen(self));
                    } else {
                        client.openScreen(new HostSetupScreen(self));
                    }
                }));
    }
}
