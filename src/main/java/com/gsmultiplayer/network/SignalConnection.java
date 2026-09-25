package com.gsmultiplayer.network;

import com.gsmultiplayer.network.websocket.WsClient;
import com.gsmultiplayer.util.GsLog;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Signaling transport: JSON messages over a WebSocket connection.
 * Incoming messages are dispatched by their "t" field to registered handlers.
 */
public final class SignalConnection {

    public interface Listener {
        void onSignalingConnected();

        void onSignalingDisconnected(String reason);
    }

    private static final Gson GSON = new Gson();

    private final Map<String, Consumer<JsonObject>> handlers = new ConcurrentHashMap<>();
    private final JsonParser jsonParser = new JsonParser();

    private volatile WsClient ws;
    private volatile Listener listener;
    private volatile boolean userRequestedClose;
    private String url;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void on(String type, Consumer<JsonObject> handler) {
        handlers.put(type, handler);
    }

    public boolean isConnected() {
        WsClient client = ws;
        return client != null && client.isOpen();
    }

    public String getUrl() {
        return url;
    }

    /** Connects and blocks until the handshake finishes. Call from a worker thread. */
    public void connect(String signalingUrl, int timeoutMs) throws IOException {
        this.url = signalingUrl;
        this.userRequestedClose = false;
        URI uri = URI.create(signalingUrl);
        WsClient client = new WsClient(uri, new WsClient.Listener() {
            @Override
            public void onText(String text) {
                dispatch(text);
            }

            @Override
            public void onClosed(int code, String reason, boolean remote) {
                GsLog.info("Signaling disconnected (" + code + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
                Listener l = listener;
                if (l != null && !userRequestedClose) {
                    l.onSignalingDisconnected(reason);
                }
            }
        });
        this.ws = client;
        long deadline = System.currentTimeMillis() + timeoutMs;
        // connect() itself has a socket connect timeout; the deadline guards the TLS handshake too
        Thread connector = new Thread(() -> {
            try {
                client.connect();
            } catch (IOException e) {
                synchronized (this) {
                    notifyAll();
                }
            }
        }, "gs-ws-connect");
        synchronized (this) {
            connector.start();
            while (connector.isAlive() && !client.isOpen()) {
                if (System.currentTimeMillis() > deadline) {
                    client.close();
                    throw new IOException("Signaling connect timeout");
                }
                try {
                    wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    client.close();
                    throw new IOException("Interrupted");
                }
            }
        }
        if (!client.isOpen()) {
            throw new IOException("Cannot connect to signaling server");
        }
        GsLog.info("Signaling connected: " + signalingUrl);
        Listener l = listener;
        if (l != null) {
            l.onSignalingConnected();
        }
    }

    public void send(JsonObject message) throws IOException {
        WsClient client = ws;
        if (client == null || !client.isOpen()) {
            throw new IOException("Signaling is not connected");
        }
        client.sendText(GSON.toJson(message));
    }

    public void close() {
        userRequestedClose = true;
        WsClient client = ws;
        if (client != null) {
            client.close();
        }
    }

    private void dispatch(String text) {
        JsonObject message;
        try {
            message = jsonParser.parse(text).getAsJsonObject();
        } catch (Exception e) {
            GsLog.warn("Received malformed signaling message: " + text);
            return;
        }
        String type = message.has("t") && message.get("t").isJsonPrimitive()
                ? message.get("t").getAsString() : null;
        if (type == null) {
            return;
        }
        Consumer<JsonObject> handler = handlers.get(type);
        if (handler != null) {
            try {
                handler.accept(message);
            } catch (Exception e) {
                GsLog.error("Handler " + type + " failed: " + e);
            }
        } else {
            GsLog.debug("Unhandled signaling message: " + type);
        }
    }
}
