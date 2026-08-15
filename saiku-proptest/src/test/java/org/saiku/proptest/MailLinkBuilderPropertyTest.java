/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.saiku.service.mail.send.MailLinkBuilder;

/**
 * Property-based tests for {@link MailLinkBuilder}, which builds the one-click unsubscribe and
 * consent-confirm links that go out in email.
 *
 * <p>Both links carry two attacker-influenceable values in the query string: a recipient address and
 * an HMAC token. If either is not encoded, an address containing {@code &} or {@code =} splits the
 * query and the server reads a DIFFERENT token than the one that was issued — silently, because the
 * resulting URL is still perfectly well-formed. That is a consent-state bug (unsubscribing the wrong
 * address, or confirming one that never consented) rather than a crash, so nothing surfaces it.
 *
 * <p>These links are also emitted into an RFC 2369 header, where a stray newline would be header
 * injection.
 */
class MailLinkBuilderPropertyTest {

    private static final String BASE = "https://saiku.example.com";

    /** Values that break a query string when concatenated unencoded. */
    private static final List<String> HOSTILE = List.of(
            "a&b@x.com",
            "a=b@x.com",
            "a?b@x.com",
            "a#b@x.com",
            "a b@x.com",
            "a+b@x.com",
            "a%40b@x.com",
            "a\nb@x.com",
            "a&token=forged@x.com",
            "üser@exämple.com");

    /** Parse a URL's query into its decoded parameters. */
    private static Map<String, String> queryParams(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                out.put(
                        java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * THE property. Whatever the address contains, it comes back out of the built URL byte-identical
     * — so the server acts on the address that was actually issued a token.
     */
    @HegelTest
    void theAddressAlwaysRoundTripsThroughTheUnsubscribeLink(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9_-]{10,40}"), "token");

        String url = new MailLinkBuilder(BASE).unsubscribeUrl(address, token);
        tc.note(url);

        assertNotNull(url, "no link was built for a valid address/token pair");
        assertEquals(address, queryParams(url).get("address"), "the address was mangled in transit");
    }

    /** And so does the token — reading a different token than was issued is the security failure. */
    @HegelTest
    void theTokenAlwaysRoundTrips(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(sampledFrom(List.of("a&b", "a=b", "a b", "a+b", "a%3Db", "tok#en")), "token");

        String url = new MailLinkBuilder(BASE).unsubscribeUrl(address, token);

        assertEquals(token, queryParams(url).get("token"), "the token was mangled in transit");
    }

    /** A hostile address can never inject an extra query parameter. */
    @HegelTest
    void ahostileAddressNeverInjectsAParameter(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9]{10,20}"), "token");

        String url = new MailLinkBuilder(BASE).unsubscribeUrl(address, token);

        assertEquals(2, queryParams(url).size(), "an extra query parameter appeared: " + url);
    }

    /** The confirm link holds the same guarantees — it grants consent, so it matters as much. */
    @HegelTest
    void theConfirmLinkRoundTripsBothValues(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(sampledFrom(List.of("a&b", "plain", "a=b", "a b")), "token");

        String url = new MailLinkBuilder(BASE).confirmUrl(address, token);
        tc.note(url);

        Map<String, String> params = queryParams(url);
        assertEquals(address, params.get("e"), "the confirm address was mangled");
        assertEquals(token, params.get("t"), "the confirm token was mangled");
        assertEquals(2, params.size(), "an extra query parameter appeared: " + url);
    }

    /**
     * No raw newline ever reaches a built link. These are emitted into an RFC 2369 header, where a
     * newline is header injection.
     */
    @HegelTest
    void noBuiltLinkEverContainsARawNewline(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(sampledFrom(List.of("tok", "a\nb", "a\rb")), "token");

        MailLinkBuilder builder = new MailLinkBuilder(BASE);
        for (String link : List.of(
                String.valueOf(builder.unsubscribeUrl(address, token)),
                String.valueOf(builder.unsubscribeHeader(address, token)),
                String.valueOf(builder.confirmUrl(address, token)))) {
            assertFalse(link.contains("\n"), "a raw newline reached a link: " + link);
            assertFalse(link.contains("\r"), "a raw carriage return reached a link: " + link);
        }
    }

    /** The header form is exactly the URL in angle brackets, per RFC 2369. */
    @HegelTest
    void theHeaderIsTheUrlInAngleBrackets(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9]{8,16}"), "token");

        MailLinkBuilder builder = new MailLinkBuilder(BASE);

        assertEquals("<" + builder.unsubscribeUrl(address, token) + ">", builder.unsubscribeHeader(address, token));
    }

    /** Every built link is an absolute URL under the configured base — never a relative path. */
    @HegelTest
    void everyLinkIsAbsoluteAndUnderTheConfiguredBase(TestCase tc) {
        String address = tc.draw(sampledFrom(HOSTILE), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9]{8,16}"), "token");

        MailLinkBuilder builder = new MailLinkBuilder(BASE);
        for (String link : List.of(builder.unsubscribeUrl(address, token), builder.confirmUrl(address, token))) {
            assertTrue(link.startsWith(BASE + "/"), "link escaped the configured base: " + link);
            assertTrue(URI.create(link).isAbsolute(), "link is not absolute: " + link);
        }
    }

    /** Missing inputs yield null rather than a half-built link that would 404 or misfire. */
    @HegelTest
    void missingInputsYieldNoLink(TestCase tc) {
        String blank = tc.draw(sampledFrom(List.of("", " ", "\t")), "blank");
        String good = tc.draw(fromRegex("[a-z]{3,8}@x.com"), "good");
        MailLinkBuilder builder = new MailLinkBuilder(BASE);

        assertNull(builder.unsubscribeUrl(blank, "tok"), "built a link with a blank address");
        assertNull(builder.unsubscribeUrl(good, blank), "built a link with a blank token");
        assertNull(builder.unsubscribeUrl(null, "tok"));
        assertNull(builder.unsubscribeUrl(good, null));
        assertNull(builder.confirmUrl(blank, "tok"), "built a confirm link with a blank address");
    }

    /** With no base URL configured, nothing is built and the builder says so. */
    @HegelTest
    void anUnconfiguredBuilderBuildsNothing(TestCase tc) {
        String address = tc.draw(fromRegex("[a-z]{3,8}@x.com"), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9]{8,16}"), "token");

        MailLinkBuilder builder = new MailLinkBuilder((String) null);

        assertFalse(builder.isConfigured(), "reported configured with no base URL");
        assertNull(builder.unsubscribeUrl(address, token));
        assertNull(builder.unsubscribeHeader(address, token));
        assertNull(builder.confirmUrl(address, token));
    }

    /** A trailing slash on the configured base never produces a doubled separator. */
    @HegelTest
    void aTrailingSlashOnTheBaseIsNormalised(TestCase tc) {
        String address = tc.draw(fromRegex("[a-z]{3,8}@x.com"), "address");
        String token = tc.draw(fromRegex("[A-Za-z0-9]{8,16}"), "token");
        String base = tc.draw(sampledFrom(List.of(BASE, BASE + "/", BASE + "//")), "base");

        String url = new MailLinkBuilder(base).unsubscribeUrl(address, token);

        assertFalse(url.substring("https://".length()).contains("//"), "doubled path separator: " + url);
    }
}
