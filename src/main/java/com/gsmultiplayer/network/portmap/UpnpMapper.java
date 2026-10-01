package com.gsmultiplayer.network.portmap;

import com.gsmultiplayer.util.GsLog;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * UPnP InternetGatewayDevice (WANIPConnection / WANPPPConnection) port mapping
 * via SSDP discovery + SOAP AddPortMapping/DeletePortMapping. Pure Java, no deps.
 */
public final class UpnpMapper implements PortMapper {

    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
    };

    private volatile String controlUrl;
    private volatile String serviceType;
    private int mappedInternal = -1;
    private int mappedExternal = -1;
    private String mappedProtocol = "TCP";

    @Override
    public String name() {
        return "UPnP";
    }

    @Override
    public int map(int internalPort, int externalPort, String protocol) {
        if (controlUrl == null && !discover()) {
            return -1;
        }
        int assigned = soapAdd(internalPort, externalPort, protocol);
        if (assigned > 0) {
            mappedInternal = internalPort;
            mappedExternal = assigned;
            mappedProtocol = protocol;
        }
        return assigned;
    }

    @Override
    public void unmap() {
        if (mappedInternal > 0 && controlUrl != null) {
            try {
                soapDelete(mappedInternal, mappedExternal, mappedProtocol);
            } catch (Exception e) {
                GsLog.debug("UPnP unmap: " + e.getMessage());
            }
            mappedInternal = -1;
            mappedExternal = -1;
        }
    }

    /** Package-private: pre-set a control URL (tests, manual configuration). */
    void setControlUrl(String url, String serviceType) {
        this.controlUrl = url;
        this.serviceType = serviceType;
    }

    // ---------------------------------------------------------------- SSDP

    private boolean discover() {
        List<String> locations = ssdpDiscover(2500);
        for (String location : locations) {
            try {
                if (parseDeviceDescription(location)) {
                    GsLog.info("UPnP: gateway found at " + controlUrl);
                    return true;
                }
            } catch (Exception e) {
                GsLog.debug("UPnP: bad description " + location + ": " + e.getMessage());
            }
        }
        GsLog.info("UPnP: no compatible gateway found (" + locations.size() + " devices replied)");
        return false;
    }

    private static List<String> ssdpDiscover(int timeoutMs) {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            outer:
            while (System.currentTimeMillis() < deadline) {
                for (String st : SEARCH_TARGETS) {
                    socket.send(new DatagramPacket(
                            searchRequest(st).getBytes("UTF-8"),
                            searchRequest(st).getBytes("UTF-8").length, group, 1900));
                }
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }
                    String location = extractHeader(new String(packet.getData(), 0, packet.getLength(),
                            java.nio.charset.StandardCharsets.ISO_8859_1), "LOCATION");
                    if (location != null) {
                        locations.add(location);
                        if (locations.size() >= 4) {
                            break outer;
                        }
                    }
                }
            }
        } catch (Exception e) {
            GsLog.debug("UPnP SSDP: " + e.getMessage());
        }
        return new ArrayList<>(locations);
    }

    private static String searchRequest(String searchTarget) {
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + searchTarget + "\r\n\r\n";
    }

    private static String extractHeader(String response, String header) {
        for (String line : response.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx > 0 && header.equalsIgnoreCase(line.substring(0, idx).trim())) {
                return line.substring(idx + 1).trim();
            }
        }
        return null;
    }

    // ------------------------------------------------- device description

    private boolean parseDeviceDescription(String location) throws Exception {
        byte[] xml = httpGet(location, 4000);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml));
        String base = location;
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String type = childText(service, "serviceType");
            String control = childText(service, "controlURL");
            if (type == null || control == null) {
                continue;
            }
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.contains("wanipconnection") || lower.contains("wanpppconnection")) {
                this.controlUrl = resolveUrl(base, control);
                this.serviceType = type;
                return true;
            }
        }
        // InternetGatewayDevice descriptors list embedded devices: look one level deeper.
        NodeList devices = doc.getElementsByTagName("device");
        for (int i = 0; i < devices.getLength(); i++) {
            Element device = (Element) devices.item(i);
            NodeList deviceList = device.getElementsByTagName("deviceList");
            for (int d = 0; d < deviceList.getLength(); d++) {
                NodeList inner = deviceList.item(d).getChildNodes();
                for (int n = 0; n < inner.getLength(); n++) {
                    Node node = inner.item(n);
                    if (node instanceof Element) {
                        NodeList svc = ((Element) node).getElementsByTagName("service");
                        for (int s = 0; s < svc.getLength(); s++) {
                            Element service = (Element) svc.item(s);
                            String type = childText(service, "serviceType");
                            String control = childText(service, "controlURL");
                            if (type != null && control != null
                                    && type.toLowerCase(Locale.ROOT).contains("wan")) {
                                this.controlUrl = resolveUrl(base, control);
                                this.serviceType = type;
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static String resolveUrl(String base, String path) throws Exception {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        URL url = new URL(base);
        String resolved;
        if (path.startsWith("/")) {
            resolved = url.getProtocol() + "://" + url.getAuthority() + path;
        } else {
            String dir = base.substring(0, base.lastIndexOf('/') + 1);
            resolved = dir + path;
        }
        return URLDecoder.decode(resolved, "UTF-8");
    }

    // ---------------------------------------------------------------- SOAP

    private int soapAdd(int internalPort, int externalPort, String protocol) {
        String localIp = PortMapService.localSiteIp();
        String body = "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">"
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>"
                + "<NewInternalPort>" + internalPort + "</NewInternalPort>"
                + "<NewInternalClient>" + localIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>GS-Multiplayer</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>"
                + "</u:AddPortMapping>";
        try {
            String response = soap(body, "AddPortMapping");
            if (response.contains("AddPortMappingResponse")
                    || response.startsWith("HTTP/1.") && response.contains(" 200 ")) {
                GsLog.info("UPnP: mapped " + protocol + " " + externalPort + " -> " + localIp + ":" + internalPort);
                return externalPort;
            }
            GsLog.debug("UPnP add rejected: " + firstLine(response));
        } catch (Exception e) {
            GsLog.debug("UPnP add failed: " + e.getMessage());
        }
        return -1;
    }

    private void soapDelete(int internalPort, int externalPort, String protocol) throws Exception {
        String body = "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">"
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + externalPort + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>"
                + "</u:DeletePortMapping>";
        soap(body, "DeletePortMapping");
    }

    private String soap(String body, String action) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(controlUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setDoOutput(true);
        conn.setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        String envelope = "<?xml version=\"1.0\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<SOAP-ENV:Body>" + body + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
        conn.getOutputStream().write(envelope.getBytes("UTF-8"));
        conn.getOutputStream().close();
        InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        if (in != null) {
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                if (out.size() > 64 * 1024) {
                    break;
                }
            }
            in.close();
        }
        return "HTTP/1.1 " + conn.getResponseCode() + "\n" + out.toString("UTF-8");
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        String line = idx < 0 ? s : s.substring(0, idx);
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    private static byte[] httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) > 0) {
            out.write(buf, 0, read);
            if (out.size() > 256 * 1024) {
                break;
            }
        }
        in.close();
        return out.toByteArray();
    }
}
