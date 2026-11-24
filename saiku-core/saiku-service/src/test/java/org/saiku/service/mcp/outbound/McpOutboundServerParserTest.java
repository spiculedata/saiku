/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.util.Random;
import org.junit.Test;
import org.saiku.datasources.connection.encrypt.CryptoUtil;
import org.saiku.service.schedule.alert.WebhookUrlValidator;

/**
 * Golden-path parses succeed, every documented error code has a hand-authored trigger, and a fuzz
 * test asserts no random input ever leaks a RuntimeException — same discipline as {@code
 * AgentSpaceParserTest} (malformed registration JSON must fail with a structured error, not a stack
 * trace on the admin REST layer).
 */
public class McpOutboundServerParserTest {

    /** Hermetic resolver: every host "resolves" to a public address — no live DNS in tests.
     *  Public so other modules' tests (e.g. the admin-resource gates) can reuse it. */
    public static final WebhookUrlValidator.HostResolver RESOLVER =
            host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})};

    @Test
    public void parsesGoldenPath() throws Exception {
        String encrypted = CryptoUtil.encrypt("sk-secret");
        String json = "{\"id\":\"notion\",\"name\":\"Notion workspace\",\"url\":\"https://mcp.notion.com/mcp\","
                + "\"authHeaderName\":\"Authorization\",\"authHeaderValue\":\"" + esc(encrypted) + "\","
                + "\"enabledTools\":[\"search_docs\",\"get_page\"]}";
        McpOutboundServer server = McpOutboundServerParser.parse("notion.json", json, RESOLVER);
        assertEquals("notion", server.id());
        assertEquals("Notion workspace", server.name());
        assertEquals("https://mcp.notion.com/mcp", server.url());
        assertEquals("Authorization", server.authHeaderName());
        assertEquals("sk-secret", server.authHeaderValue()); // decrypted in memory
        assertTrue(server.hasAuthHeader());
        assertEquals(2, server.enabledTools().size());
        assertTrue(server.isToolEnabled("search_docs"));
        assertFalse(server.isToolEnabled("delete_everything"));
    }

    @Test
    public void optionalFieldsAreOptional() throws Exception {
        String json = "{\"id\":\"minimal\",\"name\":\"Minimal\",\"url\":\"https://example.com/mcp\"}";
        McpOutboundServer server = McpOutboundServerParser.parse("s.json", json, RESOLVER);
        assertNull(server.authHeaderName());
        assertNull(server.authHeaderValue());
        assertFalse(server.hasAuthHeader());
        assertTrue(server.enabledTools().isEmpty());
    }

    /** saiku#2542: the outbound gate is https-only and refuses internal addresses, like the alert
     * webhooks — a credential header must never travel over plaintext or to an internal host. */
    @Test
    public void unsafeUrlsAreRefused() {
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"http://localhost:8811/mcp\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"http://mcp.example.com/mcp\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://127.0.0.1:9911/mcp\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://10.0.0.5/mcp\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://169.254.169.254/latest\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://localhost/mcp\"}", "INVALID_URL");
        // user-info in the authority (the credential would ride the URL).
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://user:pass@mcp.example.com/mcp\"}", "INVALID_URL");
    }

    /** saiku#2542: a restricted or non-token credential header name is refused at parse (save)
     * time — not on every later discovery call. */
    @Test
    public void restrictedHeaderNamesAreRefused() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://mcp.example.com/mcp\","
                        + "\"authHeaderName\":\"Host\",\"authHeaderValue\":\"evil\"}",
                "RESTRICTED_HEADER_NAME");
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://mcp.example.com/mcp\","
                        + "\"authHeaderName\":\"Content-Length\",\"authHeaderValue\":\"1\"}",
                "RESTRICTED_HEADER_NAME");
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://mcp.example.com/mcp\","
                        + "\"authHeaderName\":\"bad header\",\"authHeaderValue\":\"v\"}",
                "INVALID_HEADER_NAME");
    }

    @Test
    public void rejectsEmpty() {
        assertParseCode("", "EMPTY_SERVER");
        assertParseCode("   ", "EMPTY_SERVER");
    }

    @Test
    public void rejectsMalformedJson() {
        assertParseCode("not json", "MALFORMED_JSON");
        assertParseCode("[\"not\", \"object\"]", "MALFORMED_JSON");
    }

    @Test
    public void rejectsMissingId() {
        assertParseCode("{\"name\":\"X\",\"url\":\"https://e.com\"}", "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingName() {
        assertParseCode("{\"id\":\"x\",\"url\":\"https://e.com\"}", "MISSING_FIELD");
    }

    @Test
    public void rejectsMissingUrl() {
        assertParseCode("{\"id\":\"x\",\"name\":\"X\"}", "MISSING_FIELD");
    }

    @Test
    public void rejectsInvalidId() {
        assertParseCode("{\"id\":\"Has Space\",\"name\":\"X\",\"url\":\"https://e.com\"}", "INVALID_ID");
        assertParseCode("{\"id\":\"UPPER\",\"name\":\"X\",\"url\":\"https://e.com\"}", "INVALID_ID");
        assertParseCode("{\"id\":\"-leading-dash\",\"name\":\"X\",\"url\":\"https://e.com\"}", "INVALID_ID");
    }

    @Test
    public void rejectsBlankName() {
        assertParseCode("{\"id\":\"x\",\"name\":\"   \",\"url\":\"https://e.com\"}", "BLANK_FIELD");
    }

    @Test
    public void rejectsTypeMismatch() {
        assertParseCode("{\"id\":42,\"name\":\"X\",\"url\":\"https://e.com\"}", "TYPE_MISMATCH");
    }

    @Test
    public void rejectsUnknownField() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://e.com\",\"authHeadrName\":\"typo\"}", "UNKNOWN_FIELD");
    }

    @Test
    public void rejectsInvalidUrl() {
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"not a url\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"ftp://e.com/mcp\"}", "INVALID_URL");
        assertParseCode("{\"id\":\"x\",\"name\":\"X\",\"url\":\"file:///etc/passwd\"}", "INVALID_URL");
    }

    @Test
    public void rejectsIncompleteAuth() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://e.com\",\"authHeaderName\":\"Authorization\"}",
                "INCOMPLETE_AUTH");
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://e.com\",\"authHeaderValue\":\""
                        + esc(CryptoUtil.encrypt("v")) + "\"}",
                "INCOMPLETE_AUTH");
    }

    @Test
    public void rejectsUndecryptableCredential() {
        // Not "v2:"-prefixed and not valid legacy-hex → CryptoUtil.decrypt throws; the parser must
        // convert that into a structured BAD_CREDENTIAL, never leak the raw decrypt exception.
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://e.com\","
                        + "\"authHeaderName\":\"Authorization\",\"authHeaderValue\":\"not-valid-ciphertext!!\"}",
                "BAD_CREDENTIAL");
    }

    @Test
    public void rejectsEnabledToolsWrongType() {
        assertParseCode(
                "{\"id\":\"x\",\"name\":\"X\",\"url\":\"https://e.com\",\"enabledTools\":\"not-an-array\"}",
                "TYPE_MISMATCH");
    }

    /**
     * Fuzz: 500 random inputs must all either parse or fail with ParseException. Nothing may throw a
     * RuntimeException — that would surface as HTTP 500 from the admin resource.
     */
    @Test
    public void fuzzParseNeverPanics() {
        Random rng = new Random(0x1425C0FFEEL);
        int iterations = 500;
        int parsed = 0;
        int rejected = 0;
        for (int i = 0; i < iterations; i++) {
            String text = randomInput(rng, i);
            try {
                McpOutboundServer server = McpOutboundServerParser.parse("fuzz-" + i + ".json", text, RESOLVER);
                assertNotNull(server);
                parsed++;
            } catch (McpOutboundServerParser.ParseException e) {
                assertNotNull(e.code());
                assertNotNull(e.source());
                assertNotNull(e.getMessage());
                rejected++;
            } catch (RuntimeException e) {
                fail("iteration " + i + " leaked " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nInput was:\n" + text);
            }
        }
        assertTrue("fuzz parsed " + parsed + " / rejected " + rejected, rejected > 0);
    }

    private static String randomInput(Random rng, int i) {
        switch (i % 5) {
            case 0:
                return "";
            case 1:
                return randomBytes(rng, rng.nextInt(200));
            case 2:
                return "{" + randomBytes(rng, rng.nextInt(200)) + "}";
            case 3:
                return "{\"id\":\"" + randomBytes(rng, 4 + rng.nextInt(20)) + "\",\"name\":\"X\"," + "\"url\":\""
                        + randomBytes(rng, rng.nextInt(30)) + "\"}";
            default:
                return "{\"id\":\"srv-" + i + "\",\"name\":\"X\",\"url\":\"https://example.com/mcp\"}";
        }
    }

    private static String randomBytes(Random rng, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (32 + rng.nextInt(95))); // printable ASCII, includes quotes/braces
        }
        return sb.toString();
    }

    private static void assertParseCode(String json, String expectedCode) {
        try {
            McpOutboundServer s = McpOutboundServerParser.parse("t.json", json, RESOLVER);
            fail("expected ParseException(" + expectedCode + ") but parsed: " + s);
        } catch (McpOutboundServerParser.ParseException e) {
            assertEquals(expectedCode, e.code());
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
