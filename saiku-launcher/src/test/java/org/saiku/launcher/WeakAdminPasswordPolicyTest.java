/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.launcher.SaikuLauncher.ServeCommand;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * saiku#1915 — the default-credential gate keyed on the byte-identical shipped
 * hash, so {@code SAIKU_ADMIN_PASSWORD=admin} produced a NEW hash string, sailed
 * past the gate, and silenced the post-boot warning (CWE-1392 / CWE-521).
 *
 * <p>These tests pin the three legs of the fix: (1) a re-encoded "admin" is
 * recognised as the default, (2) a supplied password that is weak is refused
 * BEFORE it is written, and (3) a strong password still boots and is never
 * logged.
 */
public class WeakAdminPasswordPolicyTest {

    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "saiku.admin.password",
            "saiku.admin.passwordFile",
            "saiku.security.usersFile",
            "saiku.security.adminIsDefault",
            "saiku.allowDefaultAdmin",
            "saiku.allowWeakAdminPassword",
            "spring.profiles.active");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Properties saved = new Properties();

    private Path home;
    private Path war;

    @Before
    public void isolateTheJvmAndBuildAFixture() throws IOException {
        for (String key : TOUCHED_PROPERTIES) {
            String value = System.getProperty(key);
            if (value != null) {
                saved.setProperty(key, value);
            }
            System.clearProperty(key);
        }
        assumeFalse("SAIKU_ALLOW_DEFAULT_ADMIN is set in this environment", envFlag("SAIKU_ALLOW_DEFAULT_ADMIN"));
        assumeFalse(
                "SAIKU_ALLOW_WEAK_ADMIN_PASSWORD is set in this environment",
                envFlag("SAIKU_ALLOW_WEAK_ADMIN_PASSWORD"));
        assumeFalse("SAIKU_DEMO is set in this environment", envFlag("SAIKU_DEMO"));
        assumeTrue("SAIKU_ADMIN_PASSWORD is set in this environment", isBlank(System.getenv("SAIKU_ADMIN_PASSWORD")));

        home = tmp.newFolder("saiku-home").toPath();
        war = warContainingAdminRow(ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT + ",ROLE_USER,ROLE_ADMIN");
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

    /* ------------------- leg 1: the hash is not the password --------------- */

    /** The bypass itself: a freshly salted bcrypt of "admin" must read as default. */
    @Test
    public void reEncodedAdminHashIsStillTheDefaultAdminPassword() {
        String reEncoded = "{bcrypt}" + new BCryptPasswordEncoder().encode("admin");

        assertFalse(
                "a re-encode produces a different hash string (that was the bypass)",
                reEncoded.contains(ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT));
        assertTrue(
                "a re-encoded 'admin' is still admin/admin — the gate must compare the password, not the salt",
                ServeCommand.isDefaultAdminValue(reEncoded + ",ROLE_USER,ROLE_ADMIN"));
    }

    /** The legacy {noop} form and the shipped hash keep working. */
    @Test
    public void legacyAndShippedFormsAreUnchanged() {
        assertTrue(ServeCommand.isDefaultAdminValue("{noop}admin,ROLE_USER,ROLE_ADMIN"));
        assertTrue(ServeCommand.isDefaultAdminValue(ServeCommand.SHIPPED_BCRYPT_ADMIN_DEFAULT));
    }

    /** A strong password must not be mistaken for the default. */
    @Test
    public void aStrongRotatedHashIsNotTheDefault() throws IOException {
        Path external = home.resolve("users.properties");
        ServeCommand.writeAdminUsersFile(external, "a-strong-rotated-password");

        assertFalse(ServeCommand.isDefaultAdminValue(ServeCommand.readAdminValue(external)));
        assertFalse(ServeCommand.isWeakAdminValue(ServeCommand.readAdminValue(external)));
    }

    /** An external file carrying a re-encoded admin/admin is refused. */
    @Test
    public void externalUsersFileWithReEncodedAdminIsRefused() throws IOException {
        Path external = home.resolve("users.properties");
        Files.write(
                external,
                List.of("admin={bcrypt}" + new BCryptPasswordEncoder().encode("admin") + ",ROLE_USER,ROLE_ADMIN"));

        try {
            ServeCommand.enforceDefaultCredentialPolicy(external, war);
            fail("an external users.properties holding admin/admin must not boot");
        } catch (ServeCommand.DefaultCredentialsException expected) {
            // A re-encoded "admin" IS the shipped default password, so it takes
            // the default-credential refusal and its own escape hatch.
            assertTrue(
                    "the refusal must name the escape hatch",
                    expected.getMessage().contains("SAIKU_ALLOW_DEFAULT_ADMIN"));
        }
    }

    /**
     * saiku#1915: a hash-side denylist hit that is NOT the default password —
     * the shipped-hash comparison can never catch "changeme", only the
     * bcrypt-matched denylist can.
     */
    @Test
    public void externalUsersFileWithADenylistedPasswordIsRefused() throws IOException {
        Path external = home.resolve("users.properties");
        Files.write(
                external,
                List.of("admin={bcrypt}" + new BCryptPasswordEncoder().encode("changeme") + ",ROLE_USER,ROLE_ADMIN"));

        try {
            ServeCommand.enforceDefaultCredentialPolicy(external, war);
            fail("a users.properties holding admin/changeme must not boot");
        } catch (ServeCommand.DefaultCredentialsException expected) {
            assertTrue(
                    "the refusal must name the weak-password escape hatch",
                    expected.getMessage().contains("SAIKU_ALLOW_WEAK_ADMIN_PASSWORD"));
        }
    }

    /* ------------------- leg 2: the plaintext policy ----------------------- */

    @Test
    public void adminIsWeak() {
        assertEquals("the shipped default is refused", "it is a well-known weak password", reason("admin", "admin"));
        assertEquals(
                "a famous short word is named as such", "it is a well-known weak password", reason("admin", "password"));
        assertEquals(
                "the denylist is case-insensitive",
                "it is a well-known weak password",
                reason("admin", "PassWord123"));
        assertEquals("a 1-character password is refused", "it is shorter than 12 characters", reason("admin", "a"));
        assertEquals(
                "an 11-character password is refused on length",
                "it is shorter than 12 characters",
                reason("admin", "s3cr3t-pass"));
        assertEquals("a blank password is refused", "it is empty", reason("admin", "  "));
        assertEquals(
                "a password equal to the username is the same class of bypass",
                "it is identical to the username",
                reason("a-very-long-admin", "a-very-long-admin"));
        assertNull("a strong password clears the policy", reason("admin", "9Zt!kq2vLx#4Rb"));
    }

    private static String reason(String username, String password) {
        return ServeCommand.weakAdminPasswordReason(username, password);
    }

    /** saiku#1915 verbatim: -e SAIKU_ADMIN_PASSWORD=admin must not boot. */
    @Test
    public void suppliedAdminPasswordIsRefusedAndNothingIsWritten() {
        System.setProperty("saiku.admin.password", "admin");

        try {
            ServeCommand.resolveEffectiveUsersFile(home, war);
            fail("SAIKU_ADMIN_PASSWORD=admin must be refused before it can authenticate anyone");
        } catch (ServeCommand.DefaultCredentialsException expected) {
            assertTrue(
                    "the refusal must name the knob the operator used",
                    expected.getMessage().contains("-Dsaiku.admin.password"));
            assertTrue(
                    "the refusal must point at the secret-manager mount",
                    expected.getMessage().contains("SAIKU_ADMIN_PASSWORD_FILE"));
        }
        assertFalse("a refused password must never be written to disk", Files.exists(home.resolve("users.properties")));
        assertNull(System.getProperty("saiku.security.usersFile"));
    }

    /** A short-but-not-denylisted password is refused on length alone. */
    @Test
    public void shortPasswordIsRefused() {
        System.setProperty("saiku.admin.password", "s3cr3t");

        try {
            ServeCommand.resolveEffectiveUsersFile(home, war);
            fail("a 6-character password must be refused");
        } catch (ServeCommand.DefaultCredentialsException expected) {
            assertTrue(expected.getMessage().contains("12 characters"));
        }
    }

    /** The explicit opt-out is honoured — a lab may still take the risk. */
    @Test
    public void theOptOutLetsAWeakPasswordThrough() throws IOException {
        System.setProperty("saiku.allowWeakAdminPassword", "true");
        System.setProperty("saiku.admin.password", "admin");

        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);

        assertEquals(home.resolve("users.properties"), effective);
        assertTrue(bcryptMatches("admin", ServeCommand.readAdminValue(effective)));
    }

    /** Demo mode keeps working — the bundled demo is admin/admin by design. */
    @Test
    public void demoModeOptsOut() {
        System.setProperty("saiku.admin.password", "admin");
        System.setProperty("spring.profiles.active", "demo");

        // No exception: demo installs ship admin/admin deliberately (saiku#897).
        ServeCommand.resolveEffectiveUsersFile(home, war);
    }

    /** A strong password still boots, and the gate lets it through. */
    @Test
    public void aStrongPasswordStillBoots() throws IOException {
        System.setProperty("saiku.admin.password", "9Zt!kq2vLx#4Rb");

        Path effective = ServeCommand.resolveEffectiveUsersFile(home, war);
        ServeCommand.enforceDefaultCredentialPolicy(effective, war);

        assertTrue(bcryptMatches("9Zt!kq2vLx#4Rb", ServeCommand.readAdminValue(effective)));
        assertEquals("false", System.getProperty("saiku.security.adminIsDefault"));
    }

    /* ------------------- leg 3: hash-side denylist ------------------------- */

    @Test
    public void denylistedPasswordsAreDetectedThroughTheirHash() {
        for (String weak : List.of("password", "changeme", "12345678", "saiku")) {
            String value = "{bcrypt}" + new BCryptPasswordEncoder().encode(weak) + ",ROLE_USER,ROLE_ADMIN";
            assertTrue("hash of '" + weak + "' must read as weak", ServeCommand.isWeakAdminValue(value));
        }
        String strong = "{bcrypt}" + new BCryptPasswordEncoder().encode("9Zt!kq2vLx#4Rb");
        assertFalse(ServeCommand.isWeakAdminValue(strong));
    }

    /** A malformed / non-bcrypt encoded value must not crash the policy check. */
    @Test
    public void malformedEncodedValuesAreTreatedAsNotWeak() {
        assertFalse(ServeCommand.isWeakAdminValue("{noop}whatever,ROLE_USER"));
        assertFalse(ServeCommand.isWeakAdminValue("{bcrypt}$2a$not-a-real-hash"));
        assertFalse(ServeCommand.isWeakAdminValue(null));
    }

    /* ------------------------------ helpers -------------------------------- */

    private static boolean bcryptMatches(String password, String adminPropertyValue) {
        String enc = adminPropertyValue.split(",", 2)[0].trim();
        return new BCryptPasswordEncoder().matches(password, enc.substring("{bcrypt}".length()));
    }

    private static boolean envFlag(String name) {
        String v = System.getenv(name);
        return v != null && Boolean.parseBoolean(v.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Minimal WAR holding one users.properties row, so the zip-reading path runs for real. */
    private static Path warContainingAdminRow(String adminRow) throws IOException {
        Path path = Files.createTempFile("saiku-weak-policy-", ".war");
        path.toFile().deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("WEB-INF/users.properties"));
            out.write(("admin=" + adminRow + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path;
    }
}
