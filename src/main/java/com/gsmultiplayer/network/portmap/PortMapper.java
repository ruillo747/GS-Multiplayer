package com.gsmultiplayer.network.portmap;

/**
 * One port-forwarding protocol (UPnP IGD, NAT-PMP or PCP).
 * Implementations must be thread-safe enough for a single worker caller.
 */
public interface PortMapper {

    /** Human-readable protocol name for logs and UI ("UPnP", "NAT-PMP", "PCP"). */
    String name();

    /**
     * Asks the router to forward external {@code externalPort}/{@code protocol}
     * to {@code internalPort} on this machine.
     *
     * @param protocol "TCP" or "UDP" - the Minecraft LAN server listens on TCP
     * @return the actually assigned external port, or -1 on failure
     *         (may differ from the requested one when the router reassigns).
     */
    int map(int internalPort, int externalPort, String protocol);

    /** Removes the mapping created by {@link #map}. Never throws. */
    void unmap();
}
