package com.gsmultiplayer.network.portmap;

import com.gsmultiplayer.p2p.StunResolver;
import com.gsmultiplayer.util.GsLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tries every router port-forwarding protocol in turn (like Open2Online):
 * UPnP IGD, then NAT-PMP, then PCP. Keeps the active mapping and can undo it.
 */
public final class PortMapService {

    /** Result of a successful (or failed) mapping attempt. */
    public static final class Result {
        public final boolean ok;
        public final String method;
        public final int externalPort;

        public Result(boolean ok, String method, int externalPort) {
            this.ok = ok;
            this.method = method;
            this.externalPort = externalPort;
        }
    }

    private static final PortMapService INSTANCE = new PortMapService();

    private PortMapper active;
    private int lanPort = -1;
    private int externalPort = -1;

    private PortMapService() {
    }

    public static PortMapService get() {
        return INSTANCE;
    }

    /** Is a mapping currently active? */
    public synchronized boolean isActive() {
        return active != null;
    }

    /** Protocol that produced the active mapping, or null. */
    public synchronized String activeMethod() {
        return active == null ? null : active.name();
    }

    /** External port of the active mapping, or -1. */
    public synchronized int activeExternalPort() {
        return externalPort;
    }

    /**
     * Opens a UDP pinhole for the Minecraft LAN port on the router.
     * Tries ports {@code lanPort..lanPort+9} to dodge occupied/forbidden ports.
     */
    public synchronized Result map(int lanPort) {
        if (isActive()) {
            unmap();
        }
        PortMapper[] mappers = {new UpnpMapper(), new NatPmpMapper(false), new NatPmpMapper(true)};
        for (PortMapper mapper : mappers) {
            for (int candidate = lanPort; candidate < lanPort + 10; candidate++) {
                int assigned;
                try {
                    assigned = mapper.map(lanPort, candidate);
                } catch (Exception e) {
                    GsLog.debug(mapper.name() + " failed: " + e.getMessage());
                    break;
                }
                if (assigned > 0) {
                    active = mapper;
                    this.lanPort = lanPort;
                    this.externalPort = assigned;
                    return new Result(true, mapper.name(), assigned);
                }
            }
        }
        GsLog.info("PortMap: no router protocol available (UPnP/NAT-PMP/PCP)");
        return new Result(false, null, -1);
    }

    /** Removes the active mapping. Never throws. */
    public synchronized void unmap() {
        if (active != null) {
            try {
                active.unmap();
            } catch (Exception e) {
                GsLog.debug("unmap: " + e.getMessage());
            }
            GsLog.info("PortMap: removed " + active.name() + " mapping for port " + externalPort);
            active = null;
            externalPort = -1;
            lanPort = -1;
        }
    }

    /**
     * Public IP of this connection: STUN first (shows the NAT-reflected address),
     * then plain HTTPS echo services - they work even when UDP is blocked.
     */
    public static String publicIp() {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetSocketAddress mapped = StunResolver.queryAny(socket, 2000);
            if (mapped != null && !mapped.getAddress().isSiteLocalAddress()) {
                return mapped.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            GsLog.debug("public IP via STUN: " + e.getMessage());
        }
        for (String service : new String[]{"https://api.ipify.org", "https://checkip.amazonaws.com"}) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(service).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String ip = reader.readLine();
                reader.close();
                if (ip != null && ip.trim().matches("[0-9.]{7,15}")) {
                    GsLog.info("Public IP via " + service + ": " + ip.trim());
                    return ip.trim();
                }
            } catch (Exception e) {
                GsLog.debug("public IP via " + service + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** Preferred LAN IP of this machine; null when none. */
    public static String localSiteIp() {
        try {
            InetAddress best = null;
            for (java.util.Enumeration<java.net.NetworkInterface> nifs =
                    java.net.NetworkInterface.getNetworkInterfaces(); nifs.hasMoreElements(); ) {
                java.net.NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (java.util.Enumeration<InetAddress> addrs = nif.getInetAddresses();
                        addrs.hasMoreElements(); ) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        if (best == null || !addr.getHostAddress().endsWith(".1")) {
                            best = addr;
                        }
                    }
                }
            }
            return best == null ? null : best.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
