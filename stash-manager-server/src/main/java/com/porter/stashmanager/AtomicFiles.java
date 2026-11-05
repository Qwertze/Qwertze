package com.porter.stashmanager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

final class AtomicFiles {
    private AtomicFiles() {
    }

    /** Writes bytes to a temp file next to the target, then moves it into place. */
    static void write(Path target, byte[] data) throws IOException {
        write(target, data, false);
    }

    /** ownerOnly makes the file rw------- where the file system has POSIX permissions (ignored on Windows). */
    static void write(Path target, byte[] data, boolean ownerOnly) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            if (ownerOnly) ownerOnly(tmp);
            Files.write(tmp, data);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Makes a file readable by its owner only: rw------- on POSIX file systems; on Windows (NTFS ACLs) an ACL with just
     * the current user. Failures are only warned about.
     */
    static void ownerOnly(Path file) {
        restrict(file, false);
    }

    /** The same for a directory (rwx------; on Windows the entry is inherited by new files in it). */
    static void ownerOnlyDir(Path dir) {
        restrict(dir, true);
    }

    private static final java.util.Set<Path> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void restrict(Path path, boolean dir) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(dir ? "rwx------" : "rw-------"));
            return;
        } catch (UnsupportedOperationException ignored) {
            // Not POSIX: try ACLs below.
        } catch (IOException e) {
            warn(path, e);
            return;
        }
        java.nio.file.attribute.AclFileAttributeView acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null) return;
        try {
            // The user this server runs as (not the file's owner, which may be a group such as Administrators).
            java.nio.file.attribute.UserPrincipal owner = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
            java.nio.file.attribute.AclEntry.Builder entry = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class));
            if (dir) {
                entry.setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(entry.build()));
        } catch (IOException | RuntimeException e) {
            warn(path, e);
        }
    }

    private static void warn(Path path, Exception e) {
        if (WARNED.add(path)) System.err.println("Could not restrict " + path.getFileName() + " to this user: " + e.getMessage());
    }

    /** Atomic move, falling back to a plain replacing move where the file system can't do it. */
    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes *.tmp files left by a crash in the middle of a write. Returns how many were removed. */
    static int sweepTemp(Path dataDir) {
        if (!Files.isDirectory(dataDir)) return 0;
        List<Path> tmps;
        try (Stream<Path> walk = Files.walk(dataDir, 3)) {
            tmps = walk.filter(p -> p.getFileName().toString().endsWith(".tmp") && Files.isRegularFile(p)).toList();
        } catch (IOException e) {
            return 0;
        }
        int n = 0;
        for (Path p : tmps) {
            try {
                if (Files.deleteIfExists(p)) n++;
            } catch (IOException ignored) {
            }
        }
        return n;
    }
}
