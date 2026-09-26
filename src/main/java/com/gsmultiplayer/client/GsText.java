package com.gsmultiplayer.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gsmultiplayer.util.GsLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.LanguageDefinition;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Self-contained translation loader.
 *
 * The mod deliberately has no Fabric API dependency, and without it the game
 * never sees mods' assets/ directories - so vanilla translation storage cannot
 * serve our lang files. Instead we read assets/gsmultiplayer/lang/<code>.json
 * straight from our own jar. The language follows the Minecraft setting
 * (ru -> ru_ru.json, everything else -> en_us.json), re-checked whenever a
 * screen opens via {@link #sync}.
 */
public final class GsText {

    private static final Gson GSON = new Gson();
    private static final String PREFIX = "/assets/gsmultiplayer/lang/";

    /** English strings double as the key->text fallback. */
    private static volatile Map<String, String> fallback = Collections.emptyMap();
    private static volatile Map<String, String> current = Collections.emptyMap();
    private static volatile String loadedCode = "";

    private GsText() {
    }

    /** Reloads translations when the Minecraft language changed. */
    public static void sync(MinecraftClient client) {
        if (fallback.isEmpty() && !load("en_us", false)) {
            return;
        }
        LanguageDefinition definition = client.getLanguageManager().getLanguage();
        String code = definition != null ? definition.getCode() : "en_us";
        if (code.equals(loadedCode)) {
            return;
        }
        if ("en_us".equals(code) || !load(code, false)) {
            current = fallback;
            loadedCode = code; // avoids retrying a missing language on every sync
        }
    }

    /** Translated text with %s argument substitution. Never returns null. */
    public static Text t(String key, Object... args) {
        return new LiteralText(format(key, args));
    }

    public static String format(String key, Object... args) {
        Map<String, String> map = current;
        String template = map.get(key);
        if (template == null) {
            template = fallback.get(key);
        }
        if (template == null) {
            return key;
        }
        if (args.length == 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int argIndex = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < template.length() && template.charAt(i + 1) == 's' && argIndex < args.length) {
                sb.append(args[argIndex++]);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean load(String code, boolean required) {
        try (InputStream in = GsText.class.getResourceAsStream(PREFIX + code + ".json")) {
            if (in == null) {
                if (required) {
                    GsLog.warn("Missing translation file for " + code);
                }
                return false;
            }
            Map<String, String> loaded = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
            Map<String, String> sanitized = new HashMap<>();
            for (Map.Entry<String, String> e : loaded.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sanitized.put(e.getKey(), e.getValue());
                }
            }
            if ("en_us".equals(code)) {
                fallback = Collections.unmodifiableMap(sanitized);
            }
            current = Collections.unmodifiableMap(sanitized);
            loadedCode = code;
            GsLog.info("Translations loaded: " + code + " (" + sanitized.size() + " strings)");
            return true;
        } catch (Exception e) {
            GsLog.warn("Failed to read translations for " + code + ": " + e);
            return false;
        }
    }
}
