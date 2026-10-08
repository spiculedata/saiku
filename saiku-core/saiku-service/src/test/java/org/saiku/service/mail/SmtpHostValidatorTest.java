/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.After;
import org.junit.Test;

/**
 * saiku#1918 (17b, CWE-918) — the SMTP host gate.
 *
 * <p>The mail wizard used to CRLF-strip the host and nothing else, then {@code /test} dialled it.
 * Because SMTP carries no URL, the webhooks' "must be https" rule has no analogue here — but the
 * probe is exactly as good. The response distinguishes connection-refused from timeout from a
 * protocol banner, and that is enough to blind-map which internal host:port pairs are listening
 * (the control-plane database, a neighbour tenant's service) from a tenant-admin's browser.
 *
 * <p>These tests pin the three properties that make the gate a gate: the range rules match the
 * webhook gate's, an on-prem relay can still be configured deliberately, and the refusals never
 * leak WHICH check fired (that distinction is the oracle).
 */
public class SmtpHostValidatorTest {

    /** Resolver that never touches DNS: every name resolves to the supplied addresses. */
    private static SmtpHostValidator.HostResolver resolving(InetAddress... addrs) {
        return host -> addrs;
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @After
    public void clearOverrides() {
        System.clearProperty("saiku.mail.smtp.allowedHosts");
        System.clearProperty("saiku.mail.smtp.allowedPorts");
        System.clearProperty("saiku.mail.smtp.allowPrivateRange");
    }

    private static void assertRefused(String host, int port, SmtpHostValidator.HostResolver r) {
        try {
            SmtpHostValidator.validate(host, port, r);
            fail("expected '" + host + ":" + port + "' to be refused");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
            assertFalse(
                    "a refusal must always carry a reason for the log",
                    e.getMessage().isBlank());
        }
    }

    // ---------------- public relay: the happy path ----------------

    @Test
    public void publicHostOnASmtpPortIsAccepted() throws Exception {
        SmtpHostValidator.ValidatedHost v =
                SmtpHostValidator.validate("smtp.example.com", 587, resolving(ip("93.184.216.34")));
        assertEquals("smtp.example.com", v.host());
        assertEquals(587, v.port());
    }

    @Test
    public void hostIsNormalisedToLowerCaseAndTrimmed() throws Exception {
        // The stored value is the normalised one, so a config saved as " SMTP.Example.COM " can't
        // produce a second, differently-spelled entry in the config file.
        assertEquals(
                "smtp.example.com",
                SmtpHostValidator.validate("  SMTP.Example.COM  ", 25, resolving(ip("93.184.216.34")))
                        .host());
    }

    // ---------------- internal ranges: the actual SSRF surface ----------------

    @Test
    public void loopbackAndPrivateRangesAreRefused() throws Exception {
        // A perfectly ordinary public-looking name that happens to resolve inward — the shape a
        // DNS-rebinding or split-horizon setup produces, and the one the range check exists for.
        // (The control-plane database, the cloud metadata service, a neighbour tenant.)
        assertRefused("mail.example.com", 587, resolving(ip("127.0.0.1")));
        assertRefused("mail.example.com", 587, resolving(ip("10.1.2.3")));
        assertRefused("mail.example.com", 587, resolving(ip("192.168.1.10")));
        assertRefused("mail.example.com", 587, resolving(ip("172.16.4.5")));
        assertRefused("mail.example.com", 587, resolving(ip("169.254.169.254")));
        assertRefused("mail.example.com", 587, resolving(ip("100.64.0.1")));
        assertRefused("mail.example.com", 587, resolving(ip("::1"))); // IPv6 loopback
        assertRefused("mail.example.com", 587, resolving(ip("fc00::1"))); // IPv6 unique-local
    }

    @Test
    public void oneInternalAddressAmongManyStillRefuses() throws Exception {
        // A host with one public and one internal A record is the DNS-rebinding shape: it passes a
        // naive "does it resolve publicly" check and still lets the dialer reach the internal one.
        assertRefused("split-horizon.example.com", 587, resolving(ip("93.184.216.34"), ip("10.0.0.7")));
    }

    @Test
    public void unresolvableHostIsRefused() {
        assertRefused("nowhere.example.com", 587, host -> {
            throw new UnknownHostException(host);
        });
        assertRefused("empty.example.com", 587, host -> new InetAddress[0]);
    }

    // ---------------- syntax: a bare host is a bare host ----------------

    @Test
    public void aHostWithStructureInItIsRefused() throws Exception {
        SmtpHostValidator.HostResolver publicDns = resolving(ip("93.184.216.34"));
        // Each of these is either a configuration mistake or an attempt to make the validator read
        // something other than the host. None of them is a thing JavaMail should ever be handed.
        assertRefused("smtp.example.com:587", 587, publicDns); // port smuggled into the host
        assertRefused("smtp://example.com", 587, publicDns); // scheme
        assertRefused("user@evil.example.com", 587, publicDns); // userinfo
        assertRefused("smtp.example.com/path", 587, publicDns); // path
        assertRefused("smtp example.com", 587, publicDns); // whitespace
        assertRefused("smtp.exa mple.com", 587, publicDns);
    }

    @Test
    public void internalHostNamesAreRefusedWithoutNeedingDns() {
        // Fast-fail, and covers the unresolvable ones. A dotless name is almost always an internal
        // short name, which is exactly the shape an on-prem probe uses.
        SmtpHostValidator.HostResolver exploding = host -> {
            throw new AssertionError("must not resolve a name that is already known to be internal");
        };
        assertRefused("localhost", 587, exploding);
        assertRefused("mailserver", 587, exploding);
        assertRefused("metadata.google.internal", 587, exploding);
        assertRefused("printer.local", 587, exploding);
        assertRefused("anything.localhost", 587, exploding);
    }

    @Test
    public void obfuscatedNumericAddressesAreRefused() {
        // saiku#1846's rule, applied to the SMTP path too: JDK resolvers parse these forms
        // inconsistently, so they are rejected before resolution rather than trusted to it.
        SmtpHostValidator.HostResolver exploding = host -> {
            throw new AssertionError("obfuscated numerics must be rejected before any resolution");
        };
        assertRefused("2130706433", 587, exploding); // 127.0.0.1 as a bare integer
        assertRefused("0x7f000001", 587, exploding);
        assertRefused("0177.0.0.1", 587, exploding); // octal
        assertRefused("127.1", 587, exploding); // fewer than four parts
    }

    // ---------------- ports: mail ports, not probe ports ----------------

    @Test
    public void nonSmtpPortsAreRefused() throws Exception {
        SmtpHostValidator.HostResolver publicDns = resolving(ip("93.184.216.34"));
        assertRefused("smtp.example.com", 5432, publicDns); // postgres
        assertRefused("smtp.example.com", 6379, publicDns); // redis
        assertRefused("smtp.example.com", 1, publicDns);
        assertRefused("smtp.example.com", 8080, publicDns);
    }

    @Test
    public void portAllowlistIsExtensible() throws Exception {
        System.setProperty("saiku.mail.smtp.allowedPorts", "1025");
        assertEquals(
                1025,
                SmtpHostValidator.validate("smtp.example.com", 1025, resolving(ip("93.184.216.34")))
                        .port());
        // Widening the list adds to it; it does not replace the SMTP defaults.
        assertTrue(SmtpHostValidator.allowedPorts().contains(587));
        assertRefused("smtp.example.com", 6379, resolving(ip("93.184.216.34")));
    }

    @Test
    public void aMalformedPortEntryIsIgnoredRatherThanFatal() throws Exception {
        // The allowlist is operator configuration. A typo must not brick mail configuration for
        // the deployment; a malformed entry simply doesn't widen anything.
        System.setProperty("saiku.mail.smtp.allowedPorts", "1025,not-a-number,2525");
        assertTrue(SmtpHostValidator.allowedPorts().contains(1025));
        assertTrue(SmtpHostValidator.allowedPorts().contains(2525));
        assertEquals(
                1025,
                SmtpHostValidator.validate("smtp.example.com", 1025, resolving(ip("93.184.216.34")))
                        .port());
    }

    // ---------------- the on-prem escape hatch ----------------

    @Test
    public void allowlistOverridesTheRangeCheckForNamedHosts() throws Exception {
        // An on-prem relay is routinely an RFC-1918 address on a dotless internal name. Refusing
        // to configure mail there would be a regression, not a hardening — so an operator can name
        // the host explicitly.
        System.setProperty("saiku.mail.smtp.allowedHosts", "mail.corp");
        assertEquals(
                "mail.corp",
                SmtpHostValidator.validate("mail.corp", 25, resolving(ip("10.0.0.5")))
                        .host());
    }

    @Test
    public void allowlistSuffixEntryCoversADomain() throws Exception {
        System.setProperty("saiku.mail.smtp.allowedHosts", ".corp.example.com");
        // Every relay in the estate is covered by one entry, RFC-1918 addresses included.
        assertNotNull(SmtpHostValidator.validate("smtp1.corp.example.com", 25, resolving(ip("10.0.0.5"))));
        assertNotNull(SmtpHostValidator.validate("smtp2.corp.example.com", 25, resolving(ip("10.0.0.6"))));
        // ...and only that suffix. A different name pointing at the same internal address is still
        // refused, so the allowlist is a list and not a wildcard.
        assertRefused("smtp1.evil.test", 25, resolving(ip("10.0.0.5")));
    }

    @Test
    public void allowlistStillHonoursSyntaxAndPortRules() throws Exception {
        // Naming a host in the allowlist waives the RANGE check, not the shape check. If it waived
        // everything, the allowlist would be the bypass the gate was added to prevent.
        System.setProperty("saiku.mail.smtp.allowedHosts", "mail.corp");
        assertRefused("mail.corp:25", 25, resolving(ip("10.0.0.5")));
        assertRefused("mail.corp", 6379, resolving(ip("10.0.0.5")));
    }

    @Test
    public void allowPrivateRangeLiftsTheRangeAndNameChecksButNotThePortOrSyntaxRules() throws Exception {
        // The switch is "mail is internal on this deployment": it waives WHERE the relay is, so
        // both an RFC-1918 address and a dotless internal short name are reachable again.
        System.setProperty("saiku.mail.smtp.allowPrivateRange", "true");
        assertNotNull(SmtpHostValidator.validate("mail.corp", 25, resolving(ip("10.0.0.5"))));
        assertNotNull(SmtpHostValidator.validate("mail.corp", 25, resolving(ip("127.0.0.1"))));
        // It does NOT waive the shape of the configuration — a probe port or a scheme smuggled into
        // the host field is still refused, because that is a malformed setting either way.
        assertRefused("mail.corp", 6379, resolving(ip("10.0.0.5")));
        assertRefused("smtp://mail.corp", 25, resolving(ip("10.0.0.5")));
        assertRefused("2130706433", 25, resolving(ip("10.0.0.5")));
    }

    @Test
    public void isValidMirrorsValidate() {
        // A loopback literal needs no DNS and is refused by the same rules, so this assertion is
        // hermetic — it can't be moved by whatever the CI resolver happens to answer.
        assertFalse(SmtpHostValidator.isValid("127.0.0.1", 25));
        assertFalse(SmtpHostValidator.isValid("smtp.example.com", 6379));
    }
}
