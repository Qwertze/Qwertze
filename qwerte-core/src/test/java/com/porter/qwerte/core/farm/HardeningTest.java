package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class HardeningTest {
    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (FarmException e) {
            return e.code();
        }
    }

    // ---------- bounded JSON ----------

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    @Test
    void boundedJsonDepthStringsNumbersAndDuplicates() {
        assertEquals(List.of(), asList(BoundedJson.parse(nested(1))));
        BoundedJson.parse(nested(16));
        assertEquals("bad_program", code(() -> BoundedJson.parse(nested(17))));
        assertEquals("bad_program", code(() -> BoundedJson.parse("{\"a\":" + nested(16) + "}")));
        BoundedJson.parse("\"" + "a".repeat(256) + "\"");
        assertEquals("bad_program", code(() -> BoundedJson.parse("\"" + "a".repeat(257) + "\"")));
        assertEquals("bad_program", code(() -> BoundedJson.parse("{\"" + "k".repeat(257) + "\":1}")));
        assertEquals("bad_program", code(() -> BoundedJson.parse("{\"a\":1,\"a\":2}")));
        assertEquals(5L, BoundedJson.parse("5"));
        assertEquals(-0.5, (Double) BoundedJson.parse("-0.5"), 0);
        assertEquals(1000.0, (Double) BoundedJson.parse("1e3"), 0);
        assertEquals("bad_program", code(() -> BoundedJson.parse("1e999")));
        assertEquals("bad_program", code(() -> BoundedJson.parse("123456789012345678901")));
        assertEquals("bad_program", code(() -> BoundedJson.parse("1" + "0".repeat(40) + ".5")));
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\"}", "01", "-", "NaN", "Infinity", "tru", "\"a", "{\"a\":1}x",
                "[1 2]", "\"\\x\"", "\"\\u12\"", "\"a\nb\"", "{'a':1}"}) {
            assertEquals("bad_program", code(() -> BoundedJson.parse(bad)), bad);
        }
        assertEquals("a\"b\u00e9", BoundedJson.parse("\"a\\\"b\\u00e9\""));
        assertNull(BoundedJson.parse("null"));
        assertEquals(Boolean.TRUE, BoundedJson.parse(" true "));
    }

    private static List<?> asList(Object o) {
        return assertInstanceOf(List.class, o);
    }

    @Test
    void aHugeFlatHeaderCannotExhaustMemoryBeyondItsByteBound() {
        // 64 KiB of "1," is at most 32,768 nodes: parsed fine, bounded by the header limit the decoder enforces first
        String s = "[" + "1,".repeat(30_000) + "1]";
        assertEquals(30_001, asList(BoundedJson.parse(s)).size());
    }

    // ---------- packing and the zip-bomb guard ----------

    @Test
    void packingRoundTripsWithTheZlibWrapper() {
        byte[] plain = "QFP1 hello hello hello hello".getBytes(StandardCharsets.UTF_8);
        byte[] packed = Packing.pack(plain);
        assertEquals(0x78, packed[0] & 0xFF, "zlib header");
        assertArrayEquals(plain, Packing.unpack(packed));
    }

    @Test
    void inflatingStopsAtTheCounter() {
        byte[] bomb = Packing.pack(new byte[16 * 1024 * 1024]);
        assertTrue(bomb.length < 100_000);
        assertEquals("too_big", code(() -> Packing.unpack(bomb)));
        byte[] fine = Packing.pack(new byte[8 * 1024 * 1024]);
        assertEquals(8 * 1024 * 1024, Packing.unpack(fine).length, "exactly 8 MiB is allowed");
        assertEquals("too_big", code(() -> Packing.unpack(fine, 1000)));
    }

    @Test
    void corruptTruncatedOrTrailingStreamsAreBadProgram() {
        byte[] packed = Packing.pack("some program bytes some program bytes".getBytes(StandardCharsets.UTF_8));
        assertEquals("bad_program", code(() -> Packing.unpack(new byte[] {1, 2, 3, 4})));
        assertEquals("bad_program", code(() -> Packing.unpack(java.util.Arrays.copyOf(packed, packed.length - 4))));
        byte[] trailing = java.util.Arrays.copyOf(packed, packed.length + 3);
        assertEquals("bad_program", code(() -> Packing.unpack(trailing)));
        assertEquals("bad_program", code(() -> Packing.unpack(new byte[0])));
        assertEquals("bad_program", code(() -> Packing.unpack(null)));
        assertEquals("too_big", code(() -> Packing.unpack(new byte[Packing.MAX_PACKED + 1])));
    }

    // ---------- checker rules beyond the vectors ----------

    private static byte[] base() {
        JsonObject v = Vectors.load();
        String hex = v.getAsJsonObject("recording").getAsJsonArray("cases").get(1).getAsJsonObject().get("plainHex").getAsString();
        return Vectors.hex(hex);
    }

    private static byte[] withHeaderText(String header) {
        byte[] b = base();
        int hl = ByteBuffer.wrap(b, 5, 4).getInt();
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[9 + hb.length + b.length - 9 - hl];
        System.arraycopy(b, 0, out, 0, 5);
        ByteBuffer.wrap(out, 5, 4).putInt(hb.length);
        System.arraycopy(hb, 0, out, 9, hb.length);
        System.arraycopy(b, 9 + hl, out, 9 + hb.length, b.length - 9 - hl);
        return out;
    }

    private static String headerText() {
        byte[] b = base();
        int hl = ByteBuffer.wrap(b, 5, 4).getInt();
        return new String(b, 9, hl, StandardCharsets.UTF_8);
    }

    @Test
    void containerReachIsExactlySixBlocks() {
        // stand 0.5,0,0.5 and a container whose centre is 6.0 away along x: block x = 6 - 0.5 + ... use rel 6,0,0 with the
        // anchor stand moved to 0.5 -> centre distance sqrt(36 + 0.25); an anchor at x offset 1 brings it to sqrt(25.25)
        String h = headerText().replace("\"containers\":[]",
                "\"containers\":[{\"rel\":[6,0,0],\"block\":\"minecraft:chest\",\"role\":\"collect\"}]");
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h)));
        // a second anchor next to the chest makes the same container legal
        String twoAnchors = h.replace("\"anchors\":[{", "\"anchors\":[{").replace("\"tick\":0}],\"marks\"",
                "\"tick\":0},{\"id\":\"a1\",\"rel\":[3,0,0],\"stand\":[0.5,0.0,0.5],\"yaw\":0.0,\"pitch\":0.0,\"tol\":0.3,"
                        + "\"tick\":10}],\"marks\"");
        assertNull(ProgramChecker.verify(withHeaderText(twoAnchors)));
        // 3D distance counts height too: a chest 7 blocks above the stand is refused even with x and z equal
        String high = headerText().replace("\"containers\":[]",
                "\"containers\":[{\"rel\":[0,7,0],\"block\":\"minecraft:chest\",\"role\":\"supply\"}]");
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(high)));
    }

    @Test
    void playerIsRefusedInEntitiesAndInAttackSteps() {
        String steps = headerText().replace("\"steps\":{\"start\":[],\"stop\":[]}",
                "\"steps\":{\"start\":[{\"op\":\"attack\",\"entity\":\"minecraft:player\"}],\"stop\":[]}");
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(steps)));
        String ok = headerText().replace("\"steps\":{\"start\":[],\"stop\":[]}",
                "\"steps\":{\"start\":[{\"op\":\"attack\",\"entity\":\"minecraft:zombified_piglin\"}],\"stop\":[]}");
        assertNull(ProgramChecker.verify(withHeaderText(ok)));
    }

    @Test
    void stepVocabularyIsClosed() {
        String[][] bad = {
                {"{\"op\":\"exec\",\"cmd\":\"rm\"}"},
                {"{\"op\":\"wait\",\"ticks\":0}"},
                {"{\"op\":\"wait\",\"ticks\":72001}"},
                {"{\"op\":\"wait\",\"ticks\":5,\"extra\":1}"},
                {"{\"op\":\"walk-to-anchor\",\"anchor\":\"a9\"}"},
                {"{\"op\":\"use-block\",\"rel\":[1,2],\"face\":1}"},
                {"{\"op\":\"use-block\",\"rel\":[1,2,3],\"face\":6}"},
                {"{\"op\":\"take-from\",\"container\":0,\"item\":\"minecraft:diamond\",\"count\":1}"}, // no containers
                {"{\"op\":\"wait-until\",\"what\":\"never\"}"},
                {"{\"op\":\"select\",\"item\":\"Minecraft:Bad\"}"},
                {"{\"op\":\"look\",\"yaw\":1,\"pitch\":91}"},
                {"{\"op\":5}"},
                {"{}"},
        };
        for (String[] s : bad) {
            String h = headerText().replace("\"steps\":{\"start\":[],\"stop\":[]}",
                    "\"steps\":{\"start\":[" + s[0] + "],\"stop\":[]}");
            assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h)), s[0]);
        }
        String good = "{\"op\":\"walk-to-anchor\",\"anchor\":\"a0\"},{\"op\":\"look\",\"yaw\":10.5,\"pitch\":-3},"
                + "{\"op\":\"use-block\",\"rel\":[1,0,-2],\"face\":1},{\"op\":\"hold-use\",\"ticks\":20},"
                + "{\"op\":\"select\",\"item\":\"minecraft:bone_meal\"},{\"op\":\"wait\",\"ticks\":72000},"
                + "{\"op\":\"wait-until\",\"what\":\"item_count\",\"item\":\"minecraft:bone_meal\",\"count\":64}";
        String h = headerText().replace("\"steps\":{\"start\":[],\"stop\":[]}", "\"steps\":{\"start\":[" + good + "],\"stop\":[]}");
        assertNull(ProgramChecker.verify(withHeaderText(h)));
    }

    @Test
    void modesParamsAndMarksAreValidated() {
        String h = headerText();
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"maxLoops\":0", "\"maxLoops\":1000001"))));
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"repeat\":true,\"maxLoops\"",
                "\"repeat\":\"yes\",\"maxLoops\""))));
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"maxLoops\":0", "\"maxLoops\":0,\"x\":1"))));
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"marks\":[]",
                "\"marks\":[{\"kind\":\"collect\",\"tick\":5}]"))), "collect needs a container index");
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"marks\":[]",
                "\"marks\":[{\"kind\":\"teleport\",\"tick\":5}]"))));
        assertNull(ProgramChecker.verify(withHeaderText(h.replace("\"marks\":[]", "\"marks\":[{\"kind\":\"anchor\",\"tick\":5}]"))));
        // a mode from the M3 list is accepted and stored (it is only refused at start)
        String fishing = h.replace("\"mode\":\"custom\"", "\"mode\":\"fishing\"")
                .replace("\"params\":{\"repeat\":true,\"maxLoops\":0}", "\"params\":{}");
        assertNull(ProgramChecker.verify(withHeaderText(fishing)));
        // ... but with any params it fails closed until M3 defines a schema for the mode
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"mode\":\"custom\"", "\"mode\":\"fishing\""))));
        // the template mode must equal the mode
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"template\":{\"mode\":\"custom\"",
                "\"template\":{\"mode\":\"afk_still\""))));
        // afk_still needs an existing anchor
        assertEquals("bad_program", ProgramChecker.verify(withHeaderText(h.replace("\"mode\":\"custom\"", "\"mode\":\"afk_still\"")
                .replace("\"template\":{\"mode\":\"afk_still\",\"params\":{\"repeat\":true,\"maxLoops\":0}}",
                        "\"template\":{\"mode\":\"afk_still\",\"params\":{\"anchor\":\"a5\"}}"))));
    }

    @Test
    void headerAndEncodeLimits() {
        // header over 64 KiB cannot be written by the encoder and is refused by the decoder
        assertEquals("bad_program", code(() -> FarmRecording.decode(
                ByteBuffer.allocate(9).put(new byte[] {'Q', 'F', 'P', '1', 1}).putInt(65_537).array())));
        FarmRecording ok = FarmRecording.decode(base());
        assertEquals("bad_program", code(() -> FarmRecording.of(new FarmRecording.Header(1, "x".repeat(40), "", "", "custom",
                "minecraft:overworld", ok.header().template(), 60, ok.header().loop(), ok.header().anchors(), List.of(),
                List.of(), ok.header().steps(), List.of(), List.of(), List.of(), List.of()), List.of())));
        // an out-of-range record is refused at encode time too
        FarmRecording.TrackRecord pastEnd = new FarmRecording.TrackRecord(60, 1, null, null, null, null, null, null, null, false);
        assertEquals("bad_track", code(() -> FarmRecording.of(ok.header(), List.of(pastEnd))));
        FarmRecording.TrackRecord a = new FarmRecording.TrackRecord(5, 1, null, null, null, null, null, null, null, false);
        FarmRecording.TrackRecord b = new FarmRecording.TrackRecord(5, 0, null, null, null, null, null, null, null, false);
        assertEquals("bad_track", code(() -> FarmRecording.of(ok.header(), List.of(a, b))), "ticks must strictly increase");
    }

    @Test
    void trailingAndTruncatedTrackBytesAreBadTrack() {
        byte[] b = base();
        byte[] plus = java.util.Arrays.copyOf(b, b.length + 1); // a lone 0x00 = tickDelta 0 then no flags
        assertEquals("bad_track", ProgramChecker.verify(plus));
        byte[] minus = java.util.Arrays.copyOf(b, b.length - 1);
        assertEquals("bad_track", ProgramChecker.verify(minus));
        assertEquals("bad_program", ProgramChecker.verify(null));
        assertEquals("bad_program", ProgramChecker.verify(new byte[0]));
        assertEquals("too_big", ProgramChecker.verify(new byte[FarmRecording.MAX_PLAIN_BYTES + 1]));
    }

    @Test
    void headerWithInvalidUtf8IsBadProgram() {
        byte[] b = base();
        b[9 + 5] = (byte) 0xFF; // inside the header text
        assertEquals("bad_program", ProgramChecker.verify(b));
    }

    // ---------- contract wiring ----------

    @Test
    void theHeaderCarriesTheCoreVersionTheContractNames() {
        assertEquals("1.1.0", FarmRecording.CORE_VERSION);
        assertTrue(headerText().contains("\"core\":\"1.1.0\""));
    }

    @Test
    void releaseVectorFileMatchesTheTestResourceWhenPresent() throws Exception {
        Path release = Path.of("..", "..", "qwerte-release", "farm-vectors.json").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(release), "no qwerte-release folder next to the repo");
        assertArrayEquals(Files.readAllBytes(release), Vectors.raw(),
                "src/test/resources/.../farm-vectors.json must be a copy of qwerte-release/farm-vectors.json");
    }
}
