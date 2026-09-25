package com.gsmultiplayer.p2p;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Connectivity candidates exchanged through the signaling server. */
public final class Candidate {

    public final String type; // "host" or "srflx"
    public final String host;
    public final int port;

    public Candidate(String type, String host, int port) {
        this.type = type;
        this.host = host;
        this.port = port;
    }

    public InetSocketAddress toAddress() {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return type + ":" + host + ":" + port;
    }

    /** Serializes candidates to the compact "ip:port" form sent over signaling. */
    public static String encode(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /** Local (host) candidates: every interface address bound to the given port, loopback first. */
    public static List<InetSocketAddress> localAddresses(int port) {
        List<InetSocketAddress> out = new ArrayList<>();
        out.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<InetAddress> addresses = new ArrayList<>();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        addresses.add(addr);
                    }
                }
            }
            for (InetAddress addr : addresses) {
                out.add(new InetSocketAddress(addr, port));
            }
        } catch (Exception e) {
            // interfaces unavailable: loopback candidate is enough for tests
        }
        return out;
    }

    public static List<InetSocketAddress> parseAll(List<String> encoded) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String entry : encoded) {
            InetSocketAddress addr = parse(entry);
            if (addr != null) {
                out.add(addr);
            }
        }
        return out;
    }

    public static InetSocketAddress parse(String encoded) {
        if (encoded == null) {
            return null;
        }
        int sep = encoded.lastIndexOf(':');
        if (sep <= 0 || sep == encoded.length() - 1) {
            return null;
        }
        try {
            String host = encoded.substring(0, sep);
            int port = Integer.parseInt(encoded.substring(sep + 1));
            if (port < 1 || port > 65535) {
                return null;
            }
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return null;
        }
    }
}
