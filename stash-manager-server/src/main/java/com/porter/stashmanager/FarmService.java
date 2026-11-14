package com.porter.stashmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Farm records and the farm half of {@code tasks/sync} (FARM-CONTRACT E1 to E4). Every method takes the {@link TaskService}
 * monitor, so farm, task and profile changes are one consistent step. No farm field holds a coordinate; the only text a
 * person types is the name (rules P7), and names stay out of tasks, audit entries and anything a non-owner can read.
 */
final class FarmService {
    static final int MAX_LIST = 16;
    static final int NAME_MAX = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TaskService svc;
    private final FarmStore farms;

    FarmService(TaskService svc) {
        this.svc = svc;
        this.farms = svc.farms;
    }

    // ---------- the farmer object of a sync (E2) ----------

    /** What a sync said about the farmer, validated. {@code programs} is null when the key was absent, {@code modes} too. */
    record FarmerIn(boolean enabled, boolean acceptUploads, String version, List<String> modes, long farmsRev, List<String> forgot,
                    List<JsonObject> programs, boolean draftPresent, JsonObject draft) {
    }

    static FarmerIn parseFarmer(JsonObject body) {
        JsonObject f = Fail.object(body.get("farmer"), "farmer");
        boolean enabled = Fail.bool(f.get("enabled"), "farmer.enabled");
        boolean accept = Fail.present(f, "acceptUploads") && Fail.bool(f.get("acceptUploads"), "farmer.acceptUploads");
        String version = Fail.present(f, "version") ? Fail.string(f.get("version"), "farmer.version", FarmVocab.VERSION, 32) : null;
        List<String> modes = null;
        if (Fail.present(f, "modes")) {
            if (!f.get("modes").isJsonArray() || f.getAsJsonArray("modes").size() > 12) throw Fail.badField("farmer.modes");
            modes = new ArrayList<>();
            for (JsonElement m : f.getAsJsonArray("modes")) {
                String s = Fail.string(m, "farmer.modes", null, 32);
                if (!FarmVocab.MODES.contains(s)) throw Fail.badField("farmer.modes");
                modes.add(s);
            }
        }
        long farmsRev = Fail.present(f, "farmsRev") ? Fail.integer(f.get("farmsRev"), "farmer.farmsRev", 0, FarmVocab.MAX_INT) : 0;
        List<String> forgot = new ArrayList<>();
        if (Fail.present(f, "forgot")) {
            if (!f.get("forgot").isJsonArray() || f.getAsJsonArray("forgot").size() > 64) throw Fail.badField("farmer.forgot");
            for (JsonElement id : f.getAsJsonArray("forgot")) forgot.add(Fail.string(id, "farmer.forgot", FarmVocab.FARM_ID, 10));
        }
        List<JsonObject> programs = null;
        if (body.has("programs") && !body.get("programs").isJsonNull()) {
            if (!body.get("programs").isJsonArray() || body.getAsJsonArray("programs").size() > 32) throw Fail.badField("programs");
            programs = new ArrayList<>();
            for (JsonElement e : body.getAsJsonArray("programs")) {
                JsonObject p = Fail.object(e, "programs");
                JsonObject c = new JsonObject();
                c.addProperty("farm", Fail.string(p.get("farm"), "programs", FarmVocab.FARM_ID, 10));
                copyProgram(p, c, "programs");
                programs.add(c);
            }
        }
        boolean draftPresent = body.has("draft");
        JsonObject draft = null;
        if (draftPresent && !body.get("draft").isJsonNull()) {
            draft = new JsonObject();
            copyProgram(Fail.object(body.get("draft"), "draft"), draft, "draft");
        }
        return new FarmerIn(enabled, accept, version, modes, farmsRev, forgot, programs, draftPresent, draft);
    }

    private static void copyProgram(JsonObject in, JsonObject out, String field) {
        out.addProperty("hash", Fail.string(in.get("hash"), field, FarmVocab.HASH, 64));
        String mode = Fail.string(in.get("mode"), field, null, 32);
        if (!FarmVocab.MODES.contains(mode)) throw Fail.badField(field);
        out.addProperty("mode", mode);
        for (String k : new String[]{"ticks", "loop", "anchors"}) out.addProperty(k, Fail.integer(in.get(k), field, 0, FarmVocab.MAX_INT));
        if (field.equals("programs") || in.has("unsupported")) {
            out.addProperty("unsupported", Fail.present(in, "unsupported") ? Fail.integer(in.get("unsupported"), field, 0, FarmVocab.MAX_INT) : 0);
        }
    }

    /** Applies a validated farmer object to the profile and the farms (lock held by the caller). */
    static void applyFarmer(TaskService svc, OpsStore.Profile p, FarmerIn in, long now) {
        FarmStore farms = svc.farms;
        if (in.enabled()) svc.markFarmerSeen(p);
        JsonObject f = new JsonObject();
        f.addProperty("enabled", in.enabled());
        f.addProperty("acceptUploads", in.acceptUploads());
        f.addProperty("version", in.version());
        if (in.modes() != null) {
            JsonArray m = new JsonArray();
            in.modes().forEach(m::add);
            f.add("modes", m);
        }
        f.addProperty("farmsRev", in.farmsRev());
        p.farmer = f;
        if (in.draftPresent()) p.draft = in.draft();
        if (in.programs() != null) {
            JsonArray pl = new JsonArray();
            in.programs().forEach(pl::add);
            p.programs = pl;
        }
        if (farms.disabled()) return;
        boolean changed = false;
        if (in.programs() != null) {
            Map<String, JsonObject> byFarm = new java.util.HashMap<>();
            for (JsonObject e : in.programs()) byFarm.put(e.get("farm").getAsString(), e);
            for (FarmStore.Farm farm : farms.farms) {
                if (!p.botId.equals(farm.botId)) continue; // a program for a farm assigned to another bot is ignored
                JsonObject e = byFarm.get(farm.id);
                if (e != null) {
                    farm.missing = 0;
                    if (farm.program == null || !farm.program.hash.equals(e.get("hash").getAsString())
                        || !farm.program.mode.equals(e.get("mode").getAsString())) {
                        FarmStore.Program pr = new FarmStore.Program();
                        pr.hash = e.get("hash").getAsString();
                        pr.mode = e.get("mode").getAsString();
                        pr.at = now;
                        pr.ticks = e.get("ticks").getAsLong();
                        pr.loop = e.get("loop").getAsLong();
                        pr.anchors = e.get("anchors").getAsLong();
                        pr.unsupported = e.get("unsupported").getAsLong();
                        farm.program = pr;
                        farm.updatedAt = now;
                        changed = true;
                    }
                } else if (farm.program != null && ++farm.missing >= 2) {
                    farm.program = null;
                    farm.missing = 0;
                    farm.updatedAt = now;
                    changed = true;
                }
            }
            FarmStore.Attach a = farms.pendingAttach.get(p.botId);
            if (a != null) {
                JsonObject e = byFarm.get(a.farm);
                if ((e != null && e.get("hash").getAsString().equals(a.draftHash)) || farms.get(a.farm) == null) {
                    farms.pendingAttach.remove(p.botId);
                    changed = true;
                }
            }
        }
        List<String> pending = farms.pendingForget.get(p.botId);
        if (pending != null) {
            for (String id : in.forgot()) {
                if (pending.remove(id)) {
                    farms.forgetAt.remove(id);
                    changed = true;
                }
            }
            if (pending.removeIf(id -> now - farms.forgetAt.getOrDefault(id, now) >= FarmStore.FORGET_MS)) changed = true;
            if (pending.isEmpty()) {
                farms.pendingForget.remove(p.botId);
                changed = true;
            }
        }
        if (changed) svc.dirtyFarms();
    }

    /** The farm additions of a sync reply: farmsRev, farms (only when the bot is behind), forget and attach. */
    static void syncReply(TaskService svc, OpsStore.Profile p, FarmerIn in, JsonObject out) {
        FarmStore farms = svc.farms;
        out.addProperty("farmsRev", farms.rev);
        if (farms.disabled() || in.farmsRev() >= farms.rev) {
            out.add("farms", JsonNull.INSTANCE);
        } else {
            JsonArray list = new JsonArray();
            for (FarmStore.Farm f : farms.farms) {
                if (!p.botId.equals(f.botId)) continue;
                JsonObject j = new JsonObject();
                JsonObject full = f.toJson();
                for (String k : new String[]{"id", "name", "mode", "enabled", "session", "thresholds", "travel"}) j.add(k, full.get(k));
                list.add(j);
            }
            out.add("farms", list);
        }
        JsonArray forget = new JsonArray();
        List<String> pending = farms.pendingForget.get(p.botId);
        if (pending != null) pending.forEach(forget::add);
        out.add("forget", forget);
        FarmStore.Attach a = farms.pendingAttach.get(p.botId);
        if (a == null) {
            out.add("attach", JsonNull.INSTANCE);
        } else {
            JsonObject j = new JsonObject();
            j.addProperty("farm", a.farm);
            j.addProperty("draftHash", a.draftHash);
            out.add("attach", j);
        }
    }

    // ---------- views ----------

    private void available() {
        if (farms.disabled()) throw new Fail(503, "farms_unavailable");
    }

    private OpsStore.Profile farmerBot() {
        OpsStore.Profile best = null;
        for (OpsStore.Profile p : svc.ops.bots.values()) {
            if (p.hasRole("farmer") && (best == null || p.botId.compareTo(best.botId) < 0)) best = p;
        }
        return best;
    }

    private JsonObject farmJson(FarmStore.Farm f, long now) {
        JsonObject j = f.toJson();
        TaskStore.Task t = svc.openSessionOf(f.id);
        OpsStore.Profile p = f.botId == null ? null : svc.ops.bots.get(f.botId);
        JsonObject s = new JsonObject();
        s.addProperty("state", t == null ? "idle" : t.state);
        s.addProperty("phase", t == null ? null : t.phase);
        long loops = t == null ? 0 : t.progressDone;
        if (t != null && t.checkpoint != null && t.checkpoint.getAsJsonObject("data").has("loop")) {
            loops = t.checkpoint.getAsJsonObject("data").get("loop").getAsLong();
        }
        s.addProperty("loops", loops);
        s.addProperty("pauseReason", t == null ? null : t.pauseReason);
        s.addProperty("taskId", t == null ? null : t.id);
        s.addProperty("farmerOnline", p != null && svc.online(p, now));
        j.add("status", s);
        return j;
    }

    /** GET /api/v1/farms (and the body of every write's answer). */
    JsonObject list() {
        synchronized (svc) {
            available();
            long now = svc.now();
            svc.housekeeping(now);
            try {
                JsonObject out = new JsonObject();
                JsonArray list = new JsonArray();
                for (FarmStore.Farm f : farms.farms) list.add(farmJson(f, now));
                out.add("farms", list);
                OpsStore.Profile fb = farmerBot();
                out.add("draft", fb == null || fb.draft == null ? JsonNull.INSTANCE : fb.draft.deepCopy());
                if (fb == null) {
                    out.add("farmer", JsonNull.INSTANCE);
                } else {
                    JsonObject f = new JsonObject();
                    f.addProperty("botId", fb.botId);
                    f.addProperty("online", svc.online(fb, now));
                    JsonArray modes = new JsonArray();
                    if (fb.farmer != null && fb.farmer.get("modes") instanceof JsonArray m) modes = m.deepCopy();
                    f.add("modes", modes);
                    f.addProperty("acceptUploads", fb.farmer != null && fb.farmer.get("acceptUploads") instanceof com.google.gson.JsonPrimitive a
                        && a.isBoolean() && a.getAsBoolean());
                    f.addProperty("version", fb.farmer != null && fb.farmer.get("version") instanceof com.google.gson.JsonPrimitive v
                        && v.isString() ? v.getAsString() : null);
                    out.add("farmer", f);
                }
                out.addProperty("rev", farms.rev);
                JsonObject limits = new JsonObject();
                limits.addProperty("maxFarms", FarmStore.MAX_FARMS);
                limits.addProperty("maxList", MAX_LIST);
                limits.addProperty("nameMax", NAME_MAX);
                limits.addProperty("nameMaxDigits", 2);
                limits.addProperty("maxMsMax", 43_200_000L);
                limits.addProperty("maxMsMin", 60_000L);
                out.add("limits", limits);
                return out;
            } finally {
                svc.tryFlush();
            }
        }
    }

    // ---------- create and edit ----------

    record Saved(FarmStore.Farm farm, boolean created) {
    }

    private static final java.util.Set<String> RESERVED_KEYS = java.util.Set.of("program", "lastSession", "createdAt", "updatedAt");

    /** Name rules (P7): 1..32 characters trimmed, no section sign, no control character, fewer than 3 digits. */
    static String name(JsonElement e) {
        // NFKC first (full-width digits become digits, a no-break space a space), then every check on the normalised text.
        String s = java.text.Normalizer.normalize(Fail.string(e, "name", null, 200), java.text.Normalizer.Form.NFKC).strip();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > NAME_MAX) throw Fail.badField("name");
        int digits = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            // Control, format (zero-width and bidi controls), line and paragraph separators, lone surrogates, private use, unassigned.
            if (cp == 0xA7 || type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED) {
                throw Fail.badField("name");
            }
            if (Character.isDigit(cp)) digits++;
        }
        if (digits >= 3 || PositionGuard.looksLikePosition(s)) throw Fail.badField("name");
        return s;
    }

    Saved save(JsonObject body) {
        return svc.transaction(() -> {
                available();
                long now = svc.now();
                for (String k : RESERVED_KEYS) if (body.has(k)) throw new Fail(400, "reserved_field", k);
                for (String k : new String[]{"dropStash", "supplyStash", "schedule"}) {
                    if (Fail.present(body, k)) throw new Fail(400, "reserved_field", k);
                }
                boolean edit = Fail.present(body, "id");
                FarmStore.Farm old = null;
                FarmStore.Farm w;
                if (edit) {
                    String id = Fail.string(body.get("id"), "id", FarmVocab.FARM_ID, 10);
                    old = farms.get(id);
                    if (old == null) throw new Fail(404, "no_farm");
                    long rev = Fail.integer(body.get("rev"), "rev", 1, Long.MAX_VALUE / 2);
                    if (rev != old.rev) {
                        JsonObject extra = new JsonObject();
                        extra.add("farm", farmJson(old, now));
                        throw new Fail(409, "rev_conflict", null, 0, extra);
                    }
                    w = StateFile.STORE.fromJson(StateFile.STORE.toJsonTree(old), FarmStore.Farm.class);
                    w.missing = old.missing;
                } else {
                    if (farms.farms.size() >= FarmStore.MAX_FARMS) throw new Fail(409, "farm_limit");
                    w = new FarmStore.Farm();
                    w.createdAt = now;
                }
                // name
                if (Fail.present(body, "name")) w.name = name(body.get("name"));
                else if (!edit) throw Fail.badField("name");
                for (FarmStore.Farm other : farms.farms) {
                    if (other != old && other.name.toLowerCase(Locale.ROOT).equals(w.name.toLowerCase(Locale.ROOT))) throw new Fail(409, "name_in_use");
                }
                // server (set at creation, immutable)
                if (Fail.present(body, "server")) {
                    String s = TaskService.serverName(body.get("server"), "server");
                    if (edit && !s.equals(old.server)) throw Fail.badField("server");
                    w.server = s;
                } else if (!edit) {
                    throw Fail.badField("server");
                }
                if (Fail.present(body, "dim")) w.dim = Fail.string(body.get("dim"), "dim", FarmVocab.NAMESPACED, 64);
                if (Fail.present(body, "mode")) {
                    String m = Fail.string(body.get("mode"), "mode", null, 32);
                    if (!FarmVocab.MODES.contains(m)) throw Fail.badField("mode");
                    w.mode = m;
                } else if (!edit) {
                    throw Fail.badField("mode");
                }
                // botId
                String botId = Fail.present(body, "botId") ? Fail.string(body.get("botId"), "botId", FarmVocab.BOT_ID, 64) : null;
                if (botId == null && !edit) {
                    List<String> candidates = new ArrayList<>();
                    for (OpsStore.Profile p : svc.ops.bots.values()) if (p.hasRole("farmer")) candidates.add(p.botId);
                    if (candidates.size() != 1) throw Fail.badField("botId");
                    botId = candidates.get(0);
                }
                if (botId != null && (!edit || !botId.equals(old.botId))) {
                    OpsStore.Profile p = svc.ops.bots.get(botId);
                    if (p == null || !p.hasRole("farmer")) throw Fail.badField("botId");
                    if (edit && svc.openSessionOf(old.id) != null) throw new Fail(409, "session_open");
                    w.botId = botId;
                }
                if (Fail.present(body, "enabled")) w.enabled = Fail.bool(body.get("enabled"), "enabled");
                if (Fail.present(body, "offlineOk")) w.offlineOk = Fail.bool(body.get("offlineOk"), "offlineOk");
                if (body.has("produces")) w.produces = produces(body.get("produces"));
                if (body.has("needs")) w.needs = needs(body.get("needs"));
                if (Fail.present(body, "session")) session(body.get("session"), w.session);
                if (Fail.present(body, "thresholds")) thresholds(body.get("thresholds"), w.thresholds);
                if (Fail.present(body, "travel")) travel(body.get("travel"), w.travel);
                if (w.travel.preset.equals("pearl_both") && w.travel.station == null) throw Fail.badField("travel.station");
                if (w.session.minMs > w.session.maxMs) throw Fail.badField("session");
                // commit
                w.updatedAt = now;
                if (edit) {
                    w.rev = old.rev + 1;
                    farms.farms.set(farms.farms.indexOf(old), w);
                } else {
                    w.id = newFarmId();
                    w.rev = 1;
                    farms.farms.add(w);
                }
                farms.rev++;
                svc.dirtyFarms();
                return new Saved(w, !edit);
        });
    }

    private String newFarmId() {
        while (true) {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            String id = "f-" + HexFormat.of().formatHex(b);
            if (farms.get(id) == null) return id;
        }
    }

    private static List<FarmStore.Produce> produces(JsonElement e) {
        if (e.isJsonNull()) return new ArrayList<>();
        if (!e.isJsonArray() || e.getAsJsonArray().size() > MAX_LIST) throw Fail.badField("produces");
        List<FarmStore.Produce> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            JsonObject o = Fail.object(x, "produces");
            FarmStore.Produce p = new FarmStore.Produce();
            p.key = Fail.string(o.get("key"), "produces", FarmVocab.ITEM_KEY, 64);
            p.perHour = Fail.integer(o.get("perHour"), "produces", 0, 1_000_000);
            out.add(p);
        }
        return out;
    }

    private static List<FarmStore.Need> needs(JsonElement e) {
        if (e.isJsonNull()) return new ArrayList<>();
        if (!e.isJsonArray() || e.getAsJsonArray().size() > MAX_LIST) throw Fail.badField("needs");
        List<FarmStore.Need> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            JsonObject o = Fail.object(x, "needs");
            FarmStore.Need n = new FarmStore.Need();
            n.key = Fail.string(o.get("key"), "needs", FarmVocab.ITEM_KEY, 64);
            n.min = Fail.integer(o.get("min"), "needs", 0, 100_000);
            n.max = Fail.integer(o.get("max"), "needs", 0, 100_000);
            if (n.min > n.max) throw Fail.badField("needs");
            out.add(n);
        }
        return out;
    }

    private static void session(JsonElement e, FarmStore.Session s) {
        JsonObject o = Fail.object(e, "session");
        if (Fail.present(o, "minMs")) s.minMs = Fail.integer(o.get("minMs"), "session", 0, 43_200_000);
        if (Fail.present(o, "maxMs")) s.maxMs = Fail.integer(o.get("maxMs"), "session", 60_000, 43_200_000);
    }

    private static void thresholds(JsonElement e, FarmStore.Thresholds t) {
        JsonObject o = Fail.object(e, "thresholds");
        if (Fail.present(o, "healthStop")) t.healthStop = (int) Fail.integer(o.get("healthStop"), "thresholds", 1, 19);
        if (Fail.present(o, "food")) t.food = (int) Fail.integer(o.get("food"), "thresholds", 0, 19);
        if (Fail.present(o, "totems")) t.totems = (int) Fail.integer(o.get("totems"), "thresholds", 0, 64);
        if (Fail.present(o, "playerRadius")) t.playerRadius = (int) Fail.integer(o.get("playerRadius"), "thresholds", 8, 64);
        if (Fail.present(o, "playerSeconds")) t.playerSeconds = (int) Fail.integer(o.get("playerSeconds"), "thresholds", 1, 120);
        if (Fail.present(o, "invFullPct")) t.invFullPct = (int) Fail.integer(o.get("invFullPct"), "thresholds", 50, 100);
    }

    private static final List<String> PRESETS = List.of("pearl_both", "walk", "nether_elytra", "pitch40_walk", "out_then_pearl_home");
    private static final List<String> LEGS = List.of("walk", "pearl", "nether_elytra", "pitch40_walk");

    private static void travel(JsonElement e, FarmStore.Travel t) {
        JsonObject o = Fail.object(e, "travel");
        if (Fail.present(o, "route")) throw new Fail(400, "reserved_field", "travel.route");
        if (Fail.present(o, "preset")) {
            String s = Fail.string(o.get("preset"), "travel.preset", null, 32);
            if (!PRESETS.contains(s)) throw Fail.badField("travel.preset");
            t.preset = s;
        }
        for (String leg : new String[]{"out", "back"}) {
            if (!Fail.present(o, leg)) continue;
            String s = Fail.string(o.get(leg), "travel." + leg, null, 32);
            if (!LEGS.contains(s)) throw Fail.badField("travel." + leg);
            if (leg.equals("out")) t.out = s;
            else t.back = s;
        }
        if (o.has("station")) {
            t.station = o.get("station").isJsonNull() ? null : Fail.string(o.get("station"), "travel.station", FarmVocab.STATION_ID, 11);
        }
    }

    // ---------- delete, start, stop, attach ----------

    /** Deletes a farm (409 session_open while a session is open) and queues the forget for the farmer. */
    FarmStore.Farm delete(String id) {
        return svc.transaction(() -> {
                available();
                long now = svc.now();
                svc.housekeeping(now);
                FarmStore.Farm f = farms.get(id);
                if (f == null) throw new Fail(404, "no_farm");
                if (svc.openSessionOf(id) != null) throw new Fail(409, "session_open");
                farms.farms.remove(f);
                if (f.botId != null) {
                    List<String> l = farms.pendingForget.computeIfAbsent(f.botId, k -> new ArrayList<>());
                    if (!l.contains(id)) l.add(id);
                    farms.forgetAt.put(id, now);
                }
                farms.pendingAttach.values().removeIf(a -> id.equals(a.farm));
                farms.rev++;
                svc.dirtyFarms();
                return f;
        });
    }

    /** Starts a session (a farm.session task). Refusals are the E3 start refusals, in the contract's order. */
    TaskStore.Task start(String id, Long maxMs, Users.Principal who) {
        return svc.transaction(() -> {
                available();
                long now = svc.now();
                svc.housekeeping(now);
                FarmStore.Farm f = farms.get(id);
                if (f == null) throw new Fail(404, "no_farm");
                OpsStore.Profile p = f.botId == null ? null : svc.ops.bots.get(f.botId);
                if (f.program == null) throw new Fail(409, "no_program");
                if (p == null || !p.hasRole("farmer")) throw new Fail(409, "no_farmer");
                if (p.lastSync == 0 || now - p.lastSync > TaskService.ONLINE_MS) throw new Fail(409, "farmer_offline");
                if (svc.stopApplies(p)) throw new Fail(409, "stopped");
                if (svc.openSessionOf(id) != null) throw new Fail(409, "busy");
                if (svc.openTaskOf(p.botId) != null) throw new Fail(409, "farmer_busy");
                if (!f.enabled) throw new Fail(409, "disabled");
                boolean reported = p.farmer != null && p.farmer.get("modes") instanceof JsonArray;
                if (!FarmVocab.M2_MODES.contains(f.mode) || (reported && !contains(p.farmer.getAsJsonArray("modes"), f.mode))) {
                    throw new Fail(409, "mode_not_ready");
                }
                if (!f.mode.equals(f.program.mode)) throw new Fail(409, "program_mode_mismatch");
                TaskStore.Task t = new TaskStore.Task();
                t.id = svc.newTaskId();
                t.minRunMs = f.session.minMs;
                t.server = f.server;
                t.botId = p.botId;
                t.farmId = f.id;
                t.params.addProperty("farm", f.id);
                t.params.addProperty("programHash", f.program.hash);
                t.params.addProperty("maxMs", maxMs != null ? maxMs : f.session.maxMs);
                t.params.addProperty("origin", "website");
                t.createdById = who.id();
                t.createdByName = who.name();
                t.createdAt = now;
                t.updatedAt = now;
                svc.tasks.tasks.add(t);
                svc.dirtyTasks();
                return t;
        });
    }

    private static boolean contains(JsonArray a, String s) {
        for (JsonElement e : a) if (e.getAsString().equals(s)) return true;
        return false;
    }

    /** Cancels the open session of a farm (cancelled, "stopped"); the farmer hears it in its next sync's cancel list. */
    TaskStore.Task stopSession(String id) {
        return svc.transaction(() -> {
                available();
                long now = svc.now();
                svc.housekeeping(now);
                if (farms.get(id) == null) throw new Fail(404, "no_farm");
                TaskStore.Task t = svc.openSessionOf(id);
                if (t == null) throw new Fail(409, "no_session");
                svc.finish(t, "cancelled", "stopped", null, now);
                return t;
        });
    }

    /** Orders the farmer to bind its draft to this farm (it does so at its next sync). */
    void attach(String id) {
        svc.transaction(() -> {
                available();
                long now = svc.now();
                svc.housekeeping(now);
                FarmStore.Farm f = farms.get(id);
                if (f == null) throw new Fail(404, "no_farm");
                OpsStore.Profile p = f.botId == null ? null : svc.ops.bots.get(f.botId);
                if (p == null) throw new Fail(409, "no_farmer");
                if (svc.openSessionOf(id) != null) throw new Fail(409, "busy");
                if (p.draft == null) throw new Fail(409, "no_draft");
                FarmStore.Attach a = new FarmStore.Attach();
                a.farm = id;
                a.draftHash = p.draft.get("hash").getAsString();
                a.at = now;
                farms.pendingAttach.put(p.botId, a);
                svc.dirtyFarms();
                return null;
        });
    }
}
