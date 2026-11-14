package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.ClickCompiler.Click;
import com.porter.qwerte.core.farm.ClickCompiler.TickState;
import com.porter.qwerte.core.farm.FarmRecording.Anchor;
import com.porter.qwerte.core.farm.FarmRecording.Container;
import com.porter.qwerte.core.farm.FarmRecording.ContainerAction;
import com.porter.qwerte.core.farm.FarmRecording.Fp;
import com.porter.qwerte.core.farm.FarmRecording.Header;
import com.porter.qwerte.core.farm.FarmRecording.Hotbar;
import com.porter.qwerte.core.farm.FarmRecording.Look;
import com.porter.qwerte.core.farm.FarmRecording.Mark;
import com.porter.qwerte.core.farm.FarmRecording.Rel;
import com.porter.qwerte.core.farm.FarmRecording.Sample;
import com.porter.qwerte.core.farm.FarmRecording.Steps;
import com.porter.qwerte.core.farm.FarmRecording.Target;
import com.porter.qwerte.core.farm.FarmRecording.Template;
import com.porter.qwerte.core.farm.FarmRecording.TrackRecord;
import com.porter.qwerte.core.farm.FarmRecording.Unsupported;
import com.porter.qwerte.core.farm.FarmRecording.Use;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Runs every implementation-checkable case of farm-vectors.json against the core. */
class FarmVectorsTest {
    private static final JsonObject V = Vectors.load();

    // ---------- model conversion from the vector JSON ----------

    private static int[] ints(JsonArray a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsInt();
        }
        return r;
    }

    private static Rel rel(JsonArray a) {
        int[] i = ints(a);
        return new Rel(i[0], i[1], i[2]);
    }

    private static Vec vec(JsonArray a) {
        return new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    private static List<String> strings(JsonArray a) {
        List<String> l = new ArrayList<>();
        a.forEach(e -> l.add(e.getAsString()));
        return l;
    }

    private static Object plain(JsonElement e) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isString()) {
                return p.getAsString();
            }
            String s = p.getAsString();
            return s.contains(".") ? (Object) Double.valueOf(s) : (Object) Long.valueOf(s);
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            e.getAsJsonArray().forEach(x -> l.add(plain(x)));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        e.getAsJsonObject().entrySet().forEach(en -> m.put(en.getKey(), plain(en.getValue())));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Header header(JsonObject h) {
        JsonObject lp = h.getAsJsonObject("loop");
        List<Anchor> anchors = new ArrayList<>();
        for (JsonElement ae : h.getAsJsonArray("anchors")) {
            JsonObject a = ae.getAsJsonObject();
            JsonObject fp = a.getAsJsonObject("fp");
            anchors.add(new Anchor(a.get("id").getAsString(), rel(a.getAsJsonArray("rel")), vec(a.getAsJsonArray("stand")),
                    a.get("yaw").getAsDouble(), a.get("pitch").getAsDouble(), a.get("tol").getAsDouble(),
                    new Fp(fp.get("below").getAsString(), fp.get("feet").getAsString()), a.get("tick").getAsInt()));
        }
        List<Mark> marks = new ArrayList<>();
        for (JsonElement me : h.getAsJsonArray("marks")) {
            JsonObject m = me.getAsJsonObject();
            marks.add(new Mark(m.get("kind").getAsString(), m.get("tick").getAsInt(),
                    m.has("container") ? m.get("container").getAsInt() : null));
        }
        List<Container> containers = new ArrayList<>();
        for (JsonElement ce : h.getAsJsonArray("containers")) {
            JsonObject c = ce.getAsJsonObject();
            containers.add(new Container(rel(c.getAsJsonArray("rel")), c.get("block").getAsString(),
                    c.get("role").getAsString()));
        }
        JsonObject tpl = h.getAsJsonObject("template");
        JsonObject st = h.getAsJsonObject("steps");
        List<Map<String, Object>> start = new ArrayList<>();
        st.getAsJsonArray("start").forEach(e -> start.add((Map<String, Object>) plain(e)));
        List<Map<String, Object>> stop = new ArrayList<>();
        st.getAsJsonArray("stop").forEach(e -> stop.add((Map<String, Object>) plain(e)));
        List<Unsupported> un = new ArrayList<>();
        for (JsonElement ue : h.getAsJsonArray("unsupported")) {
            un.add(new Unsupported(ue.getAsJsonObject().get("code").getAsString(), ue.getAsJsonObject().get("tick").getAsInt()));
        }
        return new Header(h.get("format").getAsInt(), h.get("core").getAsString(), h.get("mcClient").getAsString(),
                h.get("mcProxy").getAsString(), h.get("mode").getAsString(), h.get("dim").getAsString(),
                new Template(tpl.get("mode").getAsString(), (Map<String, Object>) plain(tpl.get("params"))),
                h.get("ticks").getAsInt(), new FarmRecording.Loop(lp.get("start").getAsInt(), lp.get("end").getAsInt(),
                        lp.get("repeat").getAsBoolean()),
                anchors, marks, containers, new Steps(start, stop), strings(h.getAsJsonArray("items")),
                strings(h.getAsJsonArray("entities")), strings(h.getAsJsonArray("blocks")), un);
    }

    private static final Map<String, Integer> KEY_BITS = Map.of("forward", 1, "back", 2, "left", 4, "right", 8, "jump", 16,
            "sneak", 32, "sprint", 64);

    private static List<TrackRecord> track(JsonArray a) {
        List<TrackRecord> out = new ArrayList<>();
        for (JsonElement e : a) {
            JsonObject r = e.getAsJsonObject();
            Integer keys = null;
            if (r.has("keys")) {
                int k = 0;
                for (JsonElement n : r.getAsJsonArray("keys")) {
                    k |= KEY_BITS.get(n.getAsString());
                }
                keys = k;
            }
            Look look = r.has("look") ? new Look(r.getAsJsonArray("look").get(0).getAsFloat(),
                    r.getAsJsonArray("look").get(1).getAsFloat()) : null;
            Integer attack = r.has("attack") ? Integer.valueOf(Map.of("none", 0, "press", 1, "hold", 2)
                    .get(r.get("attack").getAsString())) : null;
            Use use = null;
            if (r.has("use")) {
                JsonObject u = r.getAsJsonObject("use");
                use = new Use(Map.of("none", 0, "press", 1, "hold", 2).get(u.get("mode").getAsString()),
                        u.get("hand").getAsInt());
            }
            Target target = null;
            if (r.has("target")) {
                JsonObject t = r.getAsJsonObject("target");
                switch (t.get("kind").getAsString()) {
                    case "miss" -> target = Target.MISS;
                    case "entity" -> target = Target.entity(t.get("entity").getAsInt());
                    default -> {
                        int[] rl = ints(t.getAsJsonArray("rel"));
                        target = Target.block(rl[0], rl[1], rl[2], t.get("face").getAsInt(), t.get("block").getAsInt());
                    }
                }
            }
            Hotbar hotbar = r.has("hotbar") ? new Hotbar(r.getAsJsonObject("hotbar").get("slot").getAsInt(),
                    r.getAsJsonObject("hotbar").get("item").getAsInt()) : null;
            Sample sample = r.has("sample") ? new Sample(r.getAsJsonArray("sample").get(0).getAsFloat(),
                    r.getAsJsonArray("sample").get(1).getAsFloat(), r.getAsJsonArray("sample").get(2).getAsFloat()) : null;
            ContainerAction ca = null;
            if (r.has("container")) {
                JsonObject c = r.getAsJsonObject("container");
                ca = c.get("op").getAsString().equals("take")
                        ? ContainerAction.take(c.get("container").getAsInt(), c.get("item").getAsInt(), c.get("count").getAsInt())
                        : ContainerAction.put(c.get("container").getAsInt(), c.get("item").getAsInt());
            }
            out.add(new TrackRecord(r.get("tick").getAsInt(), keys, look, attack, use, target, hotbar, sample, ca,
                    r.has("swing") && r.get("swing").getAsBoolean()));
        }
        return out;
    }

    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (FarmException e) {
            return e.code();
        }
    }

    // ---------- recording ----------

    @Test
    void recordingCasesEncodeToTheVectorBytes() {
        for (JsonElement ce : V.getAsJsonObject("recording").getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            String id = c.get("id").getAsString();
            FarmRecording built = FarmRecording.of(header(c.getAsJsonObject("header")), track(c.getAsJsonArray("track")));
            byte[] enc = built.encode();
            assertEquals(c.get("plainHex").getAsString(), Vectors.hex(enc), id + " bytes");
            assertEquals(c.get("plainBytes").getAsInt(), enc.length, id + " length");
            assertEquals(c.get("programHash").getAsString(), built.programHash(), id + " hash");
            assertEquals(c.get("programHash").getAsString(), FarmRecording.sha256Hex(Vectors.hex(c.get("plainHex").getAsString())));
        }
    }

    @Test
    void recordingCasesDecodeAndRoundTrip() {
        for (JsonElement ce : V.getAsJsonObject("recording").getAsJsonArray("cases")) {
            JsonObject c = ce.getAsJsonObject();
            String id = c.get("id").getAsString();
            byte[] bytes = Vectors.hex(c.get("plainHex").getAsString());
            FarmRecording dec = FarmRecording.decode(bytes);
            assertEquals(header(c.getAsJsonObject("header")), dec.header(), id + " header");
            assertEquals(track(c.getAsJsonArray("track")), dec.track(), id + " track");
            assertArrayEquals(bytes, dec.encode(), id + " re-encode");
            ProgramChecker.checkPlain(bytes);
            assertNull(ProgramChecker.verify(bytes), id);
        }
    }

    @Test
    void decodeFailures() {
        for (JsonElement ce : V.getAsJsonObject("recording").getAsJsonArray("decodeFailures")) {
            JsonObject c = ce.getAsJsonObject();
            byte[] bytes = Vectors.hex(c.get("plainHex").getAsString());
            String want = c.get("error").getAsString();
            FarmException ex = assertThrows(FarmException.class, () -> FarmRecording.decode(bytes), c.get("id").getAsString());
            assertEquals(want, ex.code(), c.get("id").getAsString() + " decode");
            assertEquals(want, ProgramChecker.verify(bytes), c.get("id").getAsString() + " verify");
        }
    }

    private static JsonElement filler(String what) {
        switch (what) {
            case "@65_anchors" -> {
                JsonArray a = new JsonArray();
                for (int i = 0; i < 65; i++) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", "a" + i);
                    JsonArray rel = new JsonArray();
                    rel.add(0);
                    rel.add(0);
                    rel.add(0);
                    o.add("rel", rel);
                    JsonArray st = new JsonArray();
                    st.add(0.5);
                    st.add(0.0);
                    st.add(0.5);
                    o.add("stand", st);
                    o.addProperty("yaw", 0.0);
                    o.addProperty("pitch", 0.0);
                    o.addProperty("tol", 0.3);
                    o.addProperty("tick", 0);
                    a.add(o);
                }
                return a;
            }
            case "@257_items", "@33_entities" -> {
                int n = what.equals("@257_items") ? 257 : 33;
                JsonArray a = new JsonArray();
                for (int i = 0; i < n; i++) {
                    a.add("minecraft:thing_" + i);
                }
                return a;
            }
            case "@129_start_steps" -> {
                JsonObject o = new JsonObject();
                JsonArray start = new JsonArray();
                for (int i = 0; i < 129; i++) {
                    JsonObject s = new JsonObject();
                    s.addProperty("op", "wait");
                    s.addProperty("ticks", 1);
                    start.add(s);
                }
                o.add("start", start);
                o.add("stop", new JsonArray());
                return o;
            }
            case "@nest_17" -> {
                JsonElement inner = new JsonArray();
                for (int i = 1; i < 17; i++) {
                    JsonArray w = new JsonArray();
                    w.add(inner);
                    inner = w;
                }
                return inner;
            }
            default -> throw new IllegalStateException(what);
        }
    }

    private static void setPath(JsonObject root, String path, JsonElement value) {
        String[] p = path.split("\\.");
        JsonElement cur = root;
        for (int i = 0; i < p.length - 1; i++) {
            cur = cur.isJsonArray() ? cur.getAsJsonArray().get(Integer.parseInt(p[i])) : cur.getAsJsonObject().get(p[i]);
        }
        String last = p[p.length - 1];
        if (cur.isJsonArray()) {
            cur.getAsJsonArray().set(Integer.parseInt(last), value);
        } else {
            cur.getAsJsonObject().add(last, value);
        }
    }

    private static byte[] withHeader(byte[] base, String headerJson) {
        int hl = ByteBuffer.wrap(base, 5, 4).getInt();
        byte[] hb = headerJson.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[9 + hb.length + (base.length - 9 - hl)];
        System.arraycopy(base, 0, out, 0, 5);
        ByteBuffer.wrap(out, 5, 4).putInt(hb.length);
        System.arraycopy(hb, 0, out, 9, hb.length);
        System.arraycopy(base, 9 + hl, out, 9 + hb.length, base.length - 9 - hl);
        return out;
    }

    @Test
    void checkerFailures() {
        JsonObject walk = null;
        byte[] base = null;
        for (JsonElement ce : V.getAsJsonObject("recording").getAsJsonArray("cases")) {
            if (ce.getAsJsonObject().get("id").getAsString().equals("walk_forward")) {
                walk = ce.getAsJsonObject();
                base = Vectors.hex(walk.get("plainHex").getAsString());
            }
        }
        assertNotNull(walk);
        int n = 0;
        for (JsonElement ce : V.getAsJsonObject("recording").getAsJsonArray("checkerFailures")) {
            JsonObject c = ce.getAsJsonObject();
            assertEquals("walk_forward", c.get("base").getAsString());
            JsonObject h = walk.getAsJsonObject("header").deepCopy();
            JsonObject set = c.getAsJsonObject("set");
            JsonElement v = set.get("value");
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().startsWith("@")) {
                v = filler(v.getAsString());
            }
            setPath(h, set.get("path").getAsString(), v);
            byte[] bytes = withHeader(base, h.toString());
            String id = c.get("id").getAsString();
            if (c.get("error").isJsonNull()) {
                ProgramChecker.checkPlain(bytes); // must not throw
            } else {
                FarmException e = assertThrows(FarmException.class, () -> ProgramChecker.checkPlain(bytes), id);
                assertEquals(c.get("error").getAsString(), e.code(), id);
            }
            n++;
        }
        assertEquals(26, n);
    }

    // ---------- anchor-relative maths ----------

    @Test
    void anchorSplit() {
        for (JsonElement e : V.getAsJsonObject("anchorRelative").getAsJsonArray("split")) {
            JsonObject c = e.getAsJsonObject();
            double[] f = new double[3];
            for (int i = 0; i < 3; i++) {
                f[i] = c.getAsJsonArray("feetOffset").get(i).getAsDouble();
            }
            AnchorMath.Split s = AnchorMath.split(f[0], f[1], f[2]);
            assertEquals(rel(c.getAsJsonArray("rel")), s.rel(), c.toString());
            Vec want = vec(c.getAsJsonArray("stand"));
            assertEquals(want.x(), s.stand().x(), 1e-12);
            assertEquals(want.y(), s.stand().y(), 1e-12);
            assertEquals(want.z(), s.stand().z(), 1e-12);
        }
    }

    @Test
    void anchorChecks() {
        for (JsonElement e : V.getAsJsonObject("anchorRelative").getAsJsonArray("checks")) {
            JsonObject c = e.getAsJsonObject();
            double d = AnchorMath.distance(vec(c.getAsJsonArray("botOffset")), vec(c.getAsJsonArray("anchorOffset")));
            assertEquals(c.get("distance").getAsDouble(), d, 1e-9);
            double tol = c.get("tol").getAsDouble();
            assertEquals(c.get("within").getAsBoolean(), AnchorMath.within(d, tol), c.toString());
            assertEquals(c.get("earlyDesync").getAsBoolean(), AnchorMath.earlyDesync(d, tol), c.toString());
        }
    }

    @Test
    void originCrossCheck() {
        for (JsonElement e : V.getAsJsonObject("anchorRelative").getAsJsonArray("originCrossCheck")) {
            JsonObject c = e.getAsJsonObject();
            int[] d = ints(c.getAsJsonArray("clientMinusPluginBlockView"));
            String err = AnchorMath.originCrossCheck(d[0], d[1], d[2], c.get("sameDimension").getAsBoolean());
            if (c.get("accepted").getAsBoolean()) {
                assertNull(err, c.toString());
            } else {
                assertEquals(c.get("errorIfRejected").getAsString(), err, c.toString());
            }
        }
    }

    @Test
    void relativeRangeAndI16Encoding() {
        for (JsonElement e : V.getAsJsonObject("anchorRelative").getAsJsonArray("range")) {
            JsonObject c = e.getAsJsonObject();
            int[] r = ints(c.getAsJsonArray("rel"));
            assertEquals(c.get("valid").getAsBoolean(), AnchorMath.inRange(r[0], r[1], r[2]), c.toString());
        }
        JsonObject walk = V.getAsJsonObject("recording").getAsJsonArray("cases").get(1).getAsJsonObject();
        for (JsonElement e : V.getAsJsonObject("anchorRelative").getAsJsonArray("i16")) {
            JsonObject c = e.getAsJsonObject();
            int[] r = ints(c.getAsJsonArray("rel"));
            Header base = header(walk.getAsJsonObject("header"));
            Header h = new Header(1, base.core(), base.mcClient(), base.mcProxy(), base.mode(), base.dim(), base.template(),
                    base.ticks(), base.loop(), base.anchors(), base.marks(), base.containers(), base.steps(), base.items(),
                    base.entities(), List.of("minecraft:stone"), base.unsupported());
            TrackRecord rec = new TrackRecord(0, null, null, null, null, Target.block(r[0], r[1], r[2], 1, 0), null, null,
                    null, false);
            String hex = Vectors.hex(FarmRecording.of(h, List.of(rec)).encode());
            // track = delta(1) flags(2) tag(1) i16 x3 face idx
            String trackHex = hex.substring(hex.length() - 2 * (1 + 2 + 1 + 6 + 1 + 1));
            assertEquals("00" + "0010" + "01" + c.get("hex").getAsString() + "01" + "00", trackHex);
        }
    }

    // ---------- click timing ----------

    private static ClickTarget clickTarget(JsonObject t) {
        return switch (t.get("kind").getAsString()) {
            case "miss" -> ClickTarget.MISS;
            case "entity" -> ClickTarget.entity(t.get("type").getAsString());
            default -> {
                int[] r = ints(t.getAsJsonArray("rel"));
                yield ClickTarget.block(r[0], r[1], r[2], t.get("face").getAsInt(), t.get("block").getAsString());
            }
        };
    }

    private static int mode(String s) {
        return switch (s) {
            case "press" -> 1;
            case "hold" -> 2;
            default -> 0;
        };
    }

    @Test
    void clickTiming() {
        Set<String> holdItems = new HashSet<>(strings(V.getAsJsonObject("clickTiming").getAsJsonArray("holdUseItemsInThisFile")));
        assertTrue(ClickCompiler.HOLD_USE_ITEMS.containsAll(holdItems), "every hold-use item of the vectors is known");
        int n = 0;
        for (JsonElement e : V.getAsJsonObject("clickTiming").getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            List<TickState> ticks = new ArrayList<>();
            for (JsonElement te : c.getAsJsonArray("ticks")) {
                JsonObject t = te.getAsJsonObject();
                ticks.add(new TickState(t.get("t").getAsInt(), t.get("yaw").getAsDouble(), t.get("pitch").getAsDouble(),
                        mode(t.get("attack").getAsString()), mode(t.get("use").getAsString()), t.get("item").getAsString(),
                        clickTarget(t.getAsJsonObject("target"))));
            }
            List<Click> want = new ArrayList<>();
            for (JsonElement se : c.getAsJsonArray("submits")) {
                JsonObject s = se.getAsJsonObject();
                want.add(new Click(s.get("t").getAsInt(), s.get("left").getAsBoolean(), s.get("right").getAsBoolean(),
                        s.get("yaw").getAsDouble(), s.get("pitch").getAsDouble(), clickTarget(s.getAsJsonObject("target"))));
            }
            List<Unsupported> wantU = new ArrayList<>();
            for (JsonElement ue : c.getAsJsonArray("unsupported")) {
                wantU.add(new Unsupported(ue.getAsJsonObject().get("code").getAsString(),
                        ue.getAsJsonObject().get("tick").getAsInt()));
            }
            ClickCompiler.Plan p = ClickCompiler.compile(ticks);
            assertEquals(want, p.clicks(), c.get("id").getAsString());
            assertEquals(wantU, p.unsupported(), c.get("id").getAsString());
            n++;
        }
        assertEquals(11, n);
    }

    // ---------- upload (pure parts) ----------

    @Test
    void uploadChunking() {
        long chunkSize = V.getAsJsonObject("upload").get("chunkSize").getAsLong();
        assertEquals(UploadSession.MAX_CHUNK, chunkSize);
        for (JsonElement e : V.getAsJsonObject("upload").getAsJsonArray("chunking")) {
            JsonObject c = e.getAsJsonObject();
            long total = c.get("totalBytes").getAsLong();
            long size = c.get("chunkSize").getAsLong();
            long chunks = c.get("chunks").getAsLong();
            assertNull(UploadSession.checkBegin(total, size, chunks), c.toString());
            assertEquals(c.get("lastLength").getAsInt(), UploadSession.expectedLength(total, size, chunks, (int) chunks - 1));
        }
    }

    private static final String ZERO_HASH = "0".repeat(64);

    private static UploadSession session(long total, long size, long chunks) {
        return new UploadSession("0123456789abcdef", total, size, chunks, ZERO_HASH, ZERO_HASH, 0);
    }

    @Test
    void uploadBadCases() {
        for (JsonElement e : V.getAsJsonObject("upload").getAsJsonArray("bad")) {
            JsonObject c = e.getAsJsonObject();
            String id = c.get("id").getAsString();
            switch (id) {
                case "zero_bytes", "over_2mib", "chunks_mismatch", "chunk_size_too_big", "chunks_over_86" -> {
                    JsonObject b = c.getAsJsonObject("begin");
                    assertEquals(c.get("error").getAsString(),
                            UploadSession.checkBegin(b.get("totalBytes").getAsLong(), b.get("chunkSize").getAsLong(),
                                    b.get("chunks").getAsLong()), id);
                    assertEquals(c.get("error").getAsString(), code(() -> session(b.get("totalBytes").getAsLong(),
                            b.get("chunkSize").getAsLong(), b.get("chunks").getAsLong())), id + " (session)");
                }
                case "short_middle_chunk", "wrong_last_length", "byte_length_zero", "byte_length_over_chunk_size" -> {
                    JsonObject b = c.getAsJsonObject("begin");
                    UploadSession s = session(b.get("totalBytes").getAsLong(), b.get("chunkSize").getAsLong(),
                            b.get("chunks").getAsLong());
                    JsonArray lens = c.getAsJsonArray("chunkLengths");
                    String got = null;
                    for (int i = 0; i < lens.size() && got == null; i++) {
                        int len = lens.get(i).getAsInt();
                        got = code(() -> {
                            // chunks are sent in order; the offending one is the last length listed
                            s.chunk(s.next(), new byte[len], 0);
                        });
                        if (got == null && i == lens.size() - 1) {
                            fail(id + " accepted");
                        }
                    }
                    assertEquals(c.get("error").getAsString(), got, id);
                }
                case "duplicate_chunk_same_bytes", "duplicate_chunk_other_bytes" -> {
                    UploadSession s = session(30_000, 24_576, 2);
                    byte[] first = new byte[24_576];
                    Arrays.fill(first, (byte) 7);
                    assertEquals(UploadSession.Outcome.ACK, s.chunk(0, first, 0));
                    byte[] again = first.clone();
                    if (c.has("secondDiffers")) {
                        again[5] = 9;
                        assertEquals("bad_hash", code(() -> s.chunk(0, again, 0)), id);
                    } else {
                        assertEquals(UploadSession.Outcome.ACK, s.chunk(0, again, 0), id);
                        assertEquals(1, s.next());
                        assertEquals(UploadSession.Outcome.ACK, s.chunk(1, new byte[5_424], 0));
                        assertTrue(s.complete());
                    }
                }
                case "index_past_window" -> {
                    UploadSession s = session(24_576L * 6, 24_576, 6);
                    int idx = c.get("chunkIndex").getAsInt();
                    assertEquals(UploadSession.Outcome.DROPPED, s.chunk(idx, new byte[24_576], 0));
                    assertEquals(0, s.next(), "ack repeats the next index");
                    assertEquals(UploadSession.Outcome.ACK, s.chunk(3, new byte[24_576], 0), "index 3 is inside the window");
                    assertEquals(0, s.next(), "an out-of-order chunk does not advance the ack");
                }
                case "index_past_count" -> {
                    UploadSession s = session(24_576L * 3, 24_576, 3);
                    assertEquals(c.get("error").getAsString(),
                            code(() -> s.chunk(c.get("chunkIndex").getAsInt(), new byte[24_576], 0)), id);
                }
                case "timeout" -> {
                    UploadSession s = session(10, 24_576, 1);
                    assertFalse(s.timedOut(60_000));
                    assertTrue(s.timedOut(c.get("secondsWithoutChunk").getAsLong() * 1000));
                }
                case "end_before_all_chunks" -> {
                    UploadSession s = session(24_576L * 3, 24_576, 3);
                    s.chunk(0, new byte[24_576], 0);
                    s.chunk(1, new byte[24_576], 0);
                    assertEquals(c.get("error").getAsString(), code(s::finish), id);
                }
                case "packed_hash_mismatch" -> {
                    byte[] plain = Vectors.hex(V.getAsJsonObject("recording").getAsJsonArray("cases").get(0).getAsJsonObject()
                            .get("plainHex").getAsString());
                    byte[] packed = Packing.pack(plain);
                    UploadSession s = new UploadSession("0123456789abcdef", packed.length, 24_576,
                            (packed.length + 24_575) / 24_576, ZERO_HASH, FarmRecording.sha256Hex(plain), 0);
                    s.chunk(0, packed, 0);
                    assertEquals(c.get("error").getAsString(), code(s::finish), id);
                }
                case "inflated_over_8mib" -> {
                    byte[] bomb = Packing.pack(new byte[8 * 1024 * 1024 + 1]);
                    assertTrue(bomb.length < 24_576);
                    UploadSession s = new UploadSession("0123456789abcdef", bomb.length, 24_576, 1,
                            FarmRecording.sha256Hex(bomb), ZERO_HASH, 0);
                    s.chunk(0, bomb, 0);
                    assertEquals(c.get("error").getAsString(), code(s::finish), id);
                }
                default -> {
                    // plugin-level behaviours (busy, uploads_off, rate limit, Bridge reader, hello): not core logic
                    assertTrue(Set.of("words_missing", "begin_while_session_open", "fifth_upload_in_10_minutes",
                            "uploads_switch_off", "real_farm_id_not_synced", "hello_feature_count_huge").contains(id), id);
                }
            }
        }
    }

    @Test
    void uploadWholePipelineAcceptsAGoodProgram() {
        byte[] plain = Vectors.hex(V.getAsJsonObject("recording").getAsJsonArray("cases").get(2).getAsJsonObject()
                .get("plainHex").getAsString());
        byte[] packed = Packing.pack(plain);
        int size = 7;
        int chunks = (packed.length + size - 1) / size;
        assertTrue(chunks > 1 && chunks <= UploadSession.MAX_CHUNKS, "the test program must need several small chunks");
        UploadSession s = new UploadSession("0123456789abcdef", packed.length, size, chunks,
                FarmRecording.sha256Hex(packed), FarmRecording.sha256Hex(plain), 0);
        for (int i = 0; i < chunks; i++) {
            int len = UploadSession.expectedLength(packed.length, size, chunks, i);
            s.chunk(i, Arrays.copyOfRange(packed, i * size, i * size + len), 0);
        }
        UploadSession.Finished f = s.finish();
        assertArrayEquals(plain, f.plain());
        assertEquals(FarmRecording.sha256Hex(plain), f.program().programHash());
    }

    @Test
    void idPatterns() {
        JsonObject p = V.getAsJsonObject("upload").getAsJsonObject("idPatterns");
        check(p.getAsJsonObject("uploadId"), UploadSession.UPLOAD_ID.pattern(), UploadSession::uploadIdOk);
        check(p.getAsJsonObject("farmId"), UploadSession.FARM_ID.pattern(), UploadSession::farmIdOk);
        check(p.getAsJsonObject("dim"), UploadSession.DIM.pattern(), UploadSession::dimOk);
        assertEquals(ProgramChecker.ID.pattern(), UploadSession.DIM.pattern());
    }

    private static void check(JsonObject o, String pattern, java.util.function.Predicate<String> ok) {
        assertEquals(o.get("pattern").getAsString(), pattern);
        o.getAsJsonArray("ok").forEach(e -> assertTrue(ok.test(e.getAsString()), e.getAsString()));
        o.getAsJsonArray("bad").forEach(e -> assertFalse(ok.test(e.getAsString()), e.getAsString()));
        assertFalse(ok.test(null));
    }

    // ---------- farm names and the server guard strings ----------

    @Test
    void farmNames() {
        for (JsonElement e : V.getAsJsonObject("farmNames").getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            String n = FarmNames.normalize(c.get("name").getAsString());
            if (c.get("ok").getAsBoolean()) {
                assertNotNull(n, c.toString());
                assertEquals(c.get("name").getAsString().strip(), n);
            } else {
                assertNull(n, c.toString());
            }
        }
        assertNull(FarmNames.normalize(null));
        assertEquals("Bone meal", FarmNames.normalize("  Bone meal \n"));
    }

    @Test
    void serverGuardKeysAndStrings() {
        JsonObject g = V.getAsJsonObject("serverGuard");
        for (JsonElement k : g.getAsJsonObject("fieldNames").getAsJsonArray("keys")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(k.getAsString(), 1);
            assertTrue(PositionGuard.hasPositionField(m), k.getAsString());
        }
        for (JsonElement e : g.getAsJsonObject("fieldNames").getAsJsonArray("bodies")) {
            JsonObject c = e.getAsJsonObject();
            Object body = plain(c.get("body"));
            assertEquals(!c.get("error").isJsonNull(), PositionGuard.hasPositionField(body), c.toString());
        }
        JsonObject sp = g.getAsJsonObject("stringPattern");
        assertEquals(sp.get("pattern").getAsString(), PositionGuard.POSITION.pattern());
        for (JsonElement e : sp.getAsJsonArray("strings")) {
            JsonObject c = e.getAsJsonObject();
            assertEquals(c.get("match").getAsBoolean(), PositionGuard.looksLikePosition(c.get("s").getAsString()), c.toString());
        }
    }
}
