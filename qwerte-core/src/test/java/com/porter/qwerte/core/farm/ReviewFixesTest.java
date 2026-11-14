package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.porter.qwerte.core.farm.ClickCompiler.Click;
import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.ClickCompiler.TickState;
import com.porter.qwerte.core.farm.FarmRecording.Header;
import com.porter.qwerte.core.farm.FarmRecording.Rel;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import com.porter.qwerte.core.farm.ProgramCompiler.RawAnchor;
import com.porter.qwerte.core.farm.ProgramCompiler.RawRecording;
import com.porter.qwerte.core.farm.ProgramCompiler.RawTick;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Tests added for the code review of the farm package (M1 to M4, L1 to L8 and the test list). */
class ReviewFixesTest {
    private static FileSystem posixFs() {
        return Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build());
    }

    private static byte[] base() {
        return Vectors.hex(Vectors.load().getAsJsonObject("recording").getAsJsonArray("cases").get(1).getAsJsonObject()
                .get("plainHex").getAsString());
    }

    private static Header baseHeader() {
        return FarmRecording.decode(base()).header();
    }

    private static Header with(Header h, String mode, Map<String, Object> params, List<String> entities,
                               List<FarmRecording.Container> containers, List<FarmRecording.Anchor> anchors,
                               String core) {
        return new Header(1, core, h.mcClient(), h.mcProxy(), mode, h.dim(), new FarmRecording.Template(mode, params),
                h.ticks(), h.loop(), anchors, h.marks(), containers, h.steps(), h.items(), entities, h.blocks(),
                h.unsupported());
    }

    private static String code(Runnable r) {
        return assertThrows(FarmException.class, r::run).code();
    }

    // ---------- M1 ----------

    @Test
    void m3ModesFailClosedWithParamsAndTheParamGuardIsRecursive() {
        Header h = baseHeader();
        assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "fishing", Map.of("mend", true), List.of(), List.of(),
                h.anchors(), "1.1.0"), List.of())));
        FarmRecording.of(with(h, "fishing", Map.of(), List.of(), List.of(), h.anchors(), "1.1.0"), List.of());
        // the guard also applies in custom and afk_still, deep inside the values
        Map<String, Object> deepPlayer = new LinkedHashMap<>();
        deepPlayer.put("repeat", true);
        deepPlayer.put("maxLoops", 0L);
        deepPlayer.put("x", List.of(Map.of("a", "minecraft:player")));
        assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "custom", deepPlayer, List.of(), List.of(),
                h.anchors(), "1.1.0"), List.of())));
        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("repeat", Map.of("origin", 1L));
        assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "custom", forbidden, List.of(), List.of(),
                h.anchors(), "1.1.0"), List.of())));
        assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "afk_still", Map.of("anchor", "minecraft:player"),
                List.of(), List.of(), h.anchors(), "1.1.0"), List.of())));
        // the legitimate afk_still anchor key still works
        FarmRecording.of(with(h, "afk_still", Map.of("anchor", "a0"), List.of(), List.of(), h.anchors(), "1.1.0"), List.of());
    }

    @Test
    void playerInEntitiesIsRefusedAsAUnit() {
        Header h = baseHeader();
        assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "custom", h.template().params(),
                List.of("minecraft:player"), List.of(), h.anchors(), "1.1.0"), List.of())));
        assertEquals("bad_program", code(() -> ProgramChecker.validateHeader(with(h, "custom", h.template().params(),
                List.of("minecraft:zombie", "minecraft:player"), List.of(), h.anchors(), "1.1.0"))));
    }

    // ---------- boundaries and exceptions ----------

    private static String headerText() {
        byte[] b = base();
        int hl = java.nio.ByteBuffer.wrap(b, 5, 4).getInt();
        return new String(b, 9, hl, StandardCharsets.UTF_8);
    }

    private static byte[] withHeaderText(String header) {
        byte[] b = base();
        int hl = java.nio.ByteBuffer.wrap(b, 5, 4).getInt();
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[9 + hb.length + b.length - 9 - hl];
        System.arraycopy(b, 0, out, 0, 5);
        java.nio.ByteBuffer.wrap(out, 5, 4).putInt(hb.length);
        System.arraycopy(hb, 0, out, 9, hb.length);
        System.arraycopy(b, 9 + hl, out, 9 + hb.length, b.length - 9 - hl);
        return out;
    }

    private static byte[] chestAt(String standX) {
        // anchor stand (standX, 0.5, 0.5), chest block 6,0,0: centre (6.5, 0.5, 0.5), so the distance is 6.5 - standX
        return withHeaderText(headerText().replace("\"stand\":[0.5,0.0,0.5]", "\"stand\":[" + standX + ",0.5,0.5]")
                .replace("\"containers\":[]",
                        "\"containers\":[{\"rel\":[6,0,0],\"block\":\"minecraft:chest\",\"role\":\"collect\"}]"));
    }

    @Test
    void containerBoundaryIsExactlySixPointZero() {
        ProgramChecker.checkPlain(chestAt("0.5")); // 6.0 exactly: accepted
        ProgramChecker.checkPlain(chestAt("0.51")); // 5.99
        assertEquals("bad_program", assertThrows(FarmException.class, () -> ProgramChecker.checkPlain(chestAt("0.49"))).code()); // 6.01
        assertEquals("bad_program", assertThrows(FarmException.class, () -> ProgramChecker.checkPlain(chestAt("0.0"))).code());
    }

    @Test
    void publicMethodsThrowOnlyFarmException() {
        assertEquals("bad_program", code(() -> ProgramChecker.check(null)));
        assertEquals("bad_program", code(() -> ProgramChecker.validateHeader(null)));
        assertEquals("bad_track", code(() -> ProgramChecker.validateTrack(baseHeader(), null)));
        assertEquals("bad_program", code(() -> ProgramChecker.checkPlain(null)));
        assertEquals("bad_program", code(() -> Packing.unpack(new byte[] {1}, -5)));
        assertEquals("bad_program", code(() -> Packing.unpack(new byte[] {1}, 0)));
        assertEquals("bad_program", code(() -> Packing.pack(null)));
        assertEquals("bad_program", code(() -> FarmRecording.of(null, List.of())));
        assertEquals("bad_program", code(() -> ProgramCompiler.compile(null, false)));
        assertEquals("bad_program", code(() -> UploadSession.expectedLength(10, 24_576, 1, 5)));
        assertEquals("bad_program", code(() -> UploadSession.expectedLength(0, 0, 0, 0)));
        // UploadSession: hostile constructor and chunk arguments
        assertEquals("bad_program", code(() -> new UploadSession(null, 10, 24_576, 1, "0".repeat(64), "0".repeat(64), 0)));
        assertEquals("bad_hash", code(() -> new UploadSession("0123456789abcdef", 10, 24_576, 1, null, "0".repeat(64), 0)));
        assertEquals("too_big", code(() -> new UploadSession("0123456789abcdef", Long.MAX_VALUE, 24_576, 1,
                "0".repeat(64), "0".repeat(64), 0)));
        assertEquals("bad_program", code(() -> new UploadSession("0123456789abcdef", Long.MIN_VALUE, 24_576, 1,
                "0".repeat(64), "0".repeat(64), 0)));
        UploadSession s = new UploadSession("0123456789abcdef", 10, 24_576, 1, "0".repeat(64), "0".repeat(64), 0);
        assertEquals("bad_program", code(() -> s.chunk(-1, new byte[10], 0)));
        assertEquals("bad_program", code(() -> s.chunk(0, null, 0)));
        assertEquals("bad_program", code(() -> s.finish()));
    }

    // ---------- L3, L6, L7, L8 ----------

    @Test
    void sampleValuesAreBounded() {
        Header h = baseHeader();
        FarmRecording.TrackRecord ok = new FarmRecording.TrackRecord(1, null, null, null, null, null, null,
                new FarmRecording.Sample(514f, 386f, -514f), null, false);
        FarmRecording.of(h, List.of(ok));
        for (FarmRecording.Sample s : new FarmRecording.Sample[] {new FarmRecording.Sample(515f, 0f, 0f),
                new FarmRecording.Sample(0f, -387f, 0f), new FarmRecording.Sample(0f, 0f, 1_000_000f),
                new FarmRecording.Sample(Float.NaN, 0f, 0f)}) {
            FarmRecording.TrackRecord bad = new FarmRecording.TrackRecord(1, null, null, null, null, null, null, s, null, false);
            assertEquals("bad_track", code(() -> FarmRecording.of(h, List.of(bad))));
        }
    }

    @Test
    void loneSurrogatesAreRefusedInTheVersionStrings() {
        Header h = baseHeader();
        for (String bad : new String[] {"\ud800", "a\udc00b", "x\ud800y"}) {
            assertEquals("bad_program", code(() -> FarmRecording.of(with(h, "custom", h.template().params(), List.of(),
                    List.of(), h.anchors(), bad), List.of())));
        }
        FarmRecording.of(with(h, "custom", h.template().params(), List.of(), List.of(), h.anchors(), "ok\ud83d\ude00"),
                List.of());
        // through the JSON escape as well
        String txt = headerText().replace("\"core\":\"1.1.0\"", "\"core\":\"\\ud800\"");
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(txt)));
    }

    @Test
    void negativeZeroDoesNotChangeTheHash() {
        Header h = baseHeader();
        FarmRecording.Anchor a = h.anchors().get(0);
        List<FarmRecording.Anchor> plus = List.of(new FarmRecording.Anchor(a.id(), a.rel(), a.stand(), 0.0, 0.0, a.tol(), a.fp(), a.tick()));
        List<FarmRecording.Anchor> minus = List.of(new FarmRecording.Anchor(a.id(), a.rel(), a.stand(), -0.0, -0.0, a.tol(), a.fp(), a.tick()));
        byte[] x = FarmRecording.of(with(h, "custom", h.template().params(), List.of(), List.of(), plus, "1.1.0"), List.of()).encode();
        byte[] y = FarmRecording.of(with(h, "custom", h.template().params(), List.of(), List.of(), minus, "1.1.0"), List.of()).encode();
        assertArrayEquals(x, y);
    }

    @Test
    void waitUntilNeedsItsFields() {
        String[] bad = {"{\"op\":\"wait-until\",\"what\":\"item_count\"}",
                "{\"op\":\"wait-until\",\"what\":\"item_count\",\"item\":\"minecraft:diamond\"}",
                "{\"op\":\"wait-until\",\"what\":\"durability\"}", "{\"op\":\"wait-until\",\"what\":\"time\"}"};
        for (String s : bad) {
            assertEquals("bad_program", ProgramChecker.verify(withHeaderText(headerText().replace(
                    "\"steps\":{\"start\":[],\"stop\":[]}", "\"steps\":{\"start\":[" + s + "],\"stop\":[]}"))), s);
        }
        String[] good = {"{\"op\":\"wait-until\",\"what\":\"inventory_full\"}",
                "{\"op\":\"wait-until\",\"what\":\"time\",\"ticks\":20}",
                "{\"op\":\"wait-until\",\"what\":\"durability\",\"percent\":10}",
                "{\"op\":\"wait-until\",\"what\":\"item_count\",\"item\":\"minecraft:diamond\",\"count\":3}"};
        for (String s : good) {
            assertEquals(null, ProgramChecker.verify(withHeaderText(headerText().replace(
                    "\"steps\":{\"start\":[],\"stop\":[]}", "\"steps\":{\"start\":[" + s + "],\"stop\":[]}"))), s);
        }
    }

    // ---------- M3 canonical bytes, L5 ----------

    @Test
    void sameStepsViaMapOfAndLinkedHashMapEncodeIdentically() {
        Header h = baseHeader();
        Map<String, Object> viaMapOf = Map.of("count", 3L, "item", "minecraft:diamond", "container", 0L, "op", "take-from");
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("op", "take-from");
        linked.put("container", 0L);
        linked.put("item", "minecraft:diamond");
        linked.put("count", 3L);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("count", 3L);
        reversed.put("item", "minecraft:diamond");
        reversed.put("container", 0L);
        reversed.put("op", "take-from");
        List<FarmRecording.Container> chest = List.of(new FarmRecording.Container(new Rel(2, 0, 0), "minecraft:chest", "collect"));
        byte[][] out = new byte[3][];
        int i = 0;
        for (Map<String, Object> step : List.of(viaMapOf, linked, reversed)) {
            Header hh = new Header(1, "1.1.0", h.mcClient(), h.mcProxy(), "custom", h.dim(), h.template(), h.ticks(), h.loop(),
                    h.anchors(), List.of(), chest, new FarmRecording.Steps(List.of(step), List.of()), h.items(), h.entities(),
                    h.blocks(), List.of());
            out[i++] = FarmRecording.of(hh, List.of()).encode();
        }
        assertArrayEquals(out[0], out[1]);
        assertArrayEquals(out[1], out[2]);
        assertTrue(new String(out[0], StandardCharsets.UTF_8).contains(
                "{\"op\":\"take-from\",\"container\":0,\"item\":\"minecraft:diamond\",\"count\":3}"));
    }

    @Test
    void nestedModelValuesAreDeeplyUnmodifiable() {
        List<Object> inner = new ArrayList<>(List.of(1L, 2L));
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "use-block");
        step.put("rel", inner);
        step.put("face", 1L);
        FarmRecording.Steps steps = new FarmRecording.Steps(List.of(step), List.of());
        inner.add(3L);
        @SuppressWarnings("unchecked")
        List<Object> copy = (List<Object>) steps.start().get(0).get("rel");
        assertEquals(2, copy.size(), "a later change of the caller's list does not reach the model");
        assertThrows(UnsupportedOperationException.class, () -> copy.add(9L));
        assertThrows(UnsupportedOperationException.class, () -> steps.start().get(0).put("x", 1L));
    }

    // ---------- M4 re-press ----------

    private static RawRecording raw(int[] attack, int[] use, ClickTarget target, String item) {
        int n = attack.length;
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            t.add(new RawTick(0, 10.0 + i, 0.0, attack[i], use[i], false, 0, item, attack[i] != 0 || use[i] != 0 ? target : ClickTarget.MISS,
                    null, List.of(), false));
        }
        return new RawRecording("1.21.11", "1.21.4", "afk_still", "minecraft:overworld", Map.of("anchor", "a0"), 0, n, true,
                List.of(new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 10.0, 0.0, 0.3, null, null, 0)), List.of(), List.of(),
                List.of(), List.of(), t);
    }

    private static List<TickState> states(RawRecording r) {
        List<TickState> s = new ArrayList<>();
        for (int i = 0; i < r.ticks().size(); i++) {
            RawTick t = r.ticks().get(i);
            s.add(new TickState(i, t.yaw(), t.pitch(), t.attack(), t.use(), t.offHandUse(), t.item(), t.target()));
        }
        return s;
    }

    private static void sameClicks(RawRecording r) {
        List<Click> fromRaw = ClickCompiler.compile(states(r)).clicks();
        FarmRecording p = ProgramCompiler.compile(r, true).program();
        List<Click> fromProgram = ProgramCompiler.clicks(p).clicks();
        assertEquals(fromRaw.size(), fromProgram.size());
        for (int i = 0; i < fromRaw.size(); i++) {
            assertEquals(fromRaw.get(i).t(), fromProgram.get(i).t());
            assertEquals(fromRaw.get(i).left(), fromProgram.get(i).left());
            assertEquals(fromRaw.get(i).right(), fromProgram.get(i).right());
            assertEquals(fromRaw.get(i).yaw(), fromProgram.get(i).yaw(), 1e-4);
            assertEquals(fromRaw.get(i).target(), fromProgram.get(i).target());
        }
        // and it survives the wire
        byte[] bytes = p.encode();
        assertArrayEquals(bytes, FarmRecording.decode(bytes).encode());
    }

    @Test
    void consecutiveAttackPressesBecomeTwoClicks() {
        ClickTarget piglin = ClickTarget.entity("minecraft:zombified_piglin");
        RawRecording r = raw(new int[] {0, 1, 1, 0, 1, 1, 1, 0}, new int[8], piglin, "minecraft:diamond_sword");
        List<Click> clicks = ClickCompiler.compile(states(r)).clicks();
        assertEquals(List.of(1, 2, 4, 5, 6), clicks.stream().map(Click::t).toList(), "every press is a click");
        sameClicks(r);
        // 1,1 on a block target: block breaking clicks every tick either way
        sameClicks(raw(new int[] {1, 1, 1}, new int[3], ClickTarget.block(1, 0, -2, 1, "minecraft:stone"), "minecraft:diamond_pickaxe"));
    }

    @Test
    void consecutiveUsePressesBecomeTwoClicks() {
        RawRecording r = raw(new int[7], new int[] {0, 1, 1, 0, 1, 1, 0}, ClickTarget.MISS, "minecraft:bone_meal");
        assertEquals(List.of(1, 2, 4, 5), ClickCompiler.compile(states(r)).clicks().stream().map(Click::t).toList());
        sameClicks(r);
        // a press that interrupts a hold restarts the 4-tick cadence
        RawRecording h = raw(new int[12], new int[] {1, 2, 2, 1, 2, 2, 2, 2, 2, 0, 0, 0}, ClickTarget.MISS, "minecraft:bone_meal");
        sameClicks(h);
    }

    @Test
    void existingVectorBytesAreUnchangedByTheRepressRule() {
        // press then none, as in the vectors: no extra records appear
        RawRecording r = raw(new int[] {0, 1, 0, 0}, new int[4], ClickTarget.entity("minecraft:zombified_piglin"), "minecraft:diamond_sword");
        FarmRecording p = ProgramCompiler.compile(r, true).program();
        assertEquals(List.of(1, 2), p.track().stream().filter(t -> t.attack() != null).map(t -> t.tick()).toList(),
                "one press record and one release record, nothing repeated");
    }

    // ---------- no absolute coordinates can pass ----------

    private static RawRecording rawWith(Vec anchorOffset, Vec feet, ClickTarget target, FarmRecording.Container container) {
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            t.add(new RawTick(0, 0.0, 0.0, i == 1 ? 1 : 0, 0, false, 0, "minecraft:diamond_sword", target, feet, List.of(), false));
        }
        return new RawRecording("1.21.11", "1.21.4", "afk_still", "minecraft:overworld", Map.of("anchor", "a0"), 0, 3, true,
                List.of(new RawAnchor("a0", anchorOffset, 0.0, 0.0, 0.3, null, null, 0)), List.of(),
                container == null ? List.of() : List.of(container), List.of(), List.of(), t);
    }

    @Test
    void absoluteValuesFedToTheCompilerAreRefused() {
        // relative inputs pass
        ProgramCompiler.compile(rawWith(new Vec(0.5, 0.0, 0.5), new Vec(0.5, 0.0, 0.5), ClickTarget.MISS, null), false);
        // an absolute feet sample (a real-world x or z) is outside any possible offset
        assertEquals("bad_track", code(() -> ProgramCompiler.compile(rawWith(new Vec(0.5, 0.0, 0.5),
                new Vec(250_000.5, 64.0, -1_200_000.5), ClickTarget.MISS, null), false)));
        // an absolute anchor position cannot be a0 (its whole-block offset would not be 0) nor stay within 512
        assertEquals("bad_program", code(() -> ProgramCompiler.compile(rawWith(new Vec(250_000.5, 64.0, 7.5),
                new Vec(0.5, 0.0, 0.5), ClickTarget.MISS, null), false)));
        // an absolute block target
        assertEquals("bad_track", code(() -> ProgramCompiler.compile(rawWith(new Vec(0.5, 0.0, 0.5),
                new Vec(0.5, 0.0, 0.5), ClickTarget.block(250_000, 64, -1_200_000, 1, "minecraft:stone"), null), false)));
        // an absolute container
        assertEquals("bad_program", code(() -> ProgramCompiler.compile(rawWith(new Vec(0.5, 0.0, 0.5), new Vec(0.5, 0.0, 0.5),
                ClickTarget.MISS, new FarmRecording.Container(new Rel(250_000, 64, 0), "minecraft:chest", "collect")), false)));
    }

    // ---------- FarmKey: race, short files, cleanup, symlinked dir ----------

    @Test
    void manyThreadsRacingToCreateTheKeyAllGetTheSameKey() throws Exception {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            int n = 12;
            ExecutorService ex = Executors.newFixedThreadPool(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<FarmKey.Loaded>> fs2 = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs2.add(ex.submit(() -> {
                    go.await();
                    return FarmKey.load(dir, null);
                }));
            }
            go.countDown();
            byte[] first = null;
            for (Future<FarmKey.Loaded> f : fs2) {
                FarmKey.Loaded l = f.get();
                assertTrue(l.ok(), l.toString());
                if (first == null) {
                    first = l.key();
                }
                assertArrayEquals(first, l.key());
            }
            ex.shutdown();
            assertArrayEquals(first, Files.readAllBytes(dir.resolve("farm.key")));
            assertEquals(1, Files.list(dir).count(), "exactly one file, no temp leftovers");
        }
    }

    @Test
    void aShortFileIsRetriedUntilTheWriterFinishesAndAnEmptyOneStaysKeyInvalid() throws Exception {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = dir.resolve("farm.key");
            Files.write(file, new byte[0]);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 9);
            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(120);
                    Files.write(file, key);
                } catch (Exception e) {
                    // the assertion below reports it
                }
            });
            writer.start();
            FarmKey.Loaded l = FarmKey.load(dir, null);
            writer.join();
            assertTrue(l.ok(), l.toString());
            assertArrayEquals(key, l.key());
            // a 0-byte file left by an interrupted write: key_invalid, left in place, nothing invented
            Files.write(file, new byte[0]);
            assertEquals("key_invalid", FarmKey.load(dir, null).code());
            assertEquals(0, Files.size(file));
            Files.write(file, new byte[10]);
            assertEquals("key_invalid", FarmKey.load(dir, null).code());
            assertEquals(10, Files.size(file));
            Files.write(file, new byte[5000]);
            assertEquals("key_invalid", FarmKey.load(dir, null).code());
        }
    }

    @Test
    void aSymlinkedFarmDirectoryIsRefusedForCreationAndForReading() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path real = fs.getPath("/elsewhere");
            Files.createDirectories(real, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            Path link = fs.getPath("/farm");
            Files.createSymbolicLink(link, real);
            assertEquals("key_perms", FarmKey.load(link, null).code());
            assertFalse(Files.exists(real.resolve("farm.key")), "nothing created through the link");
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(real),
                    "the target directory was not chmod-ed through the link");
            // an existing private key behind a symlinked directory is refused as well
            Path key = real.resolve("farm.key");
            Files.write(key, new byte[32]);
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
            assertEquals("key_perms", FarmKey.load(link, null).code());
        }
    }

    @Test
    void aFailedCreationLeavesNoKeyFileBehind() throws IOException {
        try (FileSystem fs = posixFs()) {
            Path dir = fs.getPath("/farm");
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            // a directory named farm.key makes the exclusive create fail: the result is closed and nothing else appears
            Files.createDirectory(dir.resolve("farm.key"));
            FarmKey.Loaded l = FarmKey.load(dir, null);
            assertFalse(l.ok());
            assertEquals(1, Files.list(dir).count());
            assertNotEquals(null, l.code());
        }
    }
}
