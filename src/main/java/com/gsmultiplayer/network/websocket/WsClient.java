package com.gsmultiplayer.network.websocket;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client with no external dependencies.
 * Supports ws:// and wss://, text frames, fragmentation, ping/pong and close.
 * One instance = one connection; methods are safe to call from any thread.
 */
public final class WsClient {

    public interface Listener {
        void onText(String text);

        void onClosed(int code, String reason, boolean remote);
    }

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int MAX_MESSAGE_BYTES = 512 * 1024;

    private final URI uri;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object sendLock = new Object();

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open;
    private volatile boolean closeSent;
    private Thread readerThread;

    public WsClient(URI uri, Listener listener) {
        this.uri = uri;
        this.listener = listener;
    }

    public void connect() throws IOException {
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        if (!tls && !"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Unsupported WebSocket scheme: " + uri.getScheme());
        }
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        socket = tls ? ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket() : new Socket();
        socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
        if (tls) {
            SSLSocket ssl = (SSLSocket) socket;
            ssl.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3", "TLSv1", "TLS"});
            ssl.startHandshake();
        }
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        handshake();

        open = true;
        readerThread = new Thread(this::readLoop, "gs-ws-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public boolean isOpen() {
        return open;
    }

    public void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (sendLock) {
            requireOpen();
            writeFrame(0x1, payload);
            out.flush();
        }
    }

    /** Application-level keepalive; also keeps NAT bindings warm. */
    public void sendPing(String hint) throws IOException {
        byte[] payload = hint == null ? new byte[0] : hint.getBytes(StandardCharsets.UTF_8);
        synchronized (sendLock) {
            requireOpen();
            writeFrame(0x9, payload);
            out.flush();
        }
    }

    public void close() {
        open = false;
        try {
            synchronized (sendLock) {
                if (out != null && !closeSent) {
                    closeSent = true;
                    byte[] body = new byte[2];
                    body[0] = (byte) ((1000 >> 8) & 0xFF);
                    body[1] = (byte) (1000 & 0xFF);
                    writeFrame(0x8, body);
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ //

    private void handshake() throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        String hostHeader = uri.getHost() + ((port == 80 || port == 443) ? "" : ":" + port);

        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = readAsciiLine();
        if (statusLine == null || !statusLine.contains(" 101 ")) {
            throw new IOException("WebSocket handshake failed: " + statusLine);
        }
        String accept = null;
        String line;
        while ((line = readAsciiLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String expected = expectedAccept(key);
        if (accept == null || !accept.equalsIgnoreCase(expected)) {
            throw new IOException("WebSocket handshake: bad Sec-WebSocket-Accept");
        }
    }

    private static String expectedAccept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    private void readLoop() {
        Deque<byte[]> fragments = new ArrayDeque<>();
        int fragmentedOpcode = -1;
        int totalFragmentBytes = 0;
        try {
            while (open) {
                int b0 = in.read();
                if (b0 < 0) {
                    break;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 < 0) {
                    break;
                }
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) readExact(2)) & 0xFFFF;
                } else if (len == 127) {
                    len = readExact(8);
                }
                if (len < 0 || len > MAX_MESSAGE_BYTES) {
                    throw new EOFException("WebSocket frame too large: " + len);
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                switch (opcode) {
                    case 0x0:
                    case 0x1:
                    case 0x2: {
                        if (opcode == 0x0 && fragmentedOpcode == -1) {
                            continue; // stray continuation
                        }
                        if (opcode != 0x0) {
                            fragmentedOpcode = opcode;
                            fragments.clear();
                            totalFragmentBytes = 0;
                        }
                        totalFragmentBytes += payload.length;
                        if (totalFragmentBytes > MAX_MESSAGE_BYTES) {
                            throw new EOFException("WebSocket message too large");
                        }
                        fragments.addLast(payload);
                        if (fin) {
                            boolean text = fragmentedOpcode == 0x1;
                            byte[] message = concat(fragments);
                            fragments.clear();
                            fragmentedOpcode = -1;
                            if (text) {
                                listener.onText(new String(message, StandardCharsets.UTF_8));
                            }
                        }
                        break;
                    }
                    case 0x8: {
                        int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                        String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        open = false;
                        listener.onClosed(code, reason, true);
                        return;
                    }
                    case 0x9: {
                        synchronized (sendLock) {
                            if (open) {
                                writeFrame(0xA, payload);
                                out.flush();
                            }
                        }
                        break;
                    }
                    case 0xA:
                    default:
                        // ignore pongs and unknown control frames
                        break;
                }
            }
            open = false;
            listener.onClosed(1006, "connection lost", true);
        } catch (IOException e) {
            open = false;
            listener.onClosed(1006, e.getMessage() == null ? "connection error" : e.getMessage(), true);
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        out.write(mask);
        for (int i = 0; i < len; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
    }

    private void requireOpen() throws IOException {
        if (!open) {
            throw new IOException("WebSocket is closed");
        }
    }

    private String readAsciiLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int prev = -1;
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n' && prev == '\r') {
                break;
            }
            buf.write(c);
            prev = c;
            if (buf.size() > 16 * 1024) {
                throw new IOException("WebSocket handshake line too long");
            }
        }
        byte[] bytes = buf.toByteArray();
        if (bytes.length >= 2 && bytes[bytes.length - 1] == '\n') {
            bytes[bytes.length - 2] = ' '; // strip trailing CR
        }
        return new String(bytes, StandardCharsets.US_ASCII).trim();
    }

    private long readExact(int n) throws IOException {
        byte[] buf = new byte[n];
        readFully(buf);
        long value = 0;
        for (byte b : buf) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException("WebSocket connection closed");
            }
            off += n;
        }
    }

    private static byte[] concat(Deque<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out2 = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out2, off, p.length);
            off += p.length;
        }
        return out2;
    }
}
