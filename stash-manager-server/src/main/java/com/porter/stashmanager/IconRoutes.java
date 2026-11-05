package com.porter.stashmanager;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/** GET /icon/<namespace>/<path>.png. The name and mc query parameters are accepted but ignored. */
final class IconRoutes {
    private final IconService icons;

    IconRoutes(IconService icons) {
        this.icons = icons;
    }

    void register(Router router) {
        router.prefix(Router.GET, "/icon/", Router.Guard.SESSION, this::icon);
    }

    private void icon(HttpExchange ex) throws IOException {
        String rest = ex.getRequestURI().getRawPath().substring("/icon/".length());
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.endsWith(".png")) {
            Http.error(ex, 400, "expected /icon/<namespace>/<path>.png");
            return;
        }
        String ns = rest.substring(0, slash);
        String path = rest.substring(slash + 1, rest.length() - ".png".length());
        if (!IconService.ID_PART.matcher(ns).matches() || !IconService.ID_PART.matcher(path).matches()) {
            Http.error(ex, 400, "bad item id");
            return;
        }
        // ?v=<mcVersion>-g1 is a cache-busting token from the page; its version picks the game jar (when an
        // index has that version). Other query parameters are ignored.
        String v = Http.query(ex).get("v");
        String mc = null;
        if (v != null) {
            int dash = v.indexOf('-');
            mc = dash < 0 ? v : v.substring(0, dash);
        }
        IconService.Icon icon = icons.get(ns, path, mc);
        // 503 (still fetching): the page's <img> gets an error event and retries after Retry-After.
        if (icon.status() == 503) ex.getResponseHeaders().set("Retry-After", "2");
        Http.send(ex, icon.status(), icon.contentType(), icon.data(), icon.cacheControl());
    }
}
