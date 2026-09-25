package com.gsmultiplayer.p2p;

import com.gsmultiplayer.util.GsLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/**
 * A single UDP socket with a background reader thread.
 * Packets are dispatched to the current handler (STUN, punch, link or relay phases).
 */
public final class UdpEndpoint {

    public interface PacketHandler {
        void onPacket(byte[] data, InetSocketAddress from);
    }

    private static final int READ_BUFFER = 2048;

    private final DatagramSocket socket;
    private final Thread reader;
    private volatile PacketHandler handler;
    private volatile boolean closed;

    public UdpEndpoint(DatagramSocket preBound) {
        this.socket = preBound;
        reader = new Thread(this::readLoop, "gs-udp-" + socket.getLocalPort());
        reader.setDaemon(true);
        reader.start();
    }

    public DatagramSocket socket() {
        return socket;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void setHandler(PacketHandler newHandler) {
        this.handler = newHandler;
    }

    public void sendTo(InetSocketAddress target, byte[] data) {
        if (closed || target == null) {
            return;
        }
        try {
            socket.send(new DatagramPacket(data, data.length, target));
            if (GsLog.verboseNetwork()) {
                GsLog.debug("UDP -> " + target + " (" + data.length + "B)");
            }
        } catch (IOException e) {
            // transient send errors are normal while punching; the reliability layer recovers
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        socket.close();
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER];
        try {
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
                PacketHandler h = handler;
                if (h != null) {
                    h.onPacket(data, (InetSocketAddress) packet.getSocketAddress());
                }
            }
        } catch (SocketException e) {
            // socket closed
        } catch (IOException e) {
            if (!closed) {
                GsLog.warn("UDP reader stopped: " + e);
            }
        }
    }
}
