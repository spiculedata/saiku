/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * The Drive REST conversation, against a loopback HTTP server (saiku#1987) — so the multipart body,
 * the folder pinning and the sanitised error mapping are all exercised without a Google project.
 */
public class HttpDriveUploadClientTest {

    private HttpServer server;
    private String base;

    /** What the fake Drive recorded from the last request. */
    private String lastBody;

    private String lastAuth;
    private String lastContentType;
    private String lastMethod;

    @Before
    public void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/upload", this::handleUpload);
        server.createContext("/files", this::handleMetadata);
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        lastMethod = exchange.getRequestMethod();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        respond(exchange, 200, "{\"id\":\"file-id-123\",\"name\":\"sales.csv\"}");
    }

    private void handleMetadata(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"id\":\"file-id-123\",\"name\":\"sales.csv\",\"mimeType\":\"text/csv\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private HttpDriveUploadClient client() {
        return new HttpDriveUploadClient(
                HttpClient.newHttpClient(), base + "/upload", base + "/files", Duration.ofSeconds(10));
    }

    // ---------------- the happy path ----------------

    @Test
    public void uploadsWithABearerTokenAndPinsTheFolder() throws Exception {
        String id = client().upload("tok-123", "1FolderId", "sales.csv", "text/csv", "a,b\n1,2".getBytes());

        assertEquals("file-id-123", id);
        assertEquals("POST", lastMethod);
        assertEquals("Bearer tok-123", lastAuth);
        assertNotNull("the multipart content type is required by Drive", lastContentType);
        assertTrue(lastContentType, lastContentType.startsWith("multipart/related; boundary="));
    }

    @Test
    public void theMetadataPartPinsTheUploadToTheConfiguredFolder() throws Exception {
        client().upload("tok", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
        assertTrue(
                "parents[] must be set or the file lands in the service account's root: " + lastBody,
                lastBody.contains("\"parents\":[\"1FolderId\"]"));
        assertTrue(lastBody, lastBody.contains("\"name\":\"sales.csv\""));
    }

    @Test
    public void theBodyPartCarriesTheBytesVerbatim() throws Exception {
        // Includes a NUL and a 0xFF so a charset-mangling bug in the multipart assembly is visible.
        byte[] content = new byte[] {'a', ',', 'b', '\n', 0x00, (byte) 0xFF};
        client().upload("tok", "1FolderId", "sales.csv", "text/csv", content);
        // ISO-8859-1 round-trips every byte 0x00-0xFF, so this is an exact subsequence search.
        assertTrue(
                "the binary content was mangled or truncated",
                lastBody.contains(new String(content, StandardCharsets.ISO_8859_1)));
        assertTrue(
                "the body must be terminated with the closing boundary",
                lastBody.trim().endsWith("--"));
    }

    @Test
    public void readsBackFileMetadata() throws Exception {
        Map<String, String> meta = client().fileMetadata("tok", "file-id-123");
        assertEquals("file-id-123", meta.get("id"));
        assertEquals("text/csv", meta.get("mimeType"));
    }

    // ---------------- error mapping: sanitized, no body echoed ----------------

    @Test
    public void mapsAnUploadRejectionToASanitizedError() throws Exception {
        server.removeContext("/upload");
        server.createContext("/upload", exchange -> {
            // A body that a naive implementation would happily forward into the exception message.
            respond(exchange, 403, "{\"error\":{\"message\":\"Rate Limit Exceeded\",\"token\":\"ya29.LEAK\"}}");
        });
        try {
            client().upload("tok-123", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("403"));
            assertFalse(
                    "the response body leaked: " + e.getMessage(),
                    e.getMessage().contains("ya29.LEAK"));
            assertFalse(
                    "the access token leaked: " + e.getMessage(), e.getMessage().contains("tok-123"));
        }
    }

    @Test
    public void aResponseWithoutAnIdIsAFailureNotASilentSuccess() throws Exception {
        server.removeContext("/upload");
        server.createContext("/upload", exchange -> respond(exchange, 200, "{\"name\":\"sales.csv\"}"));
        try {
            client().upload("tok", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no file id"));
        }
    }

    @Test
    public void anUnparseableResponseIsAFailure() throws Exception {
        server.removeContext("/upload");
        server.createContext("/upload", exchange -> respond(exchange, 200, "not json at all"));
        try {
            client().upload("tok", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("could not parse"));
        }
    }

    @Test
    public void anUnreachableDriveIsReportedWithoutTheRequestLine() {
        HttpDriveUploadClient dead = new HttpDriveUploadClient(
                HttpClient.newHttpClient(),
                "http://127.0.0.1:1/upload",
                "http://127.0.0.1:1/files",
                Duration.ofSeconds(2));
        try {
            dead.upload("ya29.SECRET", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertFalse(
                    "the access token leaked: " + e.getMessage(), e.getMessage().contains("ya29.SECRET"));
        }
    }

    @Test
    public void refusesToUploadWithoutAToken() {
        try {
            client().upload(null, "1FolderId", "sales.csv", "text/csv", "x".getBytes());
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no Google access token"));
        }
    }

    // ---------------- content-type hardening ----------------

    @Test
    public void passesThroughAPlainContentType() {
        assertEquals("text/csv", HttpDriveUploadClient.sanitizeContentType("text/csv"));
        assertEquals("application/pdf", HttpDriveUploadClient.sanitizeContentType("application/pdf"));
    }

    /** A CRLF in the content type would inject extra MIME headers into the multipart body. */
    @Test
    public void neutralisesAContentTypeThatTriesToInjectHeaders() {
        assertEquals(
                "application/octet-stream", HttpDriveUploadClient.sanitizeContentType("text/csv\r\nX-Injected: yes"));
        assertEquals("application/octet-stream", HttpDriveUploadClient.sanitizeContentType(null));
        assertEquals("application/octet-stream", HttpDriveUploadClient.sanitizeContentType(""));
        assertEquals("application/octet-stream", HttpDriveUploadClient.sanitizeContentType("nonsense"));
    }

    @Test
    public void aQuotedFilenameCannotBreakTheMetadataJson() throws Exception {
        client().upload("tok", "1FolderId", "a\".csv", "text/csv", "x".getBytes());
        assertTrue("the quote must be escaped in the metadata part: " + lastBody, lastBody.contains("a\\\".csv"));
    }

    /** Guard against the loopback server silently never being hit. */
    @Test
    public void theFakeDriveActuallyReceivedTheRequest() throws Exception {
        List<String> seen = new ArrayList<>();
        client().upload("tok", "1FolderId", "sales.csv", "text/csv", "x".getBytes());
        seen.add(lastMethod);
        assertEquals(List.of("POST"), seen);
    }
}
