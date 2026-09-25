package com.gsmultiplayer.config;

import com.gsmultiplayer.security.Codes;
import com.gsmultiplayer.util.GsLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Loads and saves config/gsmultiplayer.json. Also owns the local data directory
 * config/gsmultiplayer/ (friends list and other per-user data).
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path configFile;
    private static Path dataDir;
    private static GsConfig config;

    private ConfigManager() {
    }

    public static synchronized void init(Path gameDir) {
        configFile = gameDir.resolve("config").resolve("gsmultiplayer.json");
        dataDir = gameDir.resolve("config").resolve("gsmultiplayer");
        config = load();
        boolean dirty = fillDefaults(config);
        if (config.user.gsId == null || config.user.gsId.isEmpty()) {
            config.user.gsId = Codes.generateGsId();
            GsLog.info("Generated new GS ID: " + config.user.gsId);
            dirty = true;
        }
        if (dirty) {
            save();
        }
        GsLog.info("Config loaded");
    }

    public static synchronized GsConfig get() {
        if (config == null) {
            config = new GsConfig();
            fillDefaults(config);
        }
        return config;
    }

    public static synchronized Path dataDir() {
        if (dataDir == null) {
            try {
                dataDir = Files.createTempDirectory("gsmultiplayer");
            } catch (IOException e) {
                throw new IllegalStateException("No data directory", e);
            }
        }
        return dataDir;
    }

    public static synchronized void save() {
        if (configFile == null) {
            return;
        }
        try {
            Files.createDirectories(configFile.getParent());
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.write(tmp, GSON.toJson(config).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            GsLog.error("Failed to save config: " + e.getMessage());
        }
    }

    private static GsConfig load() {
        try {
            if (configFile != null && Files.exists(configFile)) {
                String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                GsConfig loaded = GSON.fromJson(json, GsConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            }
        } catch (Exception e) {
            GsLog.warn("Config is corrupted, using defaults: " + e.getMessage());
        }
        return new GsConfig();
    }

    /** Restores nulls/invalid values to defaults. Returns true when the file should be rewritten. */
    private static boolean fillDefaults(GsConfig c) {
        boolean changed = false;
        if (c.connection == null) {
            c.connection = new GsConfig.Connection();
            changed = true;
        }
        if (c.user == null) {
            c.user = new GsConfig.User();
            changed = true;
        }
        if (c.ui == null) {
            c.ui = new GsConfig.Ui();
            changed = true;
        }
        if (c.updates == null) {
            c.updates = new GsConfig.Updates();
            changed = true;
        }
        if (c.diagnostics == null) {
            c.diagnostics = new GsConfig.Diagnostics();
            changed = true;
        }
        changed |= clamp(v -> c.connection.punchTimeoutMs = v, c.connection.punchTimeoutMs, 6000, 1000, 30000);
        changed |= clamp(v -> c.connection.signalingTimeoutMs = v, c.connection.signalingTimeoutMs, 10000, 1000, 60000);
        changed |= clamp(v -> c.connection.pingIntervalMs = v, c.connection.pingIntervalMs, 4000, 1000, 30000);
        changed |= clamp(v -> c.connection.lanPort = v, c.connection.lanPort, 25575, 1024, 65535);
        changed |= clamp(v -> c.diagnostics.logLines = v, c.diagnostics.logLines, 500, 100, 5000);
        if (c.signalingUrl == null) {
            c.signalingUrl = "ws://localhost:35500";
            changed = true;
        }
        return changed;
    }

    private interface IntSetter {
        void set(int value);
    }

    private static boolean clamp(IntSetter setter, int current, int def, int min, int max) {
        if (current < min || current > max) {
            setter.set(def);
            return true;
        }
        return false;
    }
}
