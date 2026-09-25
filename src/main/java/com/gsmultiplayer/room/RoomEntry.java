package com.gsmultiplayer.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Room state as advertised by the signaling server. */
public final class RoomEntry {

    public final String code;
    public final String name;
    public final int players;
    public final int maxPlayers;
    public final String hostName;
    public final int hostId;

    public RoomEntry(String code, String name, int players, int maxPlayers, String hostName, int hostId) {
        this.code = code;
        this.name = name;
        this.players = players;
        this.maxPlayers = maxPlayers;
        this.hostName = hostName;
        this.hostId = hostId;
    }

    public static RoomEntry fromJson(JsonObject obj) {
        return new RoomEntry(
                string(obj, "code", "??????"),
                string(obj, "name", "Minecraft World"),
                intOf(obj, "players", 1),
                intOf(obj, "maxPlayers", 4),
                string(obj, "hostName", "Player"),
                intOf(obj, "hostId", -1));
    }

    public static List<RoomEntry> listFrom(JsonArray array) {
        List<RoomEntry> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (e != null && e.isJsonObject()) {
                out.add(fromJson(e.getAsJsonObject()));
            }
        }
        return out;
    }

    static String string(JsonObject obj, String key, String def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : def;
    }

    static int intOf(JsonObject obj, String key, int def) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsInt() : def;
    }
}
