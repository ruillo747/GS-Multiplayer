package com.gsmultiplayer.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Typed builders for the signaling JSON protocol (see docs/SERVER_API.md).
 * All methods may throw IOException when the connection is down.
 */
public final class SignalApi {

    private final SignalConnection connection;

    public SignalApi(SignalConnection connection) {
        this.connection = connection;
    }

    private void send(String type) throws IOException {
        send(type, null);
    }

    private void send(String type, JsonObject extra) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("t", type);
        if (extra != null) {
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
                message.add(e.getKey(), e.getValue());
            }
        }
        connection.send(message);
    }

    public void hello(String nickname, String gsId) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", nickname);
        extra.addProperty("gsid", gsId);
        send("hello", extra);
    }

    public void createRoom(String name, int maxPlayers) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("name", name);
        extra.addProperty("maxPlayers", maxPlayers);
        send("room.create", extra);
    }

    public void joinRoom(String code) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("code", code);
        send("room.join", extra);
    }

    public void leaveRoom() throws IOException {
        send("room.leave");
    }

    public void listRooms(String query) throws IOException {
        JsonObject extra = new JsonObject();
        if (query != null && !query.isEmpty()) {
            extra.addProperty("query", query);
        }
        send("room.list", extra);
    }

    /** Peer-to-peer signaling payload (candidates and similar), forwarded by the server. */
    public void signalTo(int peerId, JsonObject data) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("to", peerId);
        extra.add("data", data);
        send("signal", extra);
    }

    public void invite(String friendGsId, String roomCode) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("to", friendGsId);
        extra.addProperty("roomCode", roomCode);
        send("invite", extra);
    }

    public void inviteResponse(int toSessionId, boolean accepted) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("to", toSessionId);
        extra.addProperty("accepted", accepted);
        send("invite.response", extra);
    }

    public void presenceWatch(List<String> gsids) throws IOException {
        JsonObject extra = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String g : gsids) {
            arr.add(g);
        }
        extra.add("gsids", arr);
        send("presence.watch", extra);
    }

    public void heartbeat() throws IOException {
        send("heartbeat");
    }

    public void requestRelay() throws IOException {
        send("relay.request");
    }

    public void ping(long clientTime) throws IOException {
        JsonObject extra = new JsonObject();
        extra.addProperty("t", clientTime);
        send("ping", extra);
    }
}
