package com.gsmultiplayer.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gsmultiplayer.util.GsLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Registers a peer with the relay server and provides the wire-format constants.
 * After registration the tunnel simply prefixes every reliable-link packet with
 * the destination peer id; the relay forwards datagrams verbatim.
 */
public final class RelayAccess {

    public static final byte MAGIC_REG = 0x52;
    public static final byte MAGIC_ACK = 0x53;
    public static final byte MAGIC_KA = 0x4B;
    public static final byte MAGIC_DATA = 0x44;

    /** Relay endpoint details issued by the signaling server. */
    public static final class RelayInfo {
        public final String host;
        public final int port;
        public final String token;
        public final int peerId;
        public final String roomCode;

        public RelayInfo(String host, int port, String token, int peerId, String roomCode) {
            this.host = host;
            this.port = port;
            this.token = token;
            this.peerId = peerId;
            this.roomCode = roomCode;
        }
    }

    private static final JsonParser PARSER = new JsonParser();
    private static final int ATTEMPTS = 6;
    private static final int ATTEMPT_TIMEOUT_MS = 700;

    private RelayAccess() {
    }

    /** Blocking registration; must run before the endpoint reader starts consuming packets. */
    public static void register(DatagramSocket socket, InetSocketAddress relayAddress, String token) throws IOException {
        JsonObject reg = new JsonObject();
        reg.addProperty("token", token);
        byte[] body = reg.toString().getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[1 + body.length];
        packet[0] = MAGIC_REG;
        System.arraycopy(body, 0, packet, 1, body.length);

        socket.setSoTimeout(ATTEMPT_TIMEOUT_MS);
        try {
            IOException last = new IOException("relay did not answer");
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                socket.send(new DatagramPacket(packet, packet.length, relayAddress));
                byte[] buf = new byte[1024];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    last = new IOException("relay timeout");
                    continue;
                }
                if (response.getLength() < 1 || response.getData()[0] != MAGIC_ACK) {
                    continue;
                }
                JsonObject json;
                try {
                    json = PARSER.parse(new String(response.getData(), 1, response.getLength() - 1,
                            StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception e) {
                    continue;
                }
                String t = json.has("t") && json.get("t").isJsonPrimitive() ? json.get("t").getAsString() : "";
                if ("ok".equals(t)) {
                    GsLog.info("Relay registration ok (" + json.get("peersInRoom").getAsInt() + " peers in room)");
                    return;
                }
                String code = json.has("code") ? json.get("code").getAsString() : "UNKNOWN";
                throw new IOException("relay rejected registration: " + code);
            }
            throw last;
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /** Builds a data datagram: MAGIC_DATA | u32 destId | link packet. */
    public static byte[] wrapData(int destPeerId, byte[] linkPacket) {
        byte[] packet = new byte[5 + linkPacket.length];
        packet[0] = MAGIC_DATA;
        packet[1] = (byte) ((destPeerId >> 24) & 0xFF);
        packet[2] = (byte) ((destPeerId >> 16) & 0xFF);
        packet[3] = (byte) ((destPeerId >> 8) & 0xFF);
        packet[4] = (byte) (destPeerId & 0xFF);
        System.arraycopy(linkPacket, 0, packet, 5, linkPacket.length);
        return packet;
    }

    public static byte[] keepalive() {
        return new byte[] { MAGIC_KA };
    }
}
