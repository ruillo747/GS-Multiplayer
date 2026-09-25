package com.gsmultiplayer.friends;

import com.gsmultiplayer.security.Codes;
import com.gsmultiplayer.util.GsLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local friends list; there is no central account database by design. */
public final class FriendsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_FRIENDS = 64;

    private final List<Friend> friends = new ArrayList<>();
    private final Path file;

    public FriendsManager(Path dataDir) {
        this.file = dataDir.resolve("friends.json");
        load();
    }

    public synchronized List<Friend> list() {
        return Collections.unmodifiableList(new ArrayList<>(friends));
    }

    public synchronized List<String> gsids() {
        List<String> out = new ArrayList<>(friends.size());
        for (Friend f : friends) {
            out.add(f.gsid);
        }
        return out;
    }

    /** Adds or renames a friend. Returns false when the GS ID is malformed or the list is full. */
    public synchronized boolean addOrUpdate(String rawGsid, String name) {
        String gsid = Codes.normalizeRoomCode(rawGsid);
        if (!Codes.isValidGsId(gsid)) {
            return false;
        }
        String cleanName = Codes.sanitizeText(name, 24);
        for (Friend f : friends) {
            if (f.gsid.equals(gsid)) {
                if (!cleanName.isEmpty()) {
                    f.name = cleanName;
                }
                save();
                return true;
            }
        }
        if (friends.size() >= MAX_FRIENDS) {
            return false;
        }
        friends.add(new Friend(gsid, cleanName.isEmpty() ? gsid : cleanName));
        save();
        return true;
    }

    public synchronized boolean remove(String gsid) {
        boolean removed = friends.removeIf(f -> f.gsid.equals(gsid));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Friend find(String gsid) {
        for (Friend f : friends) {
            if (f.gsid.equals(gsid)) {
                return f;
            }
        }
        return null;
    }

    public synchronized void updatePresence(String gsid, String status, String roomCode) {
        Friend f = find(gsid);
        if (f == null) {
            return;
        }
        f.status = status == null ? "offline" : status;
        f.roomCode = roomCode;
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                List<Friend> loaded = GSON.fromJson(json, new TypeToken<List<Friend>>() { }.getType());
                if (loaded != null) {
                    friends.clear();
                    for (Friend f : loaded) {
                        if (f != null && Codes.isValidGsId(f.gsid)) {
                            f.status = "offline";
                            friends.add(f);
                        }
                    }
                }
            }
        } catch (Exception e) {
            GsLog.warn("Failed to read friends.json: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, GSON.toJson(friends).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            GsLog.error("Failed to save friends.json: " + e.getMessage());
        }
    }
}
