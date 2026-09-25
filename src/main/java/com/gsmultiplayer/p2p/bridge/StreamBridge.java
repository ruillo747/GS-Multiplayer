package com.gsmultiplayer.p2p.bridge;

import com.gsmultiplayer.p2p.reliable.ReliableLink;
import com.gsmultiplayer.util.GsLog;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges a Minecraft TCP connection through the reliable link.
 *
 * Frame layout: u8 streamId | u8 op | u16 len | payload
 *   op 0 = OPEN (guest -> host: "I want a TCP connection to your world")
 *   op 1 = DATA, op 2 = EOF (clean close), op 3 = RST (abort)
 *
 * Host mode: OPEN makes the bridge connect to the local integrated server.
 * Guest mode: the bridge listens on 127.0.0.1 and tunnels each accepted socket.
 */
public final class StreamBridge implements ReliableLink.Listener {

    public static final byte OP_OPEN = 0;
    public static final byte OP_DATA = 1;
    public static final byte OP_EOF = 2;
    public static final byte OP_RST = 3;

    private static final int CHUNK_SIZE = 1000;

    public interface HostSocketFactory {
        Socket connect() throws IOException;
    }

    private static final class Stream {
        final Socket socket;
        final OutputStream out;

        Stream(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        }
    }

    private final ReliableLink link;
    private final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "gs-bridge");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Stream> streams = new HashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final HostSocketFactory factory;
    private int nextStreamId = 1;
    private ServerSocket acceptSocket;
    private int connectedCount;

    private StreamBridge(ReliableLink link, HostSocketFactory factory) {
        this.link = link;
        this.factory = factory;
        link.setListener(this);
    }

    /** Host side: tunnel OPEN frames into fresh TCP connections to the world server. */
    public static StreamBridge host(ReliableLink link, HostSocketFactory factory) {
        return new StreamBridge(link, factory);
    }

    /** Guest side: listens locally; each accepted connection becomes a tunneled stream. */
    public static StreamBridge guest(ReliableLink link) throws IOException {
        return new StreamBridge(link, null);
    }

    public int localPort() {
        return acceptSocket != null ? acceptSocket.getLocalPort() : -1;
    }

    public int connectedStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    public int totalStreamsAccepted() {
        return connectedCount;
    }

    public void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            if (acceptSocket != null) {
                acceptSocket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
        synchronized (streams) {
            for (Stream s : streams.values()) {
                closeQuietly(s.socket);
            }
            streams.clear();
        }
        io.shutdownNow();
    }

    // ------------------------------------------------------------ link side

    @Override
    public void onMessage(byte[] data) {
        if (data.length < 4) {
            return;
        }
        int streamId = data[0] & 0xFF;
        int op = data[1] & 0xFF;
        int len = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (4 + len > data.length) {
            return;
        }
        Stream stream;
        synchronized (streams) {
            stream = streams.get(streamId);
        }
        switch (op) {
            case OP_OPEN: {
                if (stream != null || factory == null) {
                    return;
                }
                openHostStream(streamId);
                break;
            }
            case OP_DATA: {
                if (stream != null) {
                    try {
                        synchronized (stream.out) {
                            stream.out.write(data, 4, len);
                            stream.out.flush();
                        }
                    } catch (IOException e) {
                        abort(streamId);
                    }
                }
                break;
            }
            case OP_EOF:
            case OP_RST: {
                if (stream != null) {
                    closeQuietly(stream.socket);
                    forget(streamId);
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onClosed(String reason) {
        shutdown();
    }

    private void openHostStream(int streamId) {
        io.execute(() -> {
            Socket socket;
            try {
                socket = factory.connect();
            } catch (IOException e) {
                GsLog.warn("Host bridge: cannot reach the world server: " + e.getMessage());
                sendFrame(streamId, OP_RST, null);
                return;
            }
            try {
                Stream stream = new Stream(socket);
                synchronized (streams) {
                    streams.put(streamId, stream);
                }
                connectedCount++;
                pumpSocketToLink(streamId, socket);
            } catch (IOException e) {
                closeQuietly(socket);
                sendFrame(streamId, OP_RST, null);
            }
        });
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            try {
                Socket socket = acceptSocket.accept();
                socket.setTcpNoDelay(true);
                int streamId;
                synchronized (this) {
                    streamId = nextStreamId++;
                }
                Stream stream = new Stream(socket);
                synchronized (streams) {
                    streams.put(streamId, stream);
                }
                connectedCount++;
                sendFrame(streamId, OP_OPEN, null);
                pumpSocketToLink(streamId, socket);
            } catch (IOException e) {
                if (!stopped.get()) {
                    GsLog.debug("Guest bridge accept failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void pumpSocketToLink(int streamId, Socket socket) {
        io.execute(() -> {
            byte[] buffer = new byte[CHUNK_SIZE];
            try {
                InputStream in = socket.getInputStream();
                while (!stopped.get()) {
                    int n = in.read(buffer);
                    if (n < 0) {
                        sendFrame(streamId, OP_EOF, null);
                        break;
                    }
                    if (n > 0) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buffer, 0, chunk, 0, n);
                        sendFrame(streamId, OP_DATA, chunk);
                    }
                }
            } catch (IOException e) {
                sendFrame(streamId, OP_RST, null);
            } finally {
                closeQuietly(socket);
                forget(streamId);
            }
        });
    }

    private void abort(int streamId) {
        sendFrame(streamId, OP_RST, null);
        Stream stream;
        synchronized (streams) {
            stream = streams.remove(streamId);
        }
        if (stream != null) {
            closeQuietly(stream.socket);
        }
    }

    private void forget(int streamId) {
        synchronized (streams) {
            streams.remove(streamId);
        }
    }

    private void sendFrame(int streamId, int op, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] frame = new byte[4 + len];
        frame[0] = (byte) streamId;
        frame[1] = (byte) op;
        frame[2] = (byte) ((len >> 8) & 0xFF);
        frame[3] = (byte) (len & 0xFF);
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 4, len);
        }
        if (!stopped.get()) {
            link.send(frame);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
