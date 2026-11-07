package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Opaque ids for users in forced streamer mode (PROTOCOL.md, "Additions: website users"): such a user never gets a
 * server address or a bot id, only {@code sid = base64url(HMAC-SHA256(secret, server))[:12]} and
 * {@code bid = "b" + base64url(HMAC-SHA256(secret, "bot:" + botId))[:12]}. The secret is generated once per install and
 * kept in the data folder (owner-only). Requests from such a user carry the opaque ids, which are mapped back here; a
 * raw address or bot id is not accepted from them (it would be an oracle).
 */
final class ServerIds {
    static final String FILE = "streamer-ids.key";
    private final byte[] secret;
    private final Supplier<? extends Iterable<String>> servers;
    private final Supplier<? extends Iterable<String>> bots;

    ServerIds(byte[] secret, Supplier<? extends Iterable<String>> servers, Supplier<? extends Iterable<String>> bots) {
        this.secret = secret.clone();
        this.servers = servers;
        this.bots = bots;
    }

    /** The install's secret from dataDir, generated (owner-only) the first time. dataDir null: a fresh one in memory. */
    static byte[] secret(Path dataDir) {
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        if (dataDir == null) return fresh;
        Path f = dataDir.resolve(FILE);
        try {
            if (Files.exists(f)) {
                byte[] s = HexFormat.of().parseHex(Files.readString(f, StandardCharsets.UTF_8).trim());
                if (s.length >= 16) {
                    AtomicFiles.ownerOnly(f);
                    return s;
                }
            }
            AtomicFiles.write(f, HexFormat.of().formatHex(fresh).getBytes(StandardCharsets.UTF_8), true);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Could not read or save " + FILE + " (" + e.getMessage() + "); opaque ids change on restart.");
        }
        return fresh;
    }

    private String mac(String text, int chars) {
        byte[] h = Signer.hmac(secret, text.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, chars);
    }

    String sid(String server) {
        return server == null ? null : mac("server:" + server, 12);
    }

    String bid(String botId) {
        return botId == null ? null : "b" + mac("bot:" + botId, 12);
    }

    /** The server address for an opaque id, or null (unknown, or not an opaque id at all). */
    String server(String sid) {
        if (sid == null) return null;
        for (String s : servers.get()) if (sid(s).equals(sid)) return s;
        return null;
    }

    /** The bot id for an opaque id, or null. */
    String bot(String bid) {
        if (bid == null) return null;
        for (String b : bots.get()) if (bid(b).equals(bid)) return b;
        return null;
    }

    /**
     * A copy of a response with every "server" value replaced by its sid and every "botId" by its bid, at any depth.
     * Applied to every JSON response of a forced user (Http.json), so no route can leak an address by accident.
     */
    JsonElement mask(JsonElement in) {
        if (in == null) return null;
        JsonElement copy = in.deepCopy();
        walk(copy);
        return copy;
    }

    private void walk(JsonElement e) {
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) walk(x);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (Map.Entry<String, JsonElement> en : o.entrySet()) {
                JsonElement v = en.getValue();
                boolean text = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString();
                if (text && en.getKey().equals("server")) en.setValue(new com.google.gson.JsonPrimitive(sid(v.getAsString())));
                else if (text && en.getKey().equals("botId")) en.setValue(new com.google.gson.JsonPrimitive(bid(v.getAsString())));
                else walk(v);
            }
        }
    }

    /** For tests and the planner: a JSON array of the opaque ids of these servers. */
    JsonArray sids(Iterable<String> list) {
        JsonArray a = new JsonArray();
        for (String s : list) a.add(sid(s));
        return a;
    }
}
