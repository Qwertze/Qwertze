package com.porter.qwerte.manager.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /api/v1/bots}: the dashboard bots the account may see. Free text is cleaned. Pure.
 */
public final class BotList {
    /** A stash the bot owns or last used ({@code stashes[]} of a bot). */
    public record Link(String server, String stash) {
        public String key() {
            return server + "\u0000" + stash;
        }
    }

    /**
     * @param scopes       the settings scopes the bot offers: plugin, proxy, plugin:&lt;id&gt;
     * @param proxyVersion the proxy's version, "" when it didn't say
     * @param proxyMc      the Minecraft version the proxy plays, "" when unknown
     * @param stashes      the stashes linked to this bot's player
     */
    public record Bot(String botId, String player, boolean online, long lastSeen, String summary, String pluginId, String pluginVersion,
                      Set<String> caps, List<String> scopes, String proxyVersion, String proxyMc, List<Link> stashes) {
        public boolean hasCap(String cap) {
            return caps.contains(cap);
        }

        public boolean hasScope(String scope) {
            return scopes.contains(scope);
        }
    }

    public static final BotList EMPTY = new BotList(List.of());

    private final List<Bot> bots;

    public BotList(List<Bot> bots) {
        this.bots = List.copyOf(bots);
    }

    public List<Bot> bots() {
        return bots;
    }

    public Bot find(String botId) {
        if (botId == null) return null;
        for (Bot b : bots) if (b.botId().equals(botId)) return b;
        return null;
    }

    public int indexOf(String botId) {
        for (int i = 0; i < bots.size(); i++) if (bots.get(i).botId().equals(botId)) return i;
        return -1;
    }

    /** Never null; anything that is not an array of objects with a bot id gives fewer (or no) bots. */
    public static BotList parse(JsonElement body) {
        JsonArray a = Json.arr(body);
        if (a == null) return EMPTY;
        List<Bot> out = new ArrayList<>();
        for (JsonElement e : a) {
            JsonObject o = Json.obj(e);
            if (o == null) continue;
            String id = Json.str(o, "botId");
            if (!Ids.valid(id)) continue;
            JsonObject plugin = Json.obj(o.get("plugin"));
            List<String> scopes = new ArrayList<>();
            JsonArray sa = Json.arr(o.get("scopes"));
            if (sa != null) {
                for (JsonElement s : sa) {
                    if (s.isJsonPrimitive() && s.getAsJsonPrimitive().isString() && scopes.size() < 50) {
                        String scope = s.getAsString();
                        if (scope.matches("[a-z0-9_.:-]{1,64}")) scopes.add(scope);
                    }
                }
            }
            java.util.Set<String> caps = new java.util.LinkedHashSet<>();
            JsonArray ca = Json.arr(o.get("caps"));
            if (ca != null) {
                for (JsonElement c : ca) {
                    if (c.isJsonPrimitive() && c.getAsJsonPrimitive().isString() && caps.size() < 20) caps.add(Text.sanitize(c.getAsString(), 24));
                }
            }
            JsonObject proxy = Json.obj(o.get("proxy"));
            List<Link> links = new ArrayList<>();
            JsonArray la = Json.arr(o.get("stashes"));
            if (la != null) {
                for (JsonElement l : la) {
                    JsonObject lo = Json.obj(l);
                    if (lo == null || links.size() >= 64) continue;
                    String server = Json.str(lo, "server");
                    String stash = Json.str(lo, "stash");
                    if (!server.isEmpty() && !stash.isEmpty()) links.add(new Link(server, stash));
                }
            }
            out.add(new Bot(id, Text.sanitize(Json.str(o, "player"), 32), Json.bool(o, "online"), Json.lng(o, "lastSeen", 0),
                Text.sanitize(Json.str(o, "summary"), 160), plugin == null ? "" : Text.sanitize(Json.str(plugin, "id"), 40),
                plugin == null ? "" : Text.sanitize(Json.str(plugin, "version"), 24), caps, scopes,
                proxy == null ? "" : Text.sanitize(Json.str(proxy, "version"), 24), proxy == null ? "" : Text.sanitize(Json.str(proxy, "mc"), 24),
                List.copyOf(links)));
            if (out.size() >= 64) break;
        }
        return new BotList(out);
    }

    /**
     * The name to show. {@code hide} (streamer display, forced or chosen): "Bot 2" instead of the player or the id, like the
     * website's forced streamer mode.
     */
    public String name(Bot b, boolean hide) {
        if (hide || (b.player().isEmpty() && b.botId().isEmpty())) {
            int i = indexOf(b.botId());
            return "Bot " + (i < 0 ? 1 : i + 1);
        }
        return b.player().isEmpty() ? Text.sanitize(b.botId(), 40) : b.player();
    }
}
