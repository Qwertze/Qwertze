package com.porter.qwerte.messenger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Loads the custom sounds from the sounds folder and keeps what was decoded, keyed by name, size and modified time, so
 * a file is decoded once and again only after it changed. A file that could not be used is kept too (with the reason),
 * so it is not decoded again at every whisper. The folder listing is kept for a few seconds, so a flood of whispers
 * does not scan the folder each time, and it is not needed at all for a sound that is already decoded and unchanged.
 * {@link #load} reads and decodes: call it off the client thread. No Minecraft classes.
 */
public final class SoundLibrary {
    /** Decodes Ogg Vorbis (the game's own LWJGL STB; kept out of here so this can be tested without natives). */
    public interface OggDecoder {
        Pcm decode(byte[] data, double maxSeconds) throws SoundException;
    }

    /** What loading a name gave: the sound, or null and why not; and the identity a warning is kept under. */
    public record Loaded(Pcm pcm, String error, String identity) {
    }

    private static final int CACHE_SIZE = 16;
    /** The listing is used for this long. */
    static final long LISTING_MS = 5000;
    /** A name that is not in a listing this old is looked for again (a file that was just dropped in); a younger one is believed. */
    static final long RELIST_MISS_MS = 1000;

    private final Path dir;
    private final OggDecoder ogg;
    private final LongSupplier clock;
    private final Map<String, Loaded> cache = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Loaded> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private Set<String> listing;
    private long listedAt;
    private int scans;

    public SoundLibrary(Path dir, OggDecoder ogg) {
        this(dir, ogg, System::currentTimeMillis);
    }

    SoundLibrary(Path dir, OggDecoder ogg, LongSupplier clock) {
        this.dir = dir;
        this.ogg = ogg;
        this.clock = clock;
    }

    /** How many times the folder was scanned (for tests). */
    synchronized int scans() {
        return scans;
    }

    public synchronized Loaded load(String name) {
        SoundFiles.Resolved r;
        try {
            r = SoundFiles.stat(dir, name);
        } catch (SoundException e) {
            return new Loaded(null, e.getMessage(), name + "|" + e.getMessage());
        }
        Loaded cached = cache.get(name);
        if (cached != null && cached.identity().equals(r.identity())) return cached; // same file as when it was listed and decoded
        try {
            SoundFiles.requireListed(listing(name), name);
        } catch (SoundException e) {
            return new Loaded(null, e.getMessage(), name + "|" + e.getMessage());
        }
        Loaded l;
        try {
            byte[] data = SoundFiles.read(r);
            Pcm pcm = SoundFiles.isOgg(name) ? ogg.decode(data, SoundFiles.MAX_SECONDS) : WavDecoder.decode(data, SoundFiles.MAX_SECONDS);
            if (pcm.seconds() > SoundFiles.MAX_SECONDS) throw new SoundException("longer than " + (int) SoundFiles.MAX_SECONDS + " seconds");
            l = new Loaded(pcm, null, r.identity());
        } catch (SoundException e) {
            l = new Loaded(null, e.getMessage(), r.identity());
        } catch (IOException e) {
            l = new Loaded(null, "the file can not be read", r.identity());
        } catch (RuntimeException | LinkageError e) {
            l = new Loaded(null, "it could not be decoded", r.identity());
        }
        cache.put(name, l);
        return l;
    }

    /** The folder listing, scanned again when it is older than {@link #LISTING_MS}, or when it lacks the name and is older than {@link #RELIST_MISS_MS}. */
    private Set<String> listing(String name) throws SoundException {
        long now = clock.getAsLong();
        boolean fresh = listing != null && now >= listedAt && now - listedAt < LISTING_MS;
        if (fresh && (listing.contains(name) || now - listedAt < RELIST_MISS_MS)) return listing;
        Path real;
        try {
            real = dir.toRealPath();
        } catch (IOException e) {
            throw new SoundException("the sounds folder is missing");
        }
        scans++;
        listing = new HashSet<>(SoundFiles.listAll(real, SoundFiles.MAX_SCANNED));
        listedAt = now;
        return listing;
    }
}
