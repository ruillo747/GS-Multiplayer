package com.gsmultiplayer.p2p;

import com.gsmultiplayer.util.GsLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

/**
 * Minimal RFC 5389 STUN binding client: asks a public STUN server
 * "what is my external ip:port for this socket?".
 */
public final class StunResolver {

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final byte[] SERVERS_HOST = null; // marker; list below

    public static final String[][] DEFAULT_SERVERS = {
            {"stun.l.google.com", "19302"},
            {"stun1.l.google.com", "19302"},
            {"stun.cloudflare.com", "3478"}
    };

    private StunResolver() {
    }

    /**
     * Queries the servers in order; returns the XOR-MAPPED-ADDRESS of the socket
     * or null when every server fails (offline, blocked UDP, symmetric-failure is irrelevant here).
     */
    public static InetSocketAddress queryAny(DatagramSocket socket, int timeoutPerServerMs) {
        for (String[] server : DEFAULT_SERVERS) {
            try {
                InetSocketAddress result = query(server[0], Integer.parseInt(server[1]), socket, timeoutPerServerMs);
                if (result != null) {
                    GsLog.info("STUN: external address " + Candidate.encode(result) + " via " + server[0]);
                    return result;
                }
            } catch (Exception e) {
                GsLog.debug("STUN " + server[0] + " failed: " + e.getMessage());
            }
        }
        GsLog.info("STUN: no external address available");
        return null;
    }

    public static InetSocketAddress query(String host, int port, DatagramSocket socket, int timeoutMs) throws IOException {
        InetAddress server = InetAddress.getByName(host);
        byte[] txid = new byte[12];
        new SecureRandom().nextBytes(txid);

        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01; // binding request
        request[2] = 0x00;
        request[3] = 0x00; // message length
        request[4] = (byte) ((MAGIC_COOKIE >> 24) & 0xFF);
        request[5] = (byte) ((MAGIC_COOKIE >> 16) & 0xFF);
        request[6] = (byte) ((MAGIC_COOKIE >> 8) & 0xFF);
        request[7] = (byte) (MAGIC_COOKIE & 0xFF);
        System.arraycopy(txid, 0, request, 8, 12);

        socket.setSoTimeout(timeoutMs);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(request, request.length, server, port));
                byte[] buf = new byte[512];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                InetSocketAddress mapped = parseBindingResponse(response.getData(), response.getLength(), txid);
                if (mapped != null) {
                    return mapped;
                }
            }
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    private static InetSocketAddress parseBindingResponse(byte[] data, int length, byte[] txid) {
        if (length < 20 || data[0] != 0x00 || data[1] != 0x01) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            if (data[8 + i] != txid[i]) {
                return null;
            }
        }
        int messageLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int pos = 20;
        int end = Math.min(length, 20 + messageLength);
        while (pos + 4 <= end) {
            int attrType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int attrLength = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (pos + 4 + attrLength > end) {
                break;
            }
            if (attrType == 0x0020 && attrLength >= 8) { // XOR-MAPPED-ADDRESS
                int family = data[pos + 5] & 0xFF;
                int xPort = ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
                int port = xPort ^ (MAGIC_COOKIE >>> 16);
                if (family == 0x01 && attrLength >= 8) {
                    byte[] addr = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        addr[i] = (byte) (data[pos + 8 + i] ^ ((MAGIC_COOKIE >> (24 - 8 * i)) & 0xFF));
                    }
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(addr), port);
                    } catch (Exception e) {
                        return null;
                    }
                }
            } else if (attrType == 0x0001 && attrLength >= 8) { // MAPPED-ADDRESS fallback
                int family = data[pos + 5] & 0xFF;
                int port = ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
                if (family == 0x01) {
                    byte[] addr = new byte[4];
                    System.arraycopy(data, pos + 8, addr, 0, 4);
                    try {
                        return new InetSocketAddress(InetAddress.getByAddress(addr), port);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
            pos += 4 + ((attrLength + 3) & ~3);
        }
        return null;
    }
}
