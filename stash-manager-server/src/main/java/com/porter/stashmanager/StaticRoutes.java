package com.porter.stashmanager;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** The website's files from the jar's /web folder, served at /<name>. Registered last, as the fallback. */
final class StaticRoutes {
    private static final Pattern STATIC_NAME = Pattern.compile("^[a-z0-9_-][a-z0-9_.-]{0,63}$");

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    void register(Router router) {
        router.fallback(Router.GET, "/", this::staticFile);
    }

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!STATIC_NAME.matcher(name).matches()) {
            Http.error(ex, 404, "not found");
            return;
        }
        byte[] data = cache.get(name);
        if (data == null) {
            data = load(name);
            if (data == null) {
                Http.error(ex, 404, "not found");
                return;
            }
            cache.put(name, data); // only real resources are cached, so the map stays small
        }
        Http.send(ex, 200, type(name), data, "no-cache");
    }

    private static byte[] load(String name) {
        try (InputStream in = StaticRoutes.class.getResourceAsStream("/web/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String type(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "json" -> "application/json; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
