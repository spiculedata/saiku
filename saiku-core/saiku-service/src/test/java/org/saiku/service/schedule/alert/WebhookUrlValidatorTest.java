/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.Test;

/**
 * SSRF-guard tests for {@link WebhookUrlValidator} (saiku#1098). Uses IP literals (no DNS) and an
 * injected {@link WebhookUrlValidator.HostResolver} so the suite is hermetic — it never hits live DNS.
 */
public class WebhookUrlValidatorTest {

    /** A resolver that maps every host to a fixed address (or throws for an unresolvable case). */
    private static WebhookUrlValidator.HostResolver resolvesTo(String ip) {
        return host -> new InetAddress[] {InetAddress.getByName(ip)};
    }

    private static final WebhookUrlValidator.HostResolver UNRESOLVABLE = host -> {
        throw new UnknownHostException(host);
    };

    /** Public routable address so a syntactically-valid hostname passes the range check. */
    private static final WebhookUrlValidator.HostResolver PUBLIC = resolvesTo("93.184.216.34");

    private static void assertRejected(String url, WebhookUrlValidator.HostResolver r) {
        assertFalse("should reject " + url, WebhookUrlValidator.isValid(url, r));
        try {
            WebhookUrlValidator.validate(url, r);
            fail("expected rejection for " + url);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void acceptsPublicHttpsHostname() {
        assertTrue(WebhookUrlValidator.isValid("https://hooks.example.com/path?x=1", PUBLIC));
    }

    @Test
    public void acceptsPublicIpLiteralWithoutDns() {
        // No resolver call needed for a literal — validate() checks the bytes directly.
        assertTrue(WebhookUrlValidator.isValid("https://93.184.216.34/hook", UNRESOLVABLE));
    }

    @Test
    public void rejectsPlainHttp() {
        assertRejected("http://hooks.example.com/path", PUBLIC);
    }

    @Test
    public void rejectsLoopback() {
        assertRejected("https://127.0.0.1/hook", PUBLIC);
        assertRejected("https://localhost/hook", PUBLIC);
        assertRejected("https://foo.localhost/hook", PUBLIC);
    }

    @Test
    public void rejectsLinkLocalMetadataEndpoint() {
        assertRejected("https://169.254.169.254/latest/meta-data/", PUBLIC);
        assertRejected("https://metadata.google.internal/computeMetadata/v1/", PUBLIC);
    }

    @Test
    public void rejectsPrivateRangesByLiteral() {
        assertRejected("https://10.0.0.5/hook", PUBLIC);
        assertRejected("https://192.168.1.10/hook", PUBLIC);
        assertRejected("https://172.16.5.5/hook", PUBLIC);
    }

    @Test
    public void rejectsCgnatAndZeroNet() {
        assertRejected("https://100.64.1.1/hook", PUBLIC);
        assertRejected("https://0.0.0.0/hook", PUBLIC);
    }

    @Test
    public void rejectsIpv6LoopbackAndUla() {
        assertRejected("https://[::1]/hook", PUBLIC);
        assertRejected("https://[fc00::1]/hook", PUBLIC);
        assertRejected("https://[fd12:3456::1]/hook", PUBLIC);
    }

    @Test
    public void rejectsBareInternalHostname() {
        assertRejected("https://internal-service/hook", PUBLIC);
        assertRejected("https://db.local/hook", PUBLIC);
    }

    @Test
    public void rejectsPublicHostnameThatResolvesToInternal() {
        // The classic DNS-based SSRF: a public-looking name that resolves into a private range.
        assertRejected("https://evil.example.com/hook", resolvesTo("10.1.2.3"));
        assertRejected("https://evil.example.com/hook", resolvesTo("169.254.169.254"));
    }

    @Test
    public void rejectsUnresolvableHostname() {
        assertRejected("https://nope.invalid/hook", UNRESOLVABLE);
    }

    @Test
    public void rejectsCredentialsInUrl() {
        assertRejected("https://user:pass@hooks.example.com/hook", PUBLIC);
    }

    @Test
    public void rejectsNullAndBlankAndGarbage() {
        assertRejected(null, PUBLIC);
        assertRejected("", PUBLIC);
        assertRejected("not a url", PUBLIC);
        assertRejected("ftp://example.com/x", PUBLIC);
    }

    // --- saiku#1846: numeric-encoded IPv4 literals are detected + blocked explicitly ---

    @Test
    public void rejectsDecimalEncodedLoopback() {
        // 2130706433 == 127.0.0.1 — must be recognised as a literal and rejected via the byte check,
        // not merely by the incidental "no-dot ⇒ blocked" heuristic.
        assertRejected("https://2130706433/hook", PUBLIC);
    }

    @Test
    public void rejectsHexEncodedLoopback() {
        // 0x7f000001 == 127.0.0.1
        assertRejected("https://0x7f000001/hook", PUBLIC);
    }

    @Test
    public void rejectsOctalDottedLoopback() {
        // 0177.0.0.1 == 127.0.0.1 (octal first octet)
        assertRejected("https://0177.0.0.1/hook", PUBLIC);
    }

    @Test
    public void rejectsShortDottedDecimalLoopback() {
        // 127.1 == 127.0.0.1 (BSD short form)
        assertRejected("https://127.1/hook", PUBLIC);
    }

    @Test
    public void rejectsDecimalEncodedPrivateAddress() {
        // 3232235521 == 192.168.0.1
        assertRejected("https://3232235521/hook", PUBLIC);
    }

    // --- saiku#1846: validateResolved surfaces the approved address set for connect-time pinning ---

    @Test
    public void validateResolvedReturnsApprovedAddresses() {
        WebhookUrlValidator.ValidatedTarget vt =
                WebhookUrlValidator.validateResolved("https://hooks.example.com/hook", resolvesTo("93.184.216.34"));
        assertEquals("hooks.example.com", vt.uri().getHost());
        assertEquals(1, vt.addresses().length);
        assertEquals("93.184.216.34", vt.addresses()[0].getHostAddress());
    }

    @Test
    public void validateResolvedLiteralReturnsTheLiteral() {
        WebhookUrlValidator.ValidatedTarget vt =
                WebhookUrlValidator.validateResolved("https://93.184.216.34/hook", UNRESOLVABLE);
        assertEquals(1, vt.addresses().length);
        assertEquals("93.184.216.34", vt.addresses()[0].getHostAddress());
    }

    // --- saiku#2550g: IPv6 transition mechanisms that tunnel an IPv4 target ---

    @Test
    public void rejectsNat64Prefix() {
        // 64:ff9b::/96 with an embedded internal IPv4 — 64:ff9b::a9fe:a9fe = 169.254.169.254.
        assertRejected("https://[64:ff9b::169.254.169.254]/hook", PUBLIC);
        // The whole /96 is refused regardless of the embedded v4 (DNS64-synthesised
        // addresses are never a legitimate webhook target).
        assertRejected("https://[64:ff9b::1.2.3.4]/hook", PUBLIC);
    }

    @Test
    public void rejects6to4WithEmbeddedInternalIpv4() {
        // 2002::/16 — the next 32 bits are the embedded IPv4. 2002:0a00:0001:: = 10.0.0.1.
        assertRejected("https://[2002:a00:1::1]/hook", PUBLIC);
        assertRejected("https://[2002:c0a8:1::]/hook", PUBLIC); // 192.168.0.1
        // ...but a 6to4 address with a PUBLIC embedded IPv4 is not itself blocked.
        assertTrue(WebhookUrlValidator.isValid("https://[2002:5db8:d822::1]/hook", PUBLIC));
    }

    @Test
    public void rejectsIpv4CompatibleWithEmbeddedInternalIpv4() {
        // ::a.b.c.d — deprecated transition form, still parses everywhere.
        assertRejected("https://[::127.0.0.1]/hook", PUBLIC);
        assertRejected("https://[::10.0.0.1]/hook", PUBLIC);
        assertRejected("https://[::169.254.169.254]/hook", PUBLIC);
        // IPv4-mapped ::ffff:a.b.c.d keeps its existing (Java-handled) checks.
        assertRejected("https://[::ffff:127.0.0.1]/hook", PUBLIC);
    }
}
