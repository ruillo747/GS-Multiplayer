package com.gsmultiplayer.friends;

/** A friend entry, stored locally in config/gsmultiplayer/friends.json. */
public class Friend {

    public String gsid = "";
    public String name = "";

    public Friend() {
    }

    public Friend(String gsid, String name) {
        this.gsid = gsid;
        this.name = name;
    }

    /** Ephemeral presence state delivered by the signaling server; never persisted. */
    public transient String status = "offline";
    public transient String roomCode = null;

    public boolean online() {
        return !"offline".equals(status);
    }

    public boolean inRoom() {
        return "in-room".equals(status) && roomCode != null && !roomCode.isEmpty();
    }
}
