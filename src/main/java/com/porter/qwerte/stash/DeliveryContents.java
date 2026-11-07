package com.porter.qwerte.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The delivery storage's contents as the Stash Manager server gets them (0.4.0-dev.7, {@code POST /api/v1/delivery}): what is in
 * each delivery chest, in fill order, with no positions. Pure: no game types, the caller turns stacks into {@link Item}s. Never
 * part of the stash index.
 */
public final class DeliveryContents {
    public static final int MAX_CHESTS = 16, MAX_NAME = 64, MAX_INSIDE = 27, MAX_COUNT = 10000, MAX_BYTES = 230_000;

    /** What is inside a shulker, one stack of it. */
    public record Inner(String id, int count) {}

    /** One stack in a chest. {@code inside} is null for anything but a shulker box. */
    public record Item(int slot, String id, String name, int count, int maxStack, List<Inner> inside, int damage, int maxDamage) {}

    /** One chest as read: {@code size} slots of the container part, {@code items} the non-empty ones (null: never read). */
    public record Chest(int size, boolean full, long readAt, List<Item> items) {}

    /** The finished report: its JSON body, the hash of the chests (readAt left out) and the newest read. */
    public record Built(String hash, long readAt, byte[] json, int chests) {}

    private static final com.google.gson.Gson WIRE = new com.google.gson.GsonBuilder().serializeNulls().create();

    private DeliveryContents() {}

    /**
     * @param chests every delivery chest in fill order (see {@link DeliveryBook#fillOrder}); more than {@link #MAX_CHESTS} are cut
     */
    public static Built build(String server, String stash, String player, List<Chest> chests, long now) {
        List<JsonObject> base = new ArrayList<>();
        List<Long> reads = new ArrayList<>();
        for (int n = 0; n < chests.size() && n < MAX_CHESTS; n++) {
            Chest c = chests.get(n);
            int size = c.size() > 27 ? 54 : 27;
            boolean read = c.readAt() > 0 && c.items() != null;
            JsonObject o = new JsonObject();
            o.addProperty("n", n);
            o.addProperty("size", size);
            o.addProperty("full", c.full());
            if (!read) o.add("items", com.google.gson.JsonNull.INSTANCE);
            else o.add("items", items(c.items(), size));
            base.add(o);
            reads.add(read ? c.readAt() : 0L);
        }
        Built built = assemble(server, stash, player, base, reads);
        if (built.json().length <= MAX_BYTES) return built;
        // Too big: the contents of the shulkers go first (shulker:true stays), then the items of the last chests, one chest at a time
        for (JsonObject o : base) {
            if (!o.get("items").isJsonArray()) continue;
            for (var item : o.getAsJsonArray("items")) item.getAsJsonObject().remove("contents");
        }
        built = assemble(server, stash, player, base, reads);
        for (int i = base.size() - 1; i >= 0 && built.json().length > MAX_BYTES; i--) {
            base.get(i).add("items", com.google.gson.JsonNull.INSTANCE);
            reads.set(i, 0L);
            built = assemble(server, stash, player, base, reads);
        }
        return built;
    }

    /** The report body, and the hash of the chests without their readAt (a read that found nothing changed is no change). */
    private static Built assemble(String server, String stash, String player, List<JsonObject> base, List<Long> reads) {
        JsonArray hashed = new JsonArray();
        for (JsonObject o : base) hashed.add(o);
        String hash = sha1(WIRE.toJson(hashed));
        JsonArray array = new JsonArray();
        long newest = 0;
        for (int i = 0; i < base.size(); i++) {
            JsonObject o = base.get(i);
            // The documented key order: n, size, full, readAt, items
            JsonObject ordered = new JsonObject();
            ordered.add("n", o.get("n"));
            ordered.add("size", o.get("size"));
            ordered.add("full", o.get("full"));
            ordered.addProperty("readAt", reads.get(i));
            ordered.add("items", o.get("items"));
            array.add(ordered);
            newest = Math.max(newest, reads.get(i));
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", server);
        root.addProperty("stash", stash);
        root.addProperty("player", player);
        root.addProperty("hash", hash);
        root.addProperty("readAt", newest);
        root.add("chests", array);
        return new Built(hash, newest, WIRE.toJson(root).getBytes(StandardCharsets.UTF_8), base.size());
    }

    private static JsonArray items(List<Item> items, int size) {
        List<Item> sorted = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Item i : items) {
            if (i == null || i.slot() < 0 || i.slot() >= size || i.count() <= 0 || i.id() == null || !seen.add(i.slot())) continue;
            sorted.add(i);
        }
        sorted.sort(java.util.Comparator.comparingInt(Item::slot));
        JsonArray out = new JsonArray();
        for (Item i : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("slot", i.slot());
            o.addProperty("id", i.id());
            o.addProperty("name", cut(i.name()));
            o.addProperty("count", Math.min(MAX_COUNT, i.count()));
            o.addProperty("maxStack", Math.max(1, i.maxStack()));
            if (i.inside() != null) {
                o.addProperty("shulker", true);
                JsonArray contents = new JsonArray();
                for (Inner in : group(i.inside())) {
                    JsonObject c = new JsonObject();
                    c.addProperty("id", in.id());
                    c.addProperty("count", in.count());
                    contents.add(c);
                }
                o.add("contents", contents);
            }
            if (i.damage() > 0 && i.maxDamage() > 0) {
                o.addProperty("damage", i.damage());
                o.addProperty("maxDamage", i.maxDamage());
            }
            out.add(o);
        }
        return out;
    }

    /** The stacks of a shulker grouped by id (first seen first), at most {@link #MAX_INSIDE} entries. */
    public static List<Inner> group(List<Inner> stacks) {
        Map<String, Integer> sums = new LinkedHashMap<>();
        for (Inner s : stacks) {
            if (s == null || s.id() == null || s.count() <= 0) continue;
            sums.merge(s.id(), s.count(), Integer::sum);
        }
        List<Inner> out = new ArrayList<>();
        for (var e : sums.entrySet()) {
            if (out.size() >= MAX_INSIDE) break;
            out.add(new Inner(e.getKey(), Math.min(MAX_COUNT, e.getValue())));
        }
        return out;
    }

    /** Control characters stripped, at most {@link #MAX_NAME} characters, ending in an ellipsis when cut (never half of a surrogate pair). */
    public static String cut(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String s = sb.toString();
        if (s.length() <= MAX_NAME) return s;
        int end = MAX_NAME - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * When the report goes out: at most one build every {@link #DEBOUNCE_MS}, and a build is sent only when its hash differs from the
     * last one the server took, or the server asked (the heartbeat's {@code needDelivery}).
     */
    /**
     * When "Delivery report sent: N chests" goes to the log: the first success, the first success after a failure, and otherwise at
     * most once every {@link #INTERVAL_MS}. No positions or names are ever logged.
     */
    public static final class SentLog {
        public static final long INTERVAL_MS = 60_000;
        private long lastLogged = Long.MIN_VALUE;
        private boolean failedSince = true;

        /** A report was taken by the server: true when the line should be logged now. */
        public synchronized boolean sent(long now) {
            if (failedSince || now - lastLogged >= INTERVAL_MS) {
                failedSince = false;
                lastLogged = now;
                return true;
            }
            return false;
        }

        /** A send failed (offline, refused, or an error reply). */
        public synchronized void failed() {
            failedSince = true;
        }

        public static String line(int chests) {
            return "Delivery report sent: " + chests + (chests == 1 ? " chest" : " chests");
        }
    }

    public static final class Sync {
        public static final long DEBOUNCE_MS = 5_000;
        /** The retry timer of the delivery report alone: it never feeds the module's shared backoff. */
        public static final long RETRY_MIN_MS = 60_000, RETRY_MAX_MS = 600_000;

        private boolean dirty = true, asked, built, enabled;
        private long lastBuildAt, retryAt, retry;
        private String sentHash, rejectedHash;

        /**
         * A heartbeat reply came back. A server that knows the delivery report always has {@code needDelivery} in it (even false);
         * until one has, nothing is sent (an older server would answer the report with an unsigned 404).
         */
        public void onHeartbeatReply(String body) {
            try {
                var e = com.google.gson.JsonParser.parseString(body);
                if (!e.isJsonObject() || !e.getAsJsonObject().has("needDelivery")) return;
                enabled = true;
                if (e.getAsJsonObject().get("needDelivery").getAsBoolean()) asked = true;
            } catch (RuntimeException ex) {
                // Not a reply of a server that knows it
            }
        }

        /** The server has said it takes delivery reports. */
        public boolean enabled() {
            return enabled;
        }

        /** Whether a report may go out now: the server takes them and the retry timer has run out. */
        public boolean canSend(long now) {
            return enabled && now >= retryAt;
        }

        /** A delivery request failed in any way (offline, signature, 404, 5xx...): tried again later, 60 s doubling to 10 minutes. */
        public void retryLater(long now) {
            retry = retry == 0 ? RETRY_MIN_MS : Math.min(RETRY_MAX_MS, retry * 2);
            retryAt = now + retry;
        }

        /** The server refused this report for good (400, 413): that hash is not sent again, even when asked, until it changes. */
        public void rejected(String hash) {
            rejectedHash = hash;
            asked = false;
        }

        /** A change in the delivery chests is noticed. */
        public void changed() {
            dirty = true;
        }

        /** The server wants the report ({@code needDelivery}). */
        public void ask() {
            asked = true;
        }

        public boolean due(long now) {
            return (dirty || asked) && (!built || now - lastBuildAt >= DEBOUNCE_MS);
        }

        /** The report was built: @return whether to send it. */
        public boolean built(String hash, long now) {
            built = true;
            dirty = false;
            lastBuildAt = now;
            if (hash.equals(rejectedHash)) {
                asked = false;
                return false;
            }
            return asked || !hash.equals(sentHash);
        }

        /** There is nothing to report (no delivery container): nothing is wanted either. */
        public void none(long now) {
            built = true;
            dirty = false;
            asked = false;
            lastBuildAt = now;
            sentHash = null;
        }

        /** The server refused the stash name (409): the sent hash is forgotten, so the report goes out again. */
        public void forget() {
            sentHash = null;
            dirty = true;
        }

        /** The server took the report. */
        public void sent(String hash) {
            sentHash = hash;
            asked = false;
            retry = 0;
            retryAt = 0;
        }

        public void reset() {
            dirty = true;
            asked = false;
            built = false;
            enabled = false;
            lastBuildAt = 0;
            retryAt = 0;
            retry = 0;
            sentHash = null;
            rejectedHash = null;
        }
    }
}
