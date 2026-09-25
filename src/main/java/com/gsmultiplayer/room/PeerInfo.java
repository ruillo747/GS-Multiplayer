package com.gsmultiplayer.room;

import com.google.gson.JsonObject;

/** A session inside the room (the host, or a guest who just joined). */
public final class PeerInfo {

    public final int id;
    public final String name;
    public final String gsid;

    public PeerInfo(int id, String name, String gsid) {
        this.id = id;
        this.name = name;
        this.gsid = gsid;
    }

    public static PeerInfo fromJson(JsonObject obj) {
        return new PeerInfo(
                RoomEntry.intOf(obj, "id", -1),
                RoomEntry.string(obj, "name", "Player"),
                RoomEntry.string(obj, "gsid", ""));
    }
}
