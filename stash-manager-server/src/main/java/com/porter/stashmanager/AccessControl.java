package com.porter.stashmanager;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The access rules from PROTOCOL.md: LAN-only remote addresses, a Host allowlist, and the bearer key. */
public final class AccessControl {
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-f:.]+(%[0-9a-z]+)?$");

    static final String JSON = "application/json";
    static final String OCTET_STREAM = "application/octet-stream";

    private final boolean allowRemote;
    private final Set<String> allowedHosts = new HashSet<>();
    /** Only the names from allowedHosts= in config.properties (no localhost, machine names or IPs): the HTTPS proxy names. */
    private final Set<String> proxyNames = new HashSet<>();
    private final Config config;

    public AccessControl(Config config) {
        this.allowRemote = config.allowRemote;
        this.config = config;
        allowedHosts.add("localhost");
        allowedHosts.addAll(config.allowedHosts);
        for (String name : config.allowedHosts) {
            String n = stripDot(name.trim().toLowerCase(Locale.ROOT));
            if (!n.isEmpty() && !isLocalOrIp(n)) proxyNames.add(n);
        }
        try {
            String name = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                allowedHosts.add(name);
                allowedHosts.add(name + ".local");
                allowedHosts.add(name + ".lan");
            }
        } catch (UnknownHostException ignored) {
        }
        String computerName = System.getenv("COMPUTERNAME");
        if (computerName != null && !computerName.isBlank()) {
            String name = computerName.trim().toLowerCase(Locale.ROOT);
            allowedHosts.add(name);
            allowedHosts.add(name + ".local");
            allowedHosts.add(name + ".lan");
        }
    }

    /** True when both the socket address and the Host header pass. Never reads X-Forwarded-For. */
    public boolean isAllowed(HttpExchange exchange) {
        return remoteAllowed(exchange.getRemoteAddress()) && hostAllowed(exchange.getRequestHeaders().getFirst("Host"));
    }

    boolean remoteAllowed(InetSocketAddress remote) {
        if (allowRemote) return true;
        if (remote == null) return false;
        InetAddress address = remote.getAddress();
        if (address == null) return false;
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168);
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // ULA fc00::/7
        }
        return false;
    }

    boolean hostAllowed(String hostHeader) {
        if (hostHeader == null) return false;
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return false;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) return false;
            String rest = host.substring(end + 1);
            if (!rest.isEmpty() && !rest.matches("^:\\d{1,5}$")) return false;
            String literal = host.substring(1, end);
            return literal.contains(":") && IPV6_CHARS.matcher(literal).matches();
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            String port = host.substring(colon + 1);
            if (!port.matches("^\\d{1,5}$")) return false;
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return false;
        if (IPV4.matcher(host).matches()) return true;
        return allowedHosts.contains(host);
    }

    /**
     * Constant-time check of "Authorization: Bearer <key>" against the current key.
     * Returns the key's generation when it matches, otherwise -1.
     */
    public long keyGeneration(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.size() != 1) return -1;
        String value = headers.get(0).trim();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return -1;
        byte[] given = value.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        Config.KeyState key = config.keyState();
        return MessageDigest.isEqual(given, key.bytes()) ? key.generation() : -1;
    }

    /**
     * The guard for state-changing requests from the website (see "Browser writes" in PROTOCOL.md).
     * The custom header forces a CORS preflight on cross-site requests, which fails because no CORS
     * headers are ever sent; the Origin and Sec-Fetch-Site checks catch anything that slips past.
     */
    public boolean browserWriteAllowed(HttpExchange ex) {
        return browserWriteAllowed(ex.getRequestMethod(), ex.getRequestHeaders());
    }

    /** The same checks, but the body must have this media type (the build planner's raw uploads). */
    public boolean browserWriteAllowed(HttpExchange ex, String mediaType) {
        return browserWriteAllowed(ex.getRequestMethod(), ex.getRequestHeaders(), mediaType);
    }

    boolean browserWriteAllowed(String method, Headers h) {
        return browserWriteAllowed(method, h, JSON);
    }

    boolean browserWriteAllowed(String method, Headers h, String mediaType) {
        if (!"POST".equals(method)) return false;
        if (!single(h, "X-Stash-Manager", "1")) return false;
        List<String> types = h.get("Content-Type");
        if (types == null || types.size() != 1) return false;
        String media = types.get(0).split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!media.equals(mediaType)) return false;
        return sameOriginRead(h);
    }

    /**
     * The Origin and Sec-Fetch-Site rules of {@link #browserWriteAllowed} for a GET that streams private data for a
     * long time (the live viewer): an Origin, when sent, must be this server's; a Sec-Fetch-Site, when sent, must be
     * same-origin or none. Stops another site from opening the stream with the user's cookie.
     */
    public boolean sameOriginRead(HttpExchange ex) {
        return sameOriginRead(ex.getRequestHeaders());
    }

    boolean sameOriginRead(Headers h) {
        List<String> origins = h.get("Origin");
        if (origins != null) {
            if (origins.size() != 1) return false;
            if (!originMatchesHost(origins.get(0), h.getFirst("Host"))) return false;
        }
        List<String> site = h.get("Sec-Fetch-Site");
        if (site != null) {
            if (site.size() != 1) return false;
            String s = site.get(0).trim().toLowerCase(Locale.ROOT);
            if (!s.equals("same-origin") && !s.equals("none")) return false;
        }
        return true;
    }

    private static boolean single(Headers h, String name, String expected) {
        List<String> values = h.get(name);
        return values != null && values.size() == 1 && expected.equals(values.get(0).trim());
    }

    /**
     * Same origin: an http origin must equal the Host header's host and port (as before). An https origin is only
     * accepted for a name configured in allowedHosts (a TLS reverse proxy such as a Cloudflare Tunnel in front of
     * this plain-HTTP server): default port 443, and only the host names are compared, since the proxy forwards
     * the request with the bare name (port 80 after normalising). IP literals and localhost stay http-only.
     */
    boolean originMatchesHost(String origin, String hostHeader) {
        String hostHostPort = hostHeaderHostPort(hostHeader);
        if (hostHostPort == null) return false;
        String httpOrigin = originHostPort(origin);
        if (httpOrigin != null) return httpOrigin.equals(hostHostPort);
        String httpsHost = httpsOriginHost(origin);
        if (httpsHost == null || !proxyNames.contains(httpsHost)) return false;
        String hostName = stripDot(hostHostPort.substring(0, hostHostPort.lastIndexOf(':')));
        return httpsHost.equals(hostName);
    }

    /** "https://name[:443]" to "name", or null (other schemes or ports, paths, IP literals, localhost). */
    static String httpsOriginHost(String origin) {
        try {
            URI uri = new URI(origin.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) return null;
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            if (uri.getPort() != -1 && uri.getPort() != 443) return null;
            String host = stripDot(uri.getHost().toLowerCase(Locale.ROOT));
            return host.isEmpty() || isLocalOrIp(host) ? null : host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean isLocalOrIp(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("[") || host.contains(":")
                || IPV4.matcher(host).matches();
    }

    private static String stripDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    /** "http://host:port" to "host:port" (port 80 when absent). Only http origins can match, since this server is plain HTTP. */
    static String originHostPort(String origin) {
        try {
            URI uri = new URI(origin.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) return null;
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** "host[:port]" from the Host header, normalized like {@link #originHostPort}. */
    static String hostHeaderHostPort(String host) {
        if (host == null) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        String name;
        String port = "80";
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end < 0) return null;
            name = h.substring(0, end + 1);
            String rest = h.substring(end + 1);
            if (rest.startsWith(":")) port = rest.substring(1);
            else if (!rest.isEmpty()) return null;
        } else {
            int colon = h.indexOf(':');
            name = colon < 0 ? h : h.substring(0, colon);
            if (colon >= 0) port = h.substring(colon + 1);
        }
        if (name.isEmpty() || !port.matches("^\\d{1,5}$")) return null;
        return name + ":" + Integer.parseInt(port);
    }
}
