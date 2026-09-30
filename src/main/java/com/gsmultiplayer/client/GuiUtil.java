package com.gsmultiplayer.client;

import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import com.gsmultiplayer.client.GsText;

/** Minecraft-side helpers shared by all GS Multiplayer screens. */
public final class GuiUtil {

    private GuiUtil() {
    }

    public static void chat(MinecraftClient client, String key, Object... args) {
        if (client.player != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(new LiteralText("§7[GS]§r ")
                    .append(GsText.t(key, args)));
        }
    }

    public static void chatOrToast(MinecraftClient client, String key, Object... args) {
        if (client.player != null) {
            chat(client, key, args);
        } else {
            toast(client, GsText.t("gs.multiplayer.title"), GsText.t(key, args));
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

    /**
     * Splits a pasted address into host and port. Accepts "host", "host:port",
     * and "[ipv6]:port". Unknown ports fall back to {@code fallbackPort}.
     */
    public static String[] splitAddress(String raw, int fallbackPort) {
        String host = raw.trim();
        int port = fallbackPort;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) {
                port = parsePortTail(host.substring(close + 1), port);
                host = host.substring(1, close);
            }
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0) {
                port = parsePortTail(host.substring(colon + 1), port);
                host = host.substring(0, colon);
            }
        }
        return new String[]{host, String.valueOf(port)};
    }

    private static int parsePortTail(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value <= 65535 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
