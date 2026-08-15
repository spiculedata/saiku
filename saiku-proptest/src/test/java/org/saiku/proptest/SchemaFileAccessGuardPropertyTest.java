/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.saiku.service.datasource.SchemaFileAccessGuard;

/**
 * Property-based tests for {@link SchemaFileAccessGuard}, the containment that stops a data source's
 * {@code Catalog=} reading arbitrary host files (saiku#1845).
 *
 * <p>The example-based tests cover the escapes their author thought of — one {@code ../}, one
 * symlink, one missing target. That is exactly the limitation this file removes: containment either
 * holds for EVERY path or it is not containment, so the invariant is asserted over a generated space
 * of adversarial paths rather than a hand-picked list.
 *
 * <p>The invariant, stated once:
 *
 * <blockquote>
 * {@code isAllowed(p)} is true only if {@code p}'s real location lies inside a permitted root.
 * </blockquote>
 *
 * <p>Note the direction of risk. A false negative merely refuses a legal schema; a false POSITIVE
 * discloses a file. Every property below is written to hunt the latter.
 */
class SchemaFileAccessGuardPropertyTest {

    /** Traversal spellings an attacker actually tries, including the ones people forget. */
    private static final List<String> TRAVERSALS =
            List.of("..", "../..", "../../..", "./..", ".//../", "a/../..", "a/b/../../..", "./././..", "a/./../..");

    /** Absolute paths that must never be readable, whatever the roots are. */
    private static final List<String> SENSITIVE =
            List.of("/etc/passwd", "/etc/shadow", "/root/.ssh/id_rsa", "/proc/self/environ", "/var/log/auth.log");

    /**
     * Build a guard over {@code root} through the public configuration path. The constructor is
     * package-private, and going via {@code fromEnvironment} additionally exercises the property an
     * operator actually sets.
     */
    private static SchemaFileAccessGuard guardOver(Path... roots) {
        String previous = System.getProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY);
        StringBuilder joined = new StringBuilder();
        for (Path r : roots) {
            if (joined.length() > 0) {
                joined.append(File.pathSeparator);
            }
            joined.append(r);
        }
        try {
            System.setProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY, joined.toString());
            return SchemaFileAccessGuard.fromEnvironment(roots.length > 0 ? roots[0].toString() : null);
        } finally {
            if (previous == null) {
                System.clearProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY);
            } else {
                System.setProperty(SchemaFileAccessGuard.EXTRA_ROOTS_PROPERTY, previous);
            }
        }
    }

    private static Path newRoot() throws IOException {
        Path p = Files.createTempDirectory("saiku-guard-root");
        p.toFile().deleteOnExit();
        return p.toRealPath();
    }

    /** The real (symlink-followed where possible) location a path denotes. */
    private static Path realLocationOf(Path p) throws IOException {
        return Files.exists(p) ? p.toRealPath() : p.toAbsolutePath().normalize();
    }

    /**
     * THE invariant. For any generated path — traversal, nested, junk — a verdict of "allowed" must
     * be corroborated by the path's real location sitting under a permitted root.
     */
    @HegelTest
    void allowedImpliesTheRealPathIsInsideARoot(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        List<String> segments =
                tc.draw(lists(fromRegex("\\.\\.|[a-z]{1,6}|\\.")).minSize(0).maxSize(6), "segments");

        Path candidate = root;
        for (String s : segments) {
            candidate = candidate.resolve(s);
        }
        tc.note("candidate=" + candidate);

        if (guard.isAllowed(candidate)) {
            Path resolved = realLocationOf(candidate);
            assertTrue(resolved.startsWith(root), "allowed a path outside the root: " + candidate + " -> " + resolved);
        }
    }

    /** No amount of traversal from inside a root may reach a sensitive absolute path. */
    @HegelTest
    void traversalNeverEscapesToASensitivePath(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        String traversal = tc.draw(sampledFrom(TRAVERSALS), "traversal");
        String target = tc.draw(sampledFrom(SENSITIVE), "target");

        Path escaped = root.resolve(traversal + target);

        assertFalse(guard.isAllowed(escaped), "traversal escaped containment: " + escaped);
    }

    /** Absolute sensitive paths are refused outright, no traversal needed. */
    @HegelTest
    void absoluteSensitivePathsAreNeverAllowed(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        Path target = Path.of(tc.draw(sampledFrom(SENSITIVE), "target"));

        assertFalse(guard.isAllowed(target), "allowed a sensitive absolute path: " + target);
        assertThrows(IOException.class, () -> guard.assertReadable(target));
    }

    /**
     * No false negatives: any path genuinely nested under a root is readable, however deep, and
     * whether or not it exists. A guard that refused these would break legitimate schemas.
     */
    @HegelTest
    void anyPathNestedUnderARootIsAllowed(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        List<String> segments =
                tc.draw(lists(fromRegex("[a-zA-Z0-9_-]{1,10}")).minSize(1).maxSize(5), "segments");

        Path nested = root;
        for (String s : segments) {
            nested = nested.resolve(s);
        }

        assertTrue(guard.isAllowed(nested), "refused a legitimate nested schema: " + nested);
    }

    /**
     * Containment follows the symlink's TARGET, not its name. A link inside a root whose target is
     * outside must be refused — otherwise the check is bypassed by anyone who can create a link in
     * the data directory.
     */
    @HegelTest
    void aSymlinkIsJudgedOnItsTargetNotItsName(TestCase tc) throws IOException {
        Path root = newRoot();
        Path outside = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        String linkName = tc.draw(fromRegex("[a-z]{1,10}\\.xml"), "linkName");
        String targetName = tc.draw(fromRegex("[a-z]{1,10}\\.txt"), "targetName");

        Path target = outside.resolve(targetName);
        Files.writeString(target, "SECRET");
        Path link = root.resolve(linkName);
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException noSymlinkSupport) {
            return; // platform without symlink privilege — nothing to assert
        }

        assertFalse(guard.isAllowed(link), "symlink escaped containment: " + link + " -> " + target);
    }

    /**
     * The refusal message must not leak the server's directory layout. It frequently ends up in an
     * HTTP response, so echoing the allowed roots back to whoever just probed is the same class of
     * disclosure the guard exists to prevent.
     */
    @HegelTest
    void refusalMessagesNeverDiscloseTheAllowedRoots(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        Path target = Path.of(tc.draw(sampledFrom(SENSITIVE), "target"));

        IOException e = assertThrows(IOException.class, () -> guard.assertReadable(target));
        for (Path allowed : guard.allowedRoots()) {
            assertFalse(
                    e.getMessage().contains(allowed.toString()),
                    "refusal message disclosed an allowed root: " + e.getMessage());
        }
    }

    /** Adding a root only ever widens access — it can never revoke a path that was already allowed. */
    @HegelTest
    void addingARootIsMonotone(TestCase tc) throws IOException {
        Path rootA = newRoot();
        Path rootB = newRoot();

        SchemaFileAccessGuard narrow = guardOver(rootA);
        SchemaFileAccessGuard wide = guardOver(rootA, rootB);

        String name = tc.draw(fromRegex("[a-z]{1,8}"), "name");
        for (Path p : List.of(rootA.resolve(name), rootB.resolve(name), Path.of("/etc", name))) {
            if (narrow.isAllowed(p)) {
                assertTrue(wide.isAllowed(p), "widening the roots revoked access to " + p);
            }
        }
    }

    /** A verdict is always produced — never an exception — for any path shape the platform accepts. */
    @HegelTest
    void isAllowedIsTotal(TestCase tc) throws IOException {
        Path root = newRoot();
        SchemaFileAccessGuard guard = guardOver(root);

        String raw = tc.draw(fromRegex("[a-zA-Z0-9_./\\\\ :~$%-]{0,40}"), "raw");
        Path candidate;
        try {
            candidate = Path.of(raw);
        } catch (RuntimeException invalidOnThisPlatform) {
            return;
        }
        guard.isAllowed(candidate); // must not throw
    }
}
