package com.gsmultiplayer.network.portmap;

import com.gsmultiplayer.util.GsLog;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * NAT-PMP / PCP client (RFC 6886 / RFC 6887): talks UDP to the router on port 5351.
 * NAT-PMP is the simple pre-2013 protocol, PCP is its modern superset;
 * both are common in home routers and complement UPnP.
 */
public final class NatPmpMapper implements PortMapper {

    private static final int PCP_MAP_PORT = 5351;
    private final boolean pcp; // false = classic NAT-PMP, true = PCP MAP
    private volatile InetAddress gateway;
    private volatile int mappedInternal = -1;
    private volatile int mappedExternal = -1;

    public NatPmpMapper(boolean pcp) {
        this.pcp = pcp;
    }

    @Override
    public String name() {
        return pcp ? "PCP" : "NAT-PMP";
    }

    /** Router candidates derived from this machine's site-local addresses. */
    static List<InetAddress> gatewayCandidates() {
        LinkedHashSet<InetAddress> out = new LinkedHashSet<>();
        try {
            for (java.net.NetworkInterface nif : java.util.Collections
                    .list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress addr : nif.getInterfaceAddresses()) {
                    InetAddress broad = addr == null ? null : addr.getBroadcast();
                    if (broad != null) {
                        try {
                            out.add(broad);
                        } catch (Exception ignored) {
                            // some stacks produce an unusable broadcast address
                        }
                    }
                    InetAddress local = addr == null ? null : addr.getAddress();
                    if (local != null && local.isSiteLocalAddress()) {
                        byte[] b = local.getAddress();
                        try {
                            out.add(InetAddress.getByAddress(new byte[]{b[0], b[1], b[2], 1}));
                            out.add(InetAddress.getByAddress(new byte[]{b[0], b[1], 0, 1}));
                            out.add(InetAddress.getByAddress(new byte[]{b[0], b[1], 1, 1}));
                        } catch (Exception ignored) {
                            // keep the remaining candidates
                        }
                    }
                }
            }
        } catch (Exception e) {
            GsLog.debug("gateway candidates: " + e.getMessage());
        }
        return new ArrayList<>(out);
    }

    @Override
    public int map(int internalPort, int externalPort) {
        for (InetAddress gw : gatewayCandidates()) {
            try {
                int assigned = request(gw, internalPort, externalPort, 7200);
                if (assigned > 0) {
                    gateway = gw;
                    mappedInternal = internalPort;
                    mappedExternal = assigned;
                    GsLog.info(name() + ": mapped UDP " + assigned + " -> "
                            + gw.getHostAddress() + " route to port " + internalPort);
                    return assigned;
                }
            } catch (Exception e) {
                GsLog.debug(name() + " " + gw.getHostAddress() + ": " + e.getMessage());
            }
        }
        return -1;
    }

    @Override
    public void unmap() {
        if (mappedInternal > 0 && gateway != null) {
            try {
                request(gateway, mappedInternal, mappedExternal, 0);
            } catch (Exception e) {
                GsLog.debug(name() + " unmap: " + e.getMessage());
            }
            mappedInternal = -1;
            mappedExternal = -1;
        }
    }

    /** Package-private override for tests. */
    int request(InetAddress gateway, int internalPort, int externalPort, int lifetimeSec)
            throws Exception {
        byte[] request = pcp ? pcpRequest(internalPort, externalPort, lifetimeSec)
                : natPmpRequest(internalPort, externalPort, lifetimeSec);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(1500);
            socket.connect(new InetSocketAddress(gateway, PCP_MAP_PORT));
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(request, request.length));
                byte[] buf = new byte[64];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Integer assigned = parseResponse(response.getData(), response.getLength(),
                        internalPort, externalPort, lifetimeSec);
                if (assigned != null && assigned > 0) {
                    return assigned;
                }
                if (assigned != null) {
                    return -1; // explicit failure (e.g. not supported) - try next gateway
                }
            }
        }
        return -1;
    }

    private static byte[] natPmpRequest(int internalPort, int externalPort, int lifetimeSec) {
        byte[] req = new byte[12];
        req[0] = 0;   // version
        req[1] = 1;   // op: map UDP
        // reserved[2]
        req[4] = (byte) (internalPort >> 8);
        req[5] = (byte) internalPort;
        req[6] = (byte) (externalPort >> 8);
        req[7] = (byte) externalPort;
        req[8] = (byte) (lifetimeSec >> 24);
        req[9] = (byte) (lifetimeSec >> 16);
        req[10] = (byte) (lifetimeSec >> 8);
        req[11] = (byte) lifetimeSec;
        return req;
    }

    private byte[] pcpRequest(int internalPort, int externalPort, int lifetimeSec) {
        byte[] req = new byte[48];
        req[0] = 2;   // version
        req[1] = 1;   // opcode MAP
        // reserved[1], requested lifetime[4]
        writeInt32(req, 4, lifetimeSec);
        // client address [16] - zero means "derive from source"
        req[20] = 0;  // nonce[12] - zero ok for simple clients
        req[24] = 17; // protocol UDP
        // reserved[3]
        req[28] = (byte) (internalPort >> 8);
        req[29] = (byte) internalPort;
        req[30] = (byte) (externalPort >> 8);
        req[31] = (byte) externalPort;
        writeInt32(req, 32, lifetimeSec); // suggested lifetime
        return req;
    }

    /** Returns assigned external port, -1 on protocol-level failure, null on transient (retry). */
    private Integer parseResponse(byte[] resp, int len, int internalPort, int externalPort,
                                  int lifetimeSec) {
        if (pcp) {
            if (len < 24 || resp[0] != 2) {
                return null;
            }
            int result = resp[3] & 0xFF;
            if (result == 0 && len >= 40) {
                return ((resp[34] & 0xFF) << 8) | (resp[35] & 0xFF);
            }
            if (result == 10 /* NETWORK_OVERSIZED */ || result == 13 /* ADDRESS_MISMATCH */) {
                return null;
            }
            return -1;
        }
        if (len < 16 || resp[0] != 0 || resp[1] != (byte) 0x81) {
            return null;
        }
        int result = ((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF);
        if (result == 0) {
            return ((resp[8] & 0xFF) << 8) | (resp[9] & 0xFF);
        }
        return -1;
    }

    private static void writeInt32(byte[] buf, int off, int value) {
        buf[off] = (byte) (value >> 24);
        buf[off + 1] = (byte) (value >> 16);
        buf[off + 2] = (byte) (value >> 8);
        buf[off + 3] = (byte) value;
    }
}
