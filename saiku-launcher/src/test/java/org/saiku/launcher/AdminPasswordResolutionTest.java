/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.launcher.SaikuLauncher.ServeCommand;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * #1503 — the {@code SAIKU_ADMIN_PASSWORD} credential path added in 4.6.2 by
 * {@code cd4aed06}.
 *
 * <p>{@link CredentialPolicyTest} covers the pure boot decision ({@code
 * isDefaultAdminValue} + {@code shouldRefuse}) and explicitly leaves the
 * WAR-reading and file-resolution paths to the ITs. This class fills that gap
 * at unit level: {@code resolveEffectiveUsersFile}'s three-tier precedence, the
 * bcrypt row it generates, its persistence across a restart, and the two
 * properties of the file that are security-relevant rather than functional —
 * that the password is never logged, and that the file stays 0600.
 *
 * <p>The 4.6.1...4.6.2 diff shipped this feature with no test of any kind, so
 * the regression mode for the first test below is <em>fail-open</em>: an
 * instance quietly serving admin/admin on a network-reachable box.
 *
 * <p>Note on coverage limits: {@code resolveEffectiveUsersFile} reads the
 * password from {@code SAIKU_ADMIN_PASSWORD} (env) <em>or</em>
 * {@code -Dsaiku.admin.password} (system property) into one local before doing
 * anything with it, so both tiers share every line that follows. The JVM cannot
 * set its own environment, so these tests drive the system-property half; the
 * env half of that single branch is what the container run exercises.
 */
public class AdminPasswordResolutionTest {

    private static final String BCRYPT_ID = "{bcrypt}";
    private static final String DEFAULT_ADMIN_ROW = ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT + ",ROLE_USER,ROLE_ADMIN";

    /** Every system property these paths read or write. Saved, cleared, restored. */
    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "saiku.admin.password",
            "saiku.security.usersFile",
            "saiku.security.adminIsDefault",
            "saiku.allowDefaultAdmin",
            "spring.profiles.active");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Properties saved = new Properties();

    private Path home;
    private Path war;

    @Before
    public void isolateTheJvmAndBuildAFixture() throws IOException {
        // Surefire reuses a fork across classes and CredentialPolicyTest writes
        // saiku.allowDefaultAdmin — clear rather than trust the JVM we inherit.
        for (String key : TOUCHED_PROPERTIES) {
            String value = System.getProperty(key);
            if (value != null) {
                saved.setProperty(key, value);
            }
            System.clearProperty(key);
        }
        // These env vars legitimately disable the guard. A developer machine
        // exporting one would turn the fail-open test below green for the wrong
        // reason, so skip rather than assert something untrue.
        Assume.assumeFalse(
                "SAIKU_ALLOW_DEFAULT_ADMIN is set in this environment", envFlag("SAIKU_ALLOW_DEFAULT_ADMIN"));
        Assume.assumeFalse("SAIKU_DEMO is set in this environment", envFlag("SAIKU_DEMO"));
        Assume.assumeTrue(
                "SAIKU_ADMIN_PASSWORD is set in this environment", isBlank(System.getenv("SAIKU_ADMIN_PASSWORD")));

        home = tmp.newFolder("saiku-home").toPath();
        war = warContainingAdminRow(DEFAULT_ADMIN_ROW);
    }

    @After
    public void restoreTheJvm() {
        for (String key : TOUCHED_PROPERTIES) {
            String value = saved.getProperty(key);
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    /* ------------------------- the fail-open guard ------------------------- */

    /**
     * saiku#1153: with no password and no external file the launcher must fall
     * through to the WAR's baked default AND refuse to boot on it. If this ever
     * goes green-by-not-throwing, Saiku serves admin/admin.
     */
    @Test
    public void refusesToBootWithNeitherAdminPasswordNorExternalUsersFile() {
        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);

        assertEquals("with nothing to override it, the WAR's baked default is the effective file", war, effective);
        assertFalse("no external file must be conjured up", Files.exists(home.resolve("users.properties")));
        assertNull(
                "Spring must not be pointed at a file that was never written",
                System.getProperty("saiku.security.usersFile"));

        try {
            ServeCommand.enforceDefaultCredentialPolicy(effective, war);
            fail("the saiku#1153 guard must refuse to boot while the shipped default admin password is active");
        } catch (ServeCommand.DefaultCredentialsException expected) {
            assertTrue(
                    "the refusal must tell the operator how to fix it",
                    expected.getMessage().contains("SAIKU_ADMIN_PASSWORD"));
        }
        assertEquals("true", System.getProperty("saiku.security.adminIsDefault"));
    }

    /** The mirror image: once a password is set, the guard must let the boot through. */
    @Test
    public void bootProceedsOnceAnAdminPasswordIsSet() {
        System.setProperty("saiku.admin.password", "a-strong-password");

        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);
        ServeCommand.enforceDefaultCredentialPolicy(effective, war);

        assertEquals("false", System.getProperty("saiku.security.adminIsDefault"));
    }

    /* --------------------------- precedence order -------------------------- */

    /** Tier 1 beats tier 2: the property rewrites an external file that already exists. */
    @Test
    public void adminPasswordOverridesAnExistingExternalFile() throws IOException {
        Path external = home.resolve("users.properties");
        ServeCommand.writeAdminUsersFile(external, "the-old-password");
        String before = ServeCommand.readAdminValue(external);

        System.setProperty("saiku.admin.password", "the-new-password");
        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);

        assertEquals(external, effective);
        String after = ServeCommand.readAdminValue(external);
        assertNotEquals("the external file must be rewritten, not reused", before, after);
        assertTrue("the new password must be the one that authenticates", bcryptMatches("the-new-password", after));
        assertFalse("the superseded password must stop working", bcryptMatches("the-old-password", after));
    }

    /** Tier 2 beats tier 3: an external file wins over the WAR's baked default. */
    @Test
    public void externalUsersFileOverridesTheWarBakedDefault() throws IOException {
        Path external = home.resolve("users.properties");
        ServeCommand.writeAdminUsersFile(external, "a-rotated-password");

        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);

        assertEquals(external, effective);
        assertEquals(
                "Spring Security must be pointed at the external file",
                external.toUri().toString(),
                System.getProperty("saiku.security.usersFile"));
    }

    /* ------------------------ the generated bcrypt row ---------------------- */

    @Test
    public void generatedAdminRowIsWellFormedBcryptWithBothRoles() throws IOException {
        Path external = home.resolve("users.properties");

        ServeCommand.writeAdminUsersFile(external, "s3cr3t-password");

        String value = ServeCommand.readAdminValue(external);
        assertTrue(
                "Spring Security resolves the encoder from the {bcrypt} id — 4.6.2 observed {bcrypt}$2a$",
                value.startsWith(BCRYPT_ID + "$2a$"));
        assertEquals("the admin must keep both roles", "ROLE_USER,ROLE_ADMIN", value.split(",", 2)[1]);
        assertTrue(
                "the hash must verify against the password it was made from", bcryptMatches("s3cr3t-password", value));
        assertFalse(
                "a generated hash must never collide with the shipped default",
                ServeCommand.isDefaultAdminValue(value));
    }

    /** The launcher rewrites only the admin row — operator-added users survive. */
    @Test
    public void rewritingTheAdminRowPreservesOtherUsers() throws IOException {
        Path external = home.resolve("users.properties");
        Files.write(external, List.of("admin=" + DEFAULT_ADMIN_ROW, "alice={noop}wonderland,ROLE_USER"));

        ServeCommand.writeAdminUsersFile(external, "a-new-password");

        List<String> lines = Files.readAllLines(external);
        assertTrue(
                "an operator-added user must survive a password change",
                lines.contains("alice={noop}wonderland,ROLE_USER"));
        assertEquals(
                "exactly one admin row must remain",
                1,
                lines.stream().filter(l -> l.startsWith("admin=")).count());
        assertTrue(bcryptMatches("a-new-password", ServeCommand.readAdminValue(external)));
    }

    /* ------------------------------ persistence ---------------------------- */

    /**
     * dist/README's claim — "the env var is only needed the first time". Second
     * start with nothing set must reuse the persisted hash verbatim and boot.
     */
    @Test
    public void persistedPasswordIsHonouredOnASubsequentStartWithNothingSet() {
        System.setProperty("saiku.admin.password", "a-persisted-password");
        Path firstStart = ServeCommand.resolveEffectiveUsersFile(home, war);
        String hashAfterFirstStart = ServeCommand.readAdminValue(firstStart);

        // Restart: no password anywhere, exactly as a `docker run` without -e.
        System.clearProperty("saiku.admin.password");
        System.clearProperty("saiku.security.usersFile");
        Path secondStart = ServeCommand.resolveEffectiveUsersFile(home, war);

        assertEquals(firstStart, secondStart);
        assertEquals(
                "the persisted hash must survive verbatim — re-encoding would invalidate the password",
                hashAfterFirstStart,
                ServeCommand.readAdminValue(secondStart));
        assertTrue(bcryptMatches("a-persisted-password", ServeCommand.readAdminValue(secondStart)));
        ServeCommand.enforceDefaultCredentialPolicy(secondStart, war);
    }

    /* -------------------- secret handling around the file ------------------- */

    @Test
    public void theAdminPasswordNeverReachesTheLogs() {
        String password = "uniq-pw-never-logged-9f3a2b";
        System.setProperty("saiku.admin.password", password);

        PrintStream realOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Path effective;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            effective = ServeCommand.resolveEffectiveUsersFile(home, war);
        } finally {
            System.setOut(realOut);
        }

        String logged = captured.toString(StandardCharsets.UTF_8);
        assertFalse("the admin password must never be echoed to stdout", logged.contains(password));
        assertTrue("the file path is the safe, useful thing to log", logged.contains(effective.toString()));
    }

    /**
     * 4.6.2 moved the credential file out of the jar and onto a mounted volume —
     * a new exposure surface. Nothing else asserts it stays narrow.
     */
    @Test
    public void credentialFileIsWrittenOwnerReadWriteOnly() throws IOException {
        Path external = home.resolve("users.properties");
        Assume.assumeTrue(
                "POSIX file permissions are unsupported on this filesystem",
                external.getFileSystem().supportedFileAttributeViews().contains("posix"));

        ServeCommand.writeAdminUsersFile(external, "a-strong-password");

        assertEquals(
                "the credential file must be 0600 — it holds the admin hash on a mounted volume",
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(external));
    }

    /* -------------------------------- helpers ------------------------------ */

    /** A minimal stand-in for the shaded saiku.war — only the entry the policy reads. */
    private Path warContainingAdminRow(String adminRow) throws IOException {
        Path zip = tmp.newFile("saiku.war").toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("WEB-INF/users.properties"));
            out.write(("admin=" + adminRow + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    /** True when {@code rawPassword} authenticates against a {@code <encoded>,ROLE_...} row. */
    private static boolean bcryptMatches(String rawPassword, String adminPropertyValue) {
        String encoded = adminPropertyValue.split(",", 2)[0].trim();
        if (!encoded.startsWith(BCRYPT_ID)) {
            return false;
        }
        return new BCryptPasswordEncoder().matches(rawPassword, encoded.substring(BCRYPT_ID.length()));
    }

    /** Mirrors the launcher's own env-flag parsing. */
    private static boolean envFlag(String name) {
        String value = System.getenv(name);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
