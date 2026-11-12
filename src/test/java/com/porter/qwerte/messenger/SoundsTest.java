package com.porter.qwerte.messenger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The custom notification sounds: file safety, the WAV reader, the cache, and the fallback with one warning. */
class SoundsTest {
    @TempDir
    Path dir;

    // ---------- making WAV files ----------

    private static byte[] wav(int channels, int bits, int rate, int frames) {
        int frameBytes = channels * bits / 8;
        byte[] data = new byte[frames * frameBytes];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (bits == 8 ? 128 + (i % 50) : (i * 7));
        return wav(1, channels, bits, rate, data, data.length);
    }

    private static byte[] wav(int format, int channels, int bits, int rate, byte[] data, long declaredDataSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        le(o, 36 + data.length, 4);
        o.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        o.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        le(o, 16, 4);
        le(o, format, 2);
        le(o, channels, 2);
        le(o, rate, 4);
        le(o, rate * channels * bits / 8, 4);
        le(o, channels * bits / 8, 2);
        le(o, bits, 2);
        o.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        le(o, declaredDataSize, 4);
        o.writeBytes(data);
        return o.toByteArray();
    }

    private static void le(ByteArrayOutputStream o, long v, int n) {
        for (int i = 0; i < n; i++) o.write((int) (v >> (8 * i)) & 0xFF);
    }

    private static final SoundLibrary.OggDecoder NO_OGG = (data, max) -> {
        throw new SoundException("not a valid Ogg Vorbis file");
    };

    private Path put(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    // ---------- names ----------

    @Test
    void onlyPlainNamesOfSupportedFilesAreValid() {
        assertTrue(SoundFiles.validName("ding.ogg"));
        assertTrue(SoundFiles.validName("My Sound 2.WAV"));
        assertFalse(SoundFiles.validName(null));
        assertFalse(SoundFiles.validName(""));
        assertFalse(SoundFiles.validName("ding.mp3"));
        assertFalse(SoundFiles.validName("sub/ding.ogg"));
        assertFalse(SoundFiles.validName("sub\\ding.ogg"));
        assertFalse(SoundFiles.validName("../ding.ogg"));
        assertFalse(SoundFiles.validName("..ding.ogg"));
        assertFalse(SoundFiles.validName(".hidden.ogg"));
        assertFalse(SoundFiles.validName("C:\\x\\ding.ogg"));
        assertFalse(SoundFiles.validName("C:ding.ogg"));
        assertFalse(SoundFiles.validName("/etc/passwd.ogg"));
        assertFalse(SoundFiles.validName("a\u0000b.ogg"));
        assertFalse(SoundFiles.validName("a".repeat(SoundFiles.MAX_NAME) + ".ogg"));
        assertFalse(SoundFiles.validName("ding.ogg "));
        assertFalse(SoundFiles.validName("ding.ogg."));
    }

    @Test
    void theListHasOnlyUsableFilesInTheFolder() throws IOException {
        put("b.wav", wav(1, 16, 8000, 100));
        put("a.ogg", new byte[] {1});
        put("notes.txt", new byte[] {1});
        put("README.txt", new byte[] {1});
        Files.createDirectory(dir.resolve("sub.ogg")); // a folder is not a file
        Files.createDirectory(dir.resolve("sub"));
        Files.write(dir.resolve("sub").resolve("deep.ogg"), new byte[] {1});
        assertEquals(List.of("a.ogg", "b.wav"), SoundFiles.list(dir));
        String[] choices = SoundFiles.choices(dir);
        assertArrayEquals(new String[] {"Built-in", "None", "a.ogg", "b.wav"}, choices);
        assertEquals(List.of(), SoundFiles.list(dir.resolve("missing")));
    }

    @Test
    void theFolderAndReadmeAreMade() {
        Path folder = dir.resolve("qwerte").resolve("sounds");
        SoundFiles.ensureFolder(folder);
        assertTrue(Files.isDirectory(folder));
        assertTrue(Files.isRegularFile(folder.resolve("README.txt")));
        SoundFiles.ensureFolder(folder); // again: nothing breaks
        assertEquals(List.of(), SoundFiles.list(folder));
    }

    // ---------- resolving ----------

    @Test
    void resolveRefusesWhatIsNotInTheListing() throws IOException, SoundException {
        put("ok.wav", wav(1, 16, 8000, 100));
        assertEquals("ok.wav", SoundFiles.resolve(dir, "ok.wav").name());
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "missing.wav"));
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "../ok.wav"));
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, dir.resolve("ok.wav").toString()));
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "OK.WAV")); // only the exact name of a listed file
    }

    @Test
    void resolveRefusesFilesOverTwoMebibytesAndEmptyOnes() throws IOException, SoundException {
        put("big.wav", new byte[(int) SoundFiles.MAX_BYTES + 1]);
        put("edge.wav", new byte[(int) SoundFiles.MAX_BYTES]);
        put("empty.wav", new byte[0]);
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "big.wav"));
        assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "empty.wav"));
        assertEquals(SoundFiles.MAX_BYTES, SoundFiles.resolve(dir, "edge.wav").size());
    }

    @Test
    void aSymbolicLinkOutOfTheFolderIsRefused() throws IOException {
        Path outside = Files.createTempDirectory("qwerte-outside");
        try {
            Path target = outside.resolve("secret.wav");
            Files.write(target, wav(1, 16, 8000, 100));
            Path link = dir.resolve("link.wav");
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                assumeTrue(false, "symbolic links can not be made here: " + e);
            }
            SoundException e = assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "link.wav"));
            assertTrue(e.getMessage().contains("outside"), e.getMessage());
        } finally {
            Files.deleteIfExists(outside.resolve("secret.wav"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aSymbolicLinkToAFolderOutsideIsRefused() throws IOException {
        Path outside = Files.createTempDirectory("qwerte-outside");
        try {
            Files.write(outside.resolve("inner.wav"), wav(1, 16, 8000, 100));
            try {
                Files.createSymbolicLink(dir.resolve("linkdir"), outside);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                assumeTrue(false, "symbolic links can not be made here: " + e);
            }
            // the link itself is not a listed file, and a path through it is not a plain name
            assertThrows(SoundException.class, () -> SoundFiles.resolve(dir, "linkdir/inner.wav"));
            assertEquals(List.of(), SoundFiles.list(dir));
        } finally {
            Files.deleteIfExists(outside.resolve("inner.wav"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aSymbolicLinkInsideTheFolderIsFine() throws IOException, SoundException {
        Path real = put("real.wav", wav(1, 16, 8000, 100));
        try {
            Files.createSymbolicLink(dir.resolve("alias.wav"), real);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links can not be made here: " + e);
        }
        assertEquals(real.toRealPath(), SoundFiles.resolve(dir, "alias.wav").real());
    }

    // ---------- the WAV reader ----------

    @Test
    void readsPcmMonoAndStereoEightAndSixteenBit() throws SoundException {
        Pcm a = WavDecoder.decode(wav(1, 16, 22050, 1000), 10);
        assertEquals(1, a.channels());
        assertEquals(22050, a.rate());
        assertEquals(1000, a.samples().length);
        Pcm b = WavDecoder.decode(wav(2, 16, 44100, 500), 10);
        assertEquals(2, b.channels());
        assertEquals(1000, b.samples().length);
        Pcm c = WavDecoder.decode(wav(1, 8, 8000, 400), 10);
        assertEquals(400, c.samples().length);
        Pcm d = WavDecoder.decode(wav(2, 8, 8000, 400), 10);
        assertEquals(800, d.samples().length);
    }

    @Test
    void sixteenBitSamplesAreLittleEndianSigned() throws SoundException {
        byte[] data = {(byte) 0x34, (byte) 0x12, (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0x80};
        Pcm p = WavDecoder.decode(wav(1, 1, 16, 8000, data, data.length), 10);
        assertArrayEquals(new short[] {0x1234, -1, Short.MIN_VALUE}, p.samples());
    }

    @Test
    void eightBitSamplesAreUnsignedAndCentredOnZero() throws SoundException {
        byte[] data = {(byte) 128, (byte) 255, 0};
        Pcm p = WavDecoder.decode(wav(1, 1, 8, 8000, data, data.length), 10);
        assertArrayEquals(new short[] {0, 127 << 8, -128 << 8}, p.samples());
    }

    @Test
    void refusesWhatIsNotPlainPcm() {
        assertThrows(SoundException.class, () -> WavDecoder.decode(new byte[] {1, 2, 3}, 10));
        assertThrows(SoundException.class, () -> WavDecoder.decode("RIFFxxxxWAVEjunk".getBytes(StandardCharsets.US_ASCII), 10));
        assertThrows(SoundException.class, () -> WavDecoder.decode(wav(3, 1, 32, 8000, new byte[8], 8), 10)); // float
        assertThrows(SoundException.class, () -> WavDecoder.decode(wav(1, 1, 24, 8000, new byte[9], 9), 10)); // 24 bit
        assertThrows(SoundException.class, () -> WavDecoder.decode(wav(1, 6, 16, 8000, new byte[24], 24), 10)); // 6 channels
        assertThrows(SoundException.class, () -> WavDecoder.decode(wav(1, 1, 16, 100, new byte[8], 8), 10)); // silly rate
        assertThrows(SoundException.class, () -> WavDecoder.decode(wav(1, 1, 16, 8000, new byte[0], 0), 10)); // no samples
    }

    @Test
    void refusesLongerThanTheLimitBeforeMakingTheSamples() {
        SoundException e = assertThrows(SoundException.class, () -> WavDecoder.decode(wav(1, 8, 8000, 8000 * 10 + 1), 10));
        assertTrue(e.getMessage().contains("10 seconds"));
        assertDoesNotThrow(() -> WavDecoder.decode(wav(1, 8, 8000, 8000 * 10), 10));
    }

    @Test
    void aSizeFieldThatClaimsTooMuchIsCutToTheFile() throws SoundException {
        byte[] data = new byte[200];
        Pcm p = WavDecoder.decode(wav(1, 1, 16, 8000, data, 0xFFFFFFFFL), 10);
        assertEquals(100, p.samples().length);
    }

    // ---------- the library ----------

    @Test
    void decodesOnceAndAgainOnlyWhenTheFileChanged() throws IOException {
        AtomicInteger decodes = new AtomicInteger();
        SoundLibrary lib = new SoundLibrary(dir, (d, m) -> {
            decodes.incrementAndGet();
            return new Pcm(new short[] {1, 2, 3}, 1, 8000);
        });
        Path f = put("a.ogg", new byte[] {1, 2, 3, 4});
        SoundLibrary.Loaded first = lib.load("a.ogg");
        assertNotNull(first.pcm());
        assertSame(first, lib.load("a.ogg"));
        assertEquals(1, decodes.get());
        // a different size is a different file
        Files.write(f, new byte[] {1, 2, 3, 4, 5});
        SoundLibrary.Loaded second = lib.load("a.ogg");
        assertNotSame(first, second);
        assertNotEquals(first.identity(), second.identity());
        assertEquals(2, decodes.get());
        // the same size but a new time is a different file too
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
        lib.load("a.ogg");
        assertEquals(3, decodes.get());
        lib.load("a.ogg");
        assertEquals(3, decodes.get());
    }

    @Test
    void aFileThatFailedIsRememberedToo() throws IOException {
        AtomicInteger decodes = new AtomicInteger();
        SoundLibrary lib = new SoundLibrary(dir, (d, m) -> {
            decodes.incrementAndGet();
            throw new SoundException("not a valid Ogg Vorbis file");
        });
        put("bad.ogg", new byte[] {1, 2, 3});
        SoundLibrary.Loaded l = lib.load("bad.ogg");
        assertNull(l.pcm());
        assertEquals("not a valid Ogg Vorbis file", l.error());
        lib.load("bad.ogg");
        assertEquals(1, decodes.get());
    }

    @Test
    void failuresAreReportedNotThrown() throws IOException {
        long[] now = {1000};
        SoundLibrary lib = new SoundLibrary(dir, (d, m) -> {
            throw new IllegalStateException("boom");
        }, () -> now[0]);
        put("x.ogg", new byte[] {1, 2, 3});
        assertNotNull(lib.load("x.ogg").error());
        assertNotNull(lib.load("nothere.wav").error());
        assertNotNull(lib.load("../x.ogg").error());
        now[0] += 2000; // the listing is looked at again for names it lacks
        put("garbage.wav", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        assertEquals("not a WAV file", lib.load("garbage.wav").error());
        now[0] += 2000;
        put("long.wav", wav(1, 8, 8000, 8000 * 11));
        assertTrue(lib.load("long.wav").error().contains("10 seconds"));
    }

    // ---------- the fallback and the single warning ----------

    private static final class Recorder implements MessengerSounds.Out {
        final List<Float> builtin = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<Float> played = new ArrayList<>();
        boolean outputFails;

        @Override
        public void builtin(float volume) {
            builtin.add(volume);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void play(Pcm pcm, float gain) throws Exception {
            if (outputFails) throw new IllegalStateException("no mixer");
            played.add(gain);
        }
    }

    private MessengerSounds sounds(Recorder out) {
        return new MessengerSounds(new SoundLibrary(dir, NO_OGG), out, Runnable::run);
    }

    @Test
    void builtinNoneAndVolume() {
        Recorder r = new Recorder();
        MessengerSounds s = sounds(r);
        s.play("Built-in", 25, 1f);
        s.play(null, 50, 1f);
        s.play("None", 25, 1f);
        s.play("Built-in", 0, 1f);
        assertEquals(List.of(0.25f, 0.5f), r.builtin);
        assertTrue(r.played.isEmpty() && r.warnings.isEmpty());
    }

    @Test
    void aFilePlaysAtTheVolumeTimesTheMasterVolume() throws IOException {
        put("ok.wav", wav(1, 16, 8000, 100));
        Recorder r = new Recorder();
        sounds(r).play("ok.wav", 50, 0.5f);
        assertEquals(List.of(0.25f), r.played);
        assertTrue(r.builtin.isEmpty() && r.warnings.isEmpty());
        sounds(r).play("ok.wav", 50, 0f); // the game is muted: nothing at all
        assertEquals(1, r.played.size());
    }

    @Test
    void aBrokenFilePlaysTheBlipAndWarnsOnce() throws IOException {
        put("bad.wav", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        Recorder r = new Recorder();
        MessengerSounds s = sounds(r);
        s.play("bad.wav", 25, 1f);
        s.play("bad.wav", 25, 1f);
        s.play("bad.wav", 25, 1f);
        assertEquals(3, r.builtin.size()); // a blip each time
        assertEquals(1, r.warnings.size()); // one warning
        assertTrue(r.warnings.get(0).contains("bad.wav"), r.warnings.get(0));
    }

    @Test
    void aMissingFileWarnsOnceAndAgainWhenTheSettingChanges() {
        Recorder r = new Recorder();
        MessengerSounds s = sounds(r);
        s.play("gone.ogg", 25, 1f);
        s.play("gone.ogg", 25, 1f);
        assertEquals(1, r.warnings.size());
        s.play("other.ogg", 25, 1f); // another value: told again
        assertEquals(2, r.warnings.size());
        assertTrue(r.warnings.get(1).contains("other.ogg"));
        s.play("other.ogg", 25, 1f);
        assertEquals(2, r.warnings.size());
        s.resetWarning(); // the setting was set again
        s.play("other.ogg", 25, 1f);
        assertEquals(3, r.warnings.size());
        assertEquals(5, r.builtin.size());
    }

    @Test
    void aFileThatChangesIsToldAgainIfStillBad() throws IOException {
        Path f = put("bad.wav", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        Recorder r = new Recorder();
        MessengerSounds s = sounds(r);
        s.play("bad.wav", 25, 1f);
        s.play("bad.wav", 25, 1f);
        assertEquals(1, r.warnings.size());
        Files.write(f, new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        s.play("bad.wav", 25, 1f);
        assertEquals(2, r.warnings.size());
        // fixed: it plays, with no warning
        Files.write(f, wav(1, 16, 8000, 100));
        s.play("bad.wav", 25, 1f);
        assertEquals(1, r.played.size());
        assertEquals(2, r.warnings.size());
    }

    @Test
    void noAudioOutputFallsBackToTheBlipWithOneWarning() throws IOException {
        put("ok.wav", wav(1, 16, 8000, 100));
        Recorder r = new Recorder();
        r.outputFails = true;
        MessengerSounds s = sounds(r);
        s.play("ok.wav", 25, 1f);
        s.play("ok.wav", 25, 1f);
        assertEquals(2, r.builtin.size());
        assertEquals(1, r.warnings.size());
        assertTrue(r.warnings.get(0).contains("ok.wav"));
    }

    @Test
    void aTooBigFileIsRefusedWithTheBlip() throws IOException {
        put("big.wav", new byte[(int) SoundFiles.MAX_BYTES + 10]);
        Recorder r = new Recorder();
        sounds(r).play("big.wav", 25, 1f);
        assertEquals(1, r.builtin.size());
        assertTrue(r.warnings.get(0).contains("2 MiB"), r.warnings.get(0));
    }

    // ---------- the console sound's rate limit ----------

    @Test
    void theGateLetsOneThroughPerTwoSeconds() {
        SoundGate g = new SoundGate(2000);
        assertTrue(g.pass(10_000));
        assertFalse(g.pass(10_001));
        assertFalse(g.pass(11_999));
        assertTrue(g.pass(12_000));
        assertFalse(g.pass(13_000));
        assertTrue(g.pass(5_000)); // the clock went back
    }

    // ---------- the listing cap, resolving beyond the dropdown, the listing cache ----------

    @Test
    void theListingStopsAtTheScanCap() throws IOException {
        for (int i = 0; i < 120; i++) put(String.format("s%03d.wav", i), new byte[] {1});
        assertEquals(50, SoundFiles.listAll(dir, 50).size());
        assertEquals(120, SoundFiles.listAll(dir, SoundFiles.MAX_SCANNED).size());
        assertEquals(0, SoundFiles.listAll(dir, 0).size());
    }

    @Test
    void theDropdownShowsTwoHundredButASavedNameBeyondItStillResolves() throws IOException, SoundException {
        for (int i = 0; i < 230; i++) put(String.format("a%03d.wav", i), wav(1, 8, 8000, 10));
        put("zzz-last.wav", wav(1, 8, 8000, 10));
        List<String> shown = SoundFiles.list(dir);
        assertEquals(SoundFiles.MAX_LISTED, shown.size());
        assertFalse(shown.contains("zzz-last.wav"));
        assertEquals("zzz-last.wav", SoundFiles.resolve(dir, "zzz-last.wav").name());
        assertNotNull(new SoundLibrary(dir, NO_OGG).load("zzz-last.wav").pcm());
        assertEquals(SoundFiles.MAX_LISTED + 2, SoundFiles.choices(dir).length);
    }

    @Test
    void aWholeFloodOfLoadsScansTheFolderOnce() throws IOException {
        put("a.wav", wav(1, 8, 8000, 10));
        long[] now = {1000};
        SoundLibrary lib = new SoundLibrary(dir, NO_OGG, () -> now[0]);
        for (int i = 0; i < 50; i++) assertNotNull(lib.load("a.wav").pcm());
        assertEquals(1, lib.scans());
        // an unchanged, already decoded sound does not even need the listing again
        now[0] += 60_000;
        assertNotNull(lib.load("a.wav").pcm());
        assertEquals(1, lib.scans());
    }

    @Test
    void theListingIsScannedAgainWhenOldOrWhenANewFileIsLookedFor() throws IOException {
        put("a.wav", wav(1, 8, 8000, 10));
        long[] now = {1000};
        SoundLibrary lib = new SoundLibrary(dir, NO_OGG, () -> now[0]);
        assertNotNull(lib.load("a.wav").pcm());
        put("b.wav", wav(1, 8, 8000, 10));
        now[0] += 200; // a young listing is believed: b is not there yet
        assertNull(lib.load("b.wav").pcm());
        assertEquals(1, lib.scans());
        now[0] += 1000; // an older one is looked at again for a name it lacks
        assertNotNull(lib.load("b.wav").pcm());
        assertEquals(2, lib.scans());
        // a file that changed is checked against a listing again once that is old
        put("a.wav", wav(1, 8, 8000, 20));
        now[0] += SoundLibrary.LISTING_MS + 1;
        assertNotNull(lib.load("a.wav").pcm());
        assertEquals(3, lib.scans());
    }

    @Test
    void theGateForWhispersIsAQuarterOfASecond() {
        SoundGate g = new SoundGate(250);
        assertTrue(g.pass(1000));
        assertFalse(g.pass(1100));
        assertFalse(g.pass(1249));
        assertTrue(g.pass(1250));
    }

    // ---------- the warning text ----------

    @Test
    void theWarningShowsOnlyAValidShortNameWithoutMarkup() {
        assertEquals("ding.ogg", SoundWarning.shownName("ding.ogg"));
        assertEquals("(invalid name)", SoundWarning.shownName("../x.ogg"));
        assertEquals("(invalid name)", SoundWarning.shownName(null));
        assertEquals("(invalid name)", SoundWarning.shownName("a§b.mp3"));
        // Meteor's chat reads (highlight), (bold), ... as colours: no round brackets get through
        String n = SoundWarning.shownName("(highlight)(bold)x.ogg");
        assertFalse(n.contains("(") || n.contains(")"), n);
        String longName = "n".repeat(60) + ".ogg";
        String cut = SoundWarning.shownName(longName);
        assertEquals(SoundWarning.MAX_NAME, cut.length());
        assertTrue(cut.endsWith("..."));
        assertEquals("a".repeat(36) + ".ogg", SoundWarning.shownName("a".repeat(36) + ".ogg")); // exactly 40 stays
        String m = SoundWarning.message("(bold).wav", "it is empty");
        assertFalse(m.contains("(bold)"));
        assertTrue(m.contains("it is empty") && m.contains("built-in"));
    }
}
