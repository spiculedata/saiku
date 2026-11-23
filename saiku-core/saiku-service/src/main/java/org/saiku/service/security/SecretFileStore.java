/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.security;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes secret-bearing files (AES install key, SMTP settings, consent store, session store) with
 * owner-only permissions, atomically.
 *
 * <p>Why this exists — saiku#1919 item 18c (CWE-732 / CWE-377). The stores that hold secrets under
 * {@code ${saiku.home}} were written with {@code Files.write} / {@code ObjectMapper.writeValue},
 * which creates the file with {@code 0666 & ~umask} (typically 0644) and tightens permissions
 * <em>afterwards</em> where it bothers at all. Two problems follow:
 *
 * <ol>
 *   <li><b>Umask window (CWE-377).</b> Between creation and the chmod, the file is world-readable
 *       on a default-umask host, and on shared hosts the content is exposed in a race a local
 *       account can win. The fix is to pass the permissions as <em>creation attributes</em> so the
 *       file is never on disk with the wrong mode.
 *   <li><b>Windows.</b> {@code chmod}-equivalents are ACL-based; without an explicit ACL a file
 *       under a shared directory inherits {@code Users}-read. This class applies an owner-only ACL
 *       when the filesystem exposes one.
 * </ol>
 *
 * <p>Writes go to a temp sibling and are moved into place, so a reader never observes a
 * half-serialised file and a crash mid-write cannot truncate the previous value. This is the same
 * atomic pattern {@code ShareTokenStore} already uses, generalised to every secret file.
 *
 * <p>All permission tightening is best-effort: on a filesystem that supports neither POSIX modes nor
 * ACLs the write still succeeds (and the file stays under {@code saiku-home}), but a failure to
 * tighten is logged rather than swallowed silently.
 */
public final class SecretFileStore {

    private static final Logger log = LoggerFactory.getLogger(SecretFileStore.class);

    /** 0600 — the file is created already restricted, so there is no wider window to race. */
    private static final Set<PosixFilePermission> FILE_PERMS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /** 0700 — same for a directory that will hold secrets (e.g. the Jetty session store). */
    private static final Set<PosixFilePermission> DIR_PERMS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private SecretFileStore() {
        // static utility
    }

    /** Writes {@code content} to {@code target} with owner-only perms, atomically. */
    public static void writeOwnerOnly(Path target, byte[] content) throws IOException {
        writeOwnerOnly(target, tmp -> Files.write(tmp, content));
    }

    /**
     * Writes to {@code target} with owner-only perms, atomically. The caller writes through the
     * supplied {@code writer}, which receives the already-restricted temp path — it must not create
     * the file itself (that would lose the 0600 creation attributes).
     */
    public static void writeOwnerOnly(Path target, PathWriter writer) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.deleteIfExists(tmp);
        createRestricted(tmp);
        try {
            writer.write(tmp);
            // Re-assert: a writer that replaced the file (or a non-POSIX FS that ignored the
            // creation attributes) must not widen it.
            restrict(tmp, false);
            moveInto(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        restrict(target, false);
    }

    /**
     * Creates {@code dir} (and parents) and restricts it to the owner (0700 / owner-only ACL). Use for
     * directories that hold secret material, such as {@code ${saiku.home}/sessions}.
     */
    public static void restrictDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        restrict(dir, true);
    }

    /**
     * Restricts an existing file or directory to its owner. Best effort: unsupported filesystems log
     * at debug, an outright denial logs at warn and is not propagated — refusing to run because a
     * filesystem has no ACL support would be worse than the finding.
     */
    public static void restrict(Path path, boolean directory) {
        boolean posix = restrictPosix(path, directory);
        if (!posix) {
            restrictAcl(path);
        }
    }

    private static void moveInto(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // Some filesystems (and cross-device moves) don't support ATOMIC_MOVE; fall back to a
            // plain replace. The temp file is already fully written and owner-only.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Creates the file ALREADY restricted — the creation attributes are the whole point (#1919 18c). */
    private static void createRestricted(Path path) throws IOException {
        FileAttribute<?>[] attrs;
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            attrs = new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_PERMS)};
        } else {
            attrs = new FileAttribute<?>[0];
        }
        try {
            Files.createFile(path, attrs);
        } catch (FileAlreadyExistsException alreadyThere) {
            // Left over from a crashed run: re-truncate by re-applying the mode below.
            if (attrs.length > 0) {
                Files.setPosixFilePermissions(path, FILE_PERMS);
            }
        }
    }

    private static boolean restrictPosix(Path path, boolean directory) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            return false;
        }
        try {
            Files.setPosixFilePermissions(path, directory ? DIR_PERMS : FILE_PERMS);
            return true;
        } catch (IOException | UnsupportedOperationException denied) {
            // Best effort — a read-only or ACL-bound mount still gets a working file.
            log.warn("Could not set owner-only permissions on {}: {}", path, denied.getMessage());
            return true;
        }
    }

    /** Owner-only Windows ACL, applied only when the filesystem exposes one (Windows, some NFS). */
    private static void restrictAcl(Path path) {
        AclFileAttributeView view;
        try {
            view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return; // Not a POSIX or ACL filesystem (e.g. an in-memory FS in tests).
            }
        } catch (UnsupportedOperationException unsupported) {
            return;
        }
        try {
            if (view.getOwner() == null) {
                return;
            }
            AclEntry ownerEntry = ownerEntry(view);
            // Replace, do not append: inheritable ACEs from a permissive parent directory are
            // exactly what must not apply to a secret file.
            view.setAcl(Collections.singletonList(ownerEntry));
        } catch (IOException | RuntimeException denied) {
            log.warn("Could not apply an owner-only ACL to {}: {}", path, denied.getMessage());
        }
    }

    private static AclEntry ownerEntry(AclFileAttributeView view) throws IOException {
        UserPrincipal owner = view.getOwner();
        Set<AclEntryPermission> perms = EnumSet.of(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.DELETE,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.SYNCHRONIZE);
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(perms)
                .build();
    }

    /** Serialises to a caller-supplied path. Implementations may truncate; they must not create. */
    @FunctionalInterface
    public interface PathWriter {
        void write(Path path) throws IOException;
    }
}
