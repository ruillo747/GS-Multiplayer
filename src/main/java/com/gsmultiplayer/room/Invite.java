package com.gsmultiplayer.room;

import com.google.gson.JsonObject;

/** An invitation delivered through the signaling server. */
public final class Invite {

    public final int fromSessionId;
    public final String fromName;
    public final String fromGsId;
    public final String roomCode;
    public final String roomName;
    public final long receivedAt = System.currentTimeMillis();

    public Invite(int fromSessionId, String fromName, String fromGsId, String roomCode, String roomName) {
        this.fromSessionId = fromSessionId;
        this.fromName = fromName;
        this.fromGsId = fromGsId;
        this.roomCode = roomCode;
        this.roomName = roomName;
    }

    public static Invite fromJson(JsonObject obj) {
        JsonObject from = obj.has("from") && obj.get("from").isJsonObject()
                ? obj.getAsJsonObject("from") : new JsonObject();
        return new Invite(
                from.get("sessionId") != null && from.get("sessionId").isJsonPrimitive() ? from.get("sessionId").getAsInt() : -1,
                RoomEntry.string(from, "name", "Player"),
                RoomEntry.string(from, "gsid", ""),
                RoomEntry.string(obj, "roomCode", ""),
                RoomEntry.string(obj, "roomName", ""));
    }
}
