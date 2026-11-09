package com.porter.qwerte.printer.schematic;

import com.mojang.datafixers.util.Pair;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Reads the bytes of a .litematic into a {@link SchematicData}, refusing anything beyond the limits below. Pure (only
 * Minecraft's NBT classes, which need no bootstrap). Values are read through NbtOps because CompoundTag's typed getters
 * return Optionals on 1.21.11 and plain values on 1.21.4. The packed block storage is kept as the file's long[]. Meant
 * for a worker thread: the deadline is checked while inflating and between regions and palette entries.
 */
public final class LitematicReader {
    private LitematicReader() {
    }

    /** Compressed file, checked before anything is inflated. */
    public static final long MAX_COMPRESSED = 64L << 20;
    /** Uncompressed NBT: a gzip bomb is cut off here. */
    public static final long MAX_INFLATED = 256L << 20;
    /** The NbtAccounter's quota (an estimate of the heap the parsed tags take) and nesting depth. */
    public static final long MAX_HEAP = 192L << 20;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_REGIONS = 64;
    /** Cells per region and across all regions. */
    public static final long MAX_VOLUME = 1L << 25;
    public static final int MAX_PALETTE = 65_536;
    public static final int MAX_NAME = 256;
    public static final int MAX_PROPERTIES = 64;
    public static final int MIN_VERSION = 4;
    /** Data version of 1.13 (the flattening); older block palettes are not something the data fixer is used for. */
    public static final int MIN_DATA_VERSION = 1139;
    public static final int MAX_VERSION = 7;
    private static final int MAX_COORD = 1 << 28;
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Pattern PROP = Pattern.compile("[a-z0-9_]{1,64}");

    /** Why a file was refused; the message is fit for chat. */
    public static final class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }
    }

    /** Reads a .litematic's bytes (gzip NBT). {@code deadlineNanos} is a {@link System#nanoTime()} value. */
    public static SchematicData read(byte[] gzip, long deadlineNanos) throws Failure {
        if (gzip == null || gzip.length == 0) throw new Failure("The file is empty.");
        if (gzip.length > MAX_COMPRESSED) throw new Failure("The file is larger than " + (MAX_COMPRESSED >> 20) + " MiB.");
        CompoundTag root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new Limited(new GZIPInputStream(new ByteArrayInputStream(gzip)), deadlineNanos)))) {
            root = NbtIo.read(in, new NbtAccounter(MAX_HEAP, MAX_DEPTH));
        } catch (TooLarge e) {
            throw new Failure("The file is far larger than a schematic should be once unpacked (over " + (MAX_INFLATED >> 20) + " MiB).");
        } catch (TimedOut e) {
            throw new Failure("Reading the file took too long.");
        } catch (ZipException e) {
            throw new Failure("That is not a .litematic file (not gzip data).");
        } catch (EOFException e) {
            throw new Failure("The file is cut short (truncated).");
        } catch (IOException | RuntimeException e) {
            throw new Failure("The file is damaged or too deeply nested to read.");
        }
        return parse(root, deadlineNanos);
    }

    /** Checks and converts the root tag of a .litematic. */
    public static SchematicData parse(CompoundTag root, long deadlineNanos) throws Failure {
        if (root == null) throw new Failure("The file has no data.");
        Long versionValue = number(root.get("Version"));
        if (versionValue == null) throw new Failure("That is not a Litematica schematic (no Version).");
        int version = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, versionValue));
        if (version < MIN_VERSION) {
            throw new Failure("This schematic is from an old Litematica (version " + version + "); open it in Litematica once and save it again.");
        }
        List<String> warnings = new ArrayList<>();
        if (version > MAX_VERSION) {
            warnings.add("The schematic is Litematica format version " + version + ", newer than the " + MAX_VERSION + " this was written for; trying anyway.");
        }
        Long dv = number(root.get("MinecraftDataVersion"));
        int dataVersion = dv == null || dv < 0 || dv > Integer.MAX_VALUE ? 0 : dv.intValue();
        if (dataVersion > 0 && dataVersion < MIN_DATA_VERSION) throw new Failure("This schematic was made for a Minecraft older than 1.13 (data version " + dataVersion + "); it is too old.");

        Map<String, Tag> regionTags = entries(root.get("Regions"), MAX_REGIONS);
        if (regionTags == null) throw new Failure("The schematic has no regions, or more than " + MAX_REGIONS + ".");

        String name = "";
        String author = "";
        if (root.get("Metadata") instanceof CompoundTag meta) {
            name = clean(string(meta.get("Name")), 64);
            author = clean(string(meta.get("Author")), 64);
        }

        List<SchematicData.Region> regions = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Tag> e : new java.util.TreeMap<>(regionTags).entrySet()) { // by name, so the order does not depend on the file's hashing
            checkDeadline(deadlineNanos);
            String regionName = e.getKey();
            if (regionName.isEmpty() || regionName.length() > 128 || hasControl(regionName)) throw new Failure("A region has an unusable name.");
            String where = "Region \"" + clean(regionName, 40) + "\"";
            if (!(e.getValue() instanceof CompoundTag region)) throw new Failure(where + " is not a compound.");
            int[] size = xyz(region.get("Size"));
            if (size == null) throw new Failure(where + " has no valid Size.");
            int[] pos = xyz(region.get("Position"));
            if (pos == null) throw new Failure(where + " has no valid Position.");
            for (int a = 0; a < 3; a++) {
                if (Math.abs((long) pos[a]) > MAX_COORD) throw new Failure(where + " is positioned too far out.");
            }
            long volume = Math.abs((long) size[0]) * Math.abs((long) size[1]);
            if (volume > MAX_VOLUME) throw tooBig();
            volume *= Math.abs((long) size[2]);
            if (volume > MAX_VOLUME) throw tooBig();
            total += volume;
            if (total > MAX_VOLUME) throw tooBig();
            if (volume == 0) {
                warnings.add(where + " is empty and was left out.");
                continue;
            }

            Tag paletteTag = region.get("BlockStatePalette");
            if (!(paletteTag instanceof ListTag paletteList) || paletteList.isEmpty()) throw new Failure(where + " has no block palette.");
            if (paletteList.size() > MAX_PALETTE || paletteList.size() > volume) throw new Failure(where + " has a palette that is too large.");
            String[] palette = new String[paletteList.size()];
            for (int i = 0; i < palette.length; i++) {
                if ((i & 1023) == 0) checkDeadline(deadlineNanos);
                palette[i] = paletteEntry(paletteList.get(i), where);
            }
            int bits = LitematicFormat.bitsFor(palette.length);
            if (!(region.get("BlockStates") instanceof LongArrayTag states)) throw new Failure(where + " has no block data.");
            long[] data = states.getAsLongArray();
            long expected = LitematicFormat.expectedLength(volume, bits);
            if (data.length != expected) {
                throw new Failure(where + " has " + data.length + " data words but its size and palette need " + expected + ".");
            }
            regions.add(new SchematicData.Region(regionName, pos[0], pos[1], pos[2], size[0], size[1], size[2], palette, data, bits));
        }
        if (regions.isEmpty()) throw new Failure("The schematic has no blocks.");
        return new SchematicData(name, author, version, dataVersion, regions, total, warnings);
    }

    private static Failure tooBig() {
        return new Failure("The schematic is larger than " + MAX_VOLUME + " blocks.");
    }

    private static String paletteEntry(Tag tag, String where) throws Failure {
        if (!(tag instanceof CompoundTag c)) throw new Failure(where + " has a palette entry that is not a compound.");
        String name = string(c.get("Name"));
        if (name == null || name.length() > MAX_NAME || !BLOCK_ID.matcher(name).matches()) throw new Failure(where + " has a palette entry with an invalid block id.");
        Map<String, String> props = new LinkedHashMap<>();
        Tag propsTag = c.get("Properties");
        if (propsTag != null) {
            Map<String, Tag> map = entries(propsTag, MAX_PROPERTIES);
            if (map == null) throw new Failure(where + " has a palette entry with invalid properties.");
            for (Map.Entry<String, Tag> p : map.entrySet()) {
                String value = string(p.getValue());
                if (!PROP.matcher(p.getKey()).matches() || value == null || !PROP.matcher(value).matches()) {
                    throw new Failure(where + " has a palette entry with an invalid property.");
                }
                props.put(p.getKey(), value);
            }
        }
        return LitematicFormat.stateKey(name, props);
    }

    // ---------- NBT access through NbtOps ----------

    /** A compound's entries (at most {@code max}), or null when the tag is not a compound or has more. */
    static Map<String, Tag> entries(Tag tag, int max) {
        if (!(tag instanceof CompoundTag c) || c.size() > max) return null;
        Map<String, Tag> out = new LinkedHashMap<>();
        try (Stream<Pair<Tag, Tag>> s = NbtOps.INSTANCE.getMapValues(tag).result().orElse(Stream.empty())) {
            s.forEach(p -> {
                String key = string(p.getFirst());
                if (key != null) out.put(key, p.getSecond());
            });
        }
        return out;
    }

    static Long number(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getNumberValue(tag).result().map(Number::longValue).orElse(null);
    }

    static String string(Tag tag) {
        if (tag == null) return null;
        return NbtOps.INSTANCE.getStringValue(tag).result().orElse(null);
    }

    /** A compound of x, y and z as ints; null when anything is missing, of the wrong type or out of range. */
    private static int[] xyz(Tag tag) {
        if (!(tag instanceof CompoundTag c)) return null;
        int[] out = new int[3];
        String[] axes = { "x", "y", "z" };
        for (int a = 0; a < 3; a++) {
            Long v = number(c.get(axes[a]));
            if (v == null || v < Integer.MIN_VALUE + 1 || v > Integer.MAX_VALUE) return null;
            out[a] = v.intValue();
        }
        return out;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) return true;
        return false;
    }

    /**
     * Text from a file or from Litematica made safe to show in chat: control characters and the section sign dropped, '%'
     * dropped (Meteor's ChatUtils runs messages through String.format), and parentheses turned into brackets because
     * ChatUtils.formatMsg reads "(default)", "(highlight)", "(underline)" and "(bold)" as styling. Cut to {@code max}.
     */
    public static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7f || ch == '§' || ch == '%') continue;
            int type = Character.getType(ch);
            // other control, format (bidi overrides, zero width), line and paragraph separators, unassigned and lone surrogates
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) continue;
            sb.append(ch == '(' ? '[' : ch == ')' ? ']' : ch);
        }
        return sb.toString();
    }

    private static void checkDeadline(long deadlineNanos) throws Failure {
        if (Thread.interrupted()) throw new Failure("Reading the file was cancelled.");
        if (System.nanoTime() - deadlineNanos > 0) throw new Failure("Reading the file took too long.");
    }

    // ---------- the inflated stream: counted and timed ----------

    private static final class TooLarge extends IOException {
    }

    private static final class TimedOut extends IOException {
    }

    private static final class Limited extends FilterInputStream {
        private final long deadline;
        private long count;

        Limited(InputStream in, long deadline) {
            super(in);
            this.deadline = deadline;
        }

        private void note(int n) throws IOException {
            if (n > 0 && (count += n) > MAX_INFLATED) throw new TooLarge();
            if (Thread.interrupted() || System.nanoTime() - deadline > 0) throw new TimedOut();
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) note(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            note(n);
            return n;
        }
    }
}
