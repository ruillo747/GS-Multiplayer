package com.gsmultiplayer.client;

import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.LiteralText;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** Minecraft-side helpers shared by all GS Multiplayer screens. */
public final class GuiUtil {

    private GuiUtil() {
    }

    public static void chat(MinecraftClient client, String key, Object... args) {
        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(new LiteralText("§7[GS]§r ")
                    .append(new TranslatableText(key, args)));
        }
    }

    public static void chatOrToast(MinecraftClient client, String key, Object... args) {
        if (client.player != null) {
            chat(client, key, args);
        } else {
            toast(client, new TranslatableText("gs.multiplayer.title"), new TranslatableText(key, args));
        }
    }

    public static void toast(MinecraftClient client, Text title, Text body) {
        client.getToastManager().add(SystemToast.create(client, SystemToast.Type.TUTORIAL_HINT, title, body));
    }

    public static void copy(MinecraftClient client, String text) {
        client.keyboard.setClipboard(text);
    }

    public static void openUrl(String url) {
        try {
            Util.getOperatingSystem().open(url);
        } catch (Exception e) {
            GsLog.error("Cannot open URL " + url + ": " + e.getMessage());
        }
    }

    /** Opens the vanilla direct-connect flow against the local tunnel port. */
    public static void connectLocal(MinecraftClient client, net.minecraft.client.gui.screen.Screen parent,
                                   String host, int port) {
        client.openScreen(new ConnectScreen(parent, client, host, port));
    }
}
