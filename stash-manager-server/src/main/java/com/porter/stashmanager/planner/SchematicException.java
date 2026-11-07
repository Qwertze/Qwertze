package com.porter.stashmanager.planner;

/**
 * A schematic could not be read. The message is short and safe to show to the user: it never contains raw file bytes,
 * and tag names in it are cut to 64 characters.
 */
public final class SchematicException extends Exception {
    /** Why the file was rejected, for the HTTP status / UI wording. */
    public enum Code {
        /** Malformed or truncated NBT, or a schematic whose fields are inconsistent. */
        INVALID,
        /** Over one of the size limits (decompressed bytes, tags, depth, volume, regions, ...). */
        TOO_LARGE,
        /** Parsing took longer than the deadline. */
        TIMEOUT,
        /** A pre-1.13 MCEdit/WorldEdit .schematic with numeric block ids. */
        LEGACY,
        /** Valid NBT, but not a format (or format version) this reader knows. */
        UNSUPPORTED
    }

    private final Code code;

    public SchematicException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    static SchematicException invalid(String message) {
        return new SchematicException(Code.INVALID, message);
    }

    static SchematicException tooLarge(String message) {
        return new SchematicException(Code.TOO_LARGE, message);
    }

    /** A tag name or other file-supplied text, cut so it can go into an error message. */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), 64));
        for (int i = 0; i < s.length() && b.length() < 64; i++) {
            char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        if (s.length() > 64) b.append("...");
        return b.toString();
    }
}
