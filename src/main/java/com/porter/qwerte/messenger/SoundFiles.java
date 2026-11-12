package com.porter.qwerte.messenger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The folder of custom notification sounds ({@code meteor-client/qwerte/sounds}) and the checks every file must pass.
 * Only a plain file name that the folder's own listing has is used: no sub folders, separators, "..", drive letters or
 * absolute paths. The file is then resolved to its real path (links followed) and must still be inside the folder's
 * real path, so a symbolic link or junction can not lead out of it. No Minecraft classes.
 */
public final class SoundFiles {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final double MAX_SECONDS = 10;
    public static final int MAX_NAME = 100;
    /** The dropdown shows at most this many names. */
    public static final int MAX_LISTED = 200;
    /** Most directory entries looked at in one scan. */
    public static final int MAX_SCANNED = 5000;
    public static final String README = "README.txt";

    private SoundFiles() {
    }

    /** A file that passed the checks: its real path and the identity (name, size, time) the decoded sound is cached under. */
    public record Resolved(String name, Path real, long size, long mtime) {
        public String identity() {
            return name + "|" + size + "|" + mtime;
        }
    }

    /** A plain name of a supported file ({@code .ogg} or {@code .wav}): no separators, colons, "..", control characters or leading dot. */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
        if (name.startsWith(".") || name.endsWith(" ") || name.endsWith(".") || name.contains("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return isOgg(name) || isWav(name);
    }

    public static boolean isOgg(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".ogg");
    }

    public static boolean isWav(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".wav");
    }

    /** The dropdown's list: the usable file names, sorted, at most {@link #MAX_LISTED}. Only for showing; {@link #resolve} does not depend on it. */
    public static List<String> list(Path dir) {
        List<String> all = listAll(dir, MAX_SCANNED);
        return all.size() > MAX_LISTED ? new ArrayList<>(all.subList(0, MAX_LISTED)) : all;
    }

    /**
     * The usable file names in the folder (not its sub folders), sorted. At most {@code maxScanned} directory entries are
     * looked at (a folder with more has an arbitrary part of them listed). Empty when the folder is missing.
     */
    public static List<String> listAll(Path dir, int maxScanned) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            int seen = 0;
            for (Path p : ds) {
                if (++seen > maxScanned) break;
                Path fn = p.getFileName();
                if (fn == null) continue;
                String n = fn.toString();
                if (validName(n) && Files.isRegularFile(p)) names.add(n);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Checks the name, the listing (up to {@link #MAX_SCANNED} entries), the real path, the kind of file and the size. */
    public static Resolved resolve(Path dir, String name) throws SoundException {
        Resolved r = stat(dir, name);
        requireListed(listAll(realDirOf(dir), MAX_SCANNED), name);
        return r;
    }

    /** The exact name must be in the listing. */
    public static void requireListed(java.util.Collection<String> listing, String name) throws SoundException {
        if (!listing.contains(name)) throw new SoundException("not in the sounds folder");
    }

    private static Path realDirOf(Path dir) throws SoundException {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            throw new SoundException("the sounds folder is missing");
        }
    }

    /**
     * The name, the real path (it must stay inside the folder's real path), the kind of file and the size; without the
     * listing check, so a cached sound can be recognised first. Call {@link #requireListed} before using a new file.
     */
    public static Resolved stat(Path dir, String name) throws SoundException {
        if (!validName(name)) throw new SoundException("not a plain .ogg or .wav file name");
        Path realDir = realDirOf(dir);
        Path real;
        try {
            real = realDir.resolve(name).toRealPath();
        } catch (NoSuchFileException e) {
            throw new SoundException("the file is missing");
        } catch (IOException e) {
            throw new SoundException("the file can not be read");
        }
        if (!real.startsWith(realDir)) throw new SoundException("it points outside the sounds folder");
        try {
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw new SoundException("not a regular file");
            long size = Files.size(real);
            if (size > MAX_BYTES) throw new SoundException("bigger than 2 MiB");
            if (size <= 0) throw new SoundException("the file is empty");
            return new Resolved(name, real, size, Files.getLastModifiedTime(real).toMillis());
        } catch (IOException e) {
            throw new SoundException("the file can not be read");
        }
    }

    /** The bytes of a resolved file: at most {@link #MAX_BYTES} (a file that grew since it was checked is refused). */
    public static byte[] read(Resolved r) throws IOException, SoundException {
        try (InputStream in = Files.newInputStream(r.real(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = in.readNBytes((int) MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new SoundException("bigger than 2 MiB");
            return data;
        }
    }

    /** What the setting offers: Built-in, None, then the files. */
    public static String[] choices(Path dir) {
        List<String> files = list(dir);
        String[] out = new String[files.size() + 2];
        out[0] = MessengerSounds.BUILTIN;
        out[1] = MessengerSounds.NONE;
        for (int i = 0; i < files.size(); i++) out[i + 2] = files.get(i);
        return out;
    }

    /** Makes the folder and, if there is none, a short README.txt in it. Failures are ignored (the sounds then just are not found). */
    public static void ensureFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve(README);
            if (!Files.exists(readme)) Files.write(readme, readmeText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // no folder, no custom sounds
        }
    }

    static String readmeText() {
        return String.join("\r\n",
            "Qwerte Messenger: your own notification sounds",
            "",
            "Put sound files here, then pick one in the Messenger module:",
            "  dm-sound       plays when a whisper arrives",
            "  console-sound  plays when a new Console line arrives (separate-console on)",
            "  sound-volume   how loud (the game's master volume applies too)",
            "",
            "Files:",
            "  .ogg  (Ogg Vorbis)",
            "  .wav  (PCM, 8 or 16 bit, mono or stereo)",
            "",
            "Limits: at most 2 MiB and 10 seconds. Only files directly in this folder are used (no sub folders).",
            "A name must be plain: letters, digits, spaces, dashes, dots and underscores.",
            "If a file is missing or can not be played, the built-in sound plays instead and the Messenger says so once in chat.",
            "");
    }
}
