package com.porter.qwerte.manager.net;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * A validated Stash Manager address: http or https, a host, an optional port, nothing else. It is the only place that
 * builds request addresses, so a request can only ever go to this origin.
 */
public final class ServerUrl {
    private final String origin;

    private ServerUrl(String origin) {
        this.origin = origin;
    }

    /** Null for anything that isn't {@code http(s)://host[:port]} (a trailing slash is fine). */
    public static ServerUrl parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 256) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return null;
        }
        URI uri;
        try {
            uri = new URI(t);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) return null;
        return new ServerUrl(build(scheme, host, port));
    }

    /** The origin (scheme, host and port) of an address, in the same form as {@link #origin()}. */
    public static String originOf(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return null;
        return build(scheme.toLowerCase(Locale.ROOT), host, uri.getPort());
    }

    private static String build(String scheme, String host, int port) {
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    /** {@code http://host:port}, lower case, the default port left out. */
    public String origin() {
        return origin;
    }

    public boolean secure() {
        return origin.startsWith("https://");
    }

    /**
     * True when the host is this PC or on a local network: localhost, a loopback, private or link-local address, or a
     * name without dots (or ending in .local or .lan). Plain http to anything else travels the internet unencrypted.
     */
    public boolean localHost() {
        String rest = origin.substring(origin.indexOf("://") + 3);
        String host;
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            host = end < 0 ? rest : rest.substring(1, end);
        } else {
            int colon = rest.lastIndexOf(':');
            host = colon < 0 ? rest : rest.substring(0, colon);
        }
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan")) return true;
        if (host.contains(":")) {
            return host.equals("::1") || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd");
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
            } catch (NumberFormatException notAnAddress) {
                return false;
            }
        }
        return parts.length == 1;
    }

    /**
     * The full address of {@code rawPath} (already encoded, starting with a slash; use {@link #seg} for variable
     * parts) and the query values, which are encoded here.
     */
    public URI resolve(String rawPath, Map<String, String> query) {
        if (rawPath == null || !rawPath.startsWith("/")) throw new IllegalArgumentException("path");
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '?' || c == '#') throw new IllegalArgumentException("path");
        }
        StringBuilder sb = new StringBuilder(origin).append(rawPath);
        if (query != null && !query.isEmpty()) {
            char sep = '?';
            for (Map.Entry<String, String> e : query.entrySet()) {
                sb.append(sep).append(encode(e.getKey())).append('=').append(encode(e.getValue()));
                sep = '&';
            }
        }
        return URI.create(sb.toString());
    }

    /** One path segment, percent-encoded. */
    public static String seg(String s) {
        return encode(s == null ? "" : s);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ServerUrl other && other.origin.equals(origin);
    }

    @Override
    public int hashCode() {
        return origin.hashCode();
    }

    @Override
    public String toString() {
        return origin;
    }
}
