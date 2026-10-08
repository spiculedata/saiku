/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JDK-HttpClient {@link DriveUploadClient} (saiku#1987) — Drive v3 {@code files.create} with a
 * {@code multipart/related} body. No {@code google-api-client} dependency: one multipart request and
 * one JSON response is a poor reason to pull a transitive dependency tree into the WAR, and the
 * dependency-check gate would then own its CVEs.
 *
 * <p>Endpoints are constructor-overridable so the connector's HTTP conversation is testable against a
 * loopback server without a Google project.
 */
public final class HttpDriveUploadClient implements DriveUploadClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDriveUploadClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BOUNDARY = "saiku-export-destination-boundary";
    private static final String CRLF = "\r\n";

    private final HttpClient http;
    private final String uploadEndpoint;
    private final String metadataEndpoint;
    private final Duration timeout;

    /** Production endpoints. */
    public HttpDriveUploadClient() {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                "https://www.googleapis.com/upload/drive/v3/files",
                "https://www.googleapis.com/drive/v3/files",
                Duration.ofSeconds(60));
    }

    /** Visible for tests: every endpoint and the HTTP client are overridable. */
    public HttpDriveUploadClient(HttpClient http, String uploadEndpoint, String metadataEndpoint, Duration timeout) {
        this.http = http;
        this.uploadEndpoint = uploadEndpoint;
        this.metadataEndpoint = metadataEndpoint;
        this.timeout = timeout;
    }

    @Override
    public String upload(String accessToken, String folderId, String filename, String contentType, byte[] content)
            throws ExportDeliveryException {
        if (isBlank(accessToken)) {
            throw ExportDeliveryException.unavailable("no Google access token available");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        // parents[] pins the upload to the configured folder; omitting it would land in the service
        // account's "My Drive" root, which is exactly the containment the threat model relies on.
        String metadataJson = "{\"name\":" + jsonString(filename) + ",\"parents\":[" + jsonString(folderId) + "]}";
        byte[] body = multipartRelated(metadataJson, contentType, content);

        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadEndpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/related; boundary=" + BOUNDARY)
                // NB: no explicit Content-Length. The JDK HttpClient rejects a manually-set one
                // ("restricted header name") and derives it from the byte-array publisher, which is
                // what we want anyway.
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = send(request, "upload the export to Google Drive");
        if (response.statusCode() / 100 != 2) {
            throw ExportDeliveryException.remote("Google Drive upload returned HTTP " + response.statusCode());
        }
        String fileId = fileIdFrom(response.body());
        log.debug("Uploaded {} ({} bytes) to Google Drive file {}", filename, content.length, fileId);
        return fileId;
    }

    @Override
    public Map<String, String> fileMetadata(String accessToken, String fileId) throws ExportDeliveryException {
        if (isBlank(accessToken) || isBlank(fileId)) {
            throw ExportDeliveryException.unavailable("no access token or file id for a Drive metadata read");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(metadataEndpoint + "?fields=id,name,mimeType&fileId="
                        + java.net.URLEncoder.encode(fileId, StandardCharsets.UTF_8)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = send(request, "read Google Drive file metadata");
        if (response.statusCode() / 100 != 2) {
            throw ExportDeliveryException.remote("Google Drive metadata read returned HTTP " + response.statusCode());
        }
        return parseFields(response.body());
    }

    private HttpResponse<String> send(HttpRequest request, String what) throws ExportDeliveryException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExportDeliveryException.unavailable("interrupted while trying to " + what);
        } catch (IOException e) {
            // Never forward e.getMessage() — an HttpClient message can quote the request line, and the
            // Authorization header is on it.
            throw ExportDeliveryException.unavailable("could not reach Google Drive to " + what + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** Drive's {@code files.create} returns the file resource; we only want the id. */
    private String fileIdFrom(String body) throws ExportDeliveryException {
        Map<String, String> fields = parseFields(body);
        String id = fields.get("id");
        if (id == null || id.isBlank()) {
            throw ExportDeliveryException.remote("Google Drive accepted the upload but returned no file id");
        }
        return id;
    }

    private static Map<String, String> parseFields(String body) throws ExportDeliveryException {
        try {
            Map<String, Object> parsed = MAPPER.readValue(body == null ? "{}" : body, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            parsed.forEach((k, v) -> {
                if (v != null && !(v instanceof Map) && !(v instanceof java.util.List)) {
                    out.put(k, String.valueOf(v));
                }
            });
            return out;
        } catch (IOException e) {
            throw ExportDeliveryException.remote("Google Drive returned a response Saiku could not parse");
        }
    }

    /**
     * Build the {@code multipart/related} body: a JSON metadata part, then the bytes. Written by hand
     * rather than with a MIME library because the format is fixed and a wrong content-length here
     * fails only in production.
     */
    private static byte[] multipartRelated(String metadataJson, String contentType, byte[] content)
            throws ExportDeliveryException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(("--" + BOUNDARY + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/json; charset=UTF-8".getBytes(StandardCharsets.UTF_8));
            out.write((CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            out.write((CRLF + "--" + BOUNDARY + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + sanitizeContentType(contentType)).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Transfer-Encoding: binary" + CRLF + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write((CRLF + "--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExportDeliveryException("could not assemble the Google Drive upload body");
        }
        return out.toByteArray();
    }

    /**
     * A content type arrives from the artifact, which in turn comes from a job payload. A CRLF in it
     * would let an admin-authored job inject extra MIME headers into this multipart body, so only a
     * conservative token is allowed through and anything else falls back to octet-stream.
     */
    static String sanitizeContentType(String contentType) {
        if (contentType == null
                || !contentType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(;[\\x20-\\x7e]*)?")
                || contentType.contains("\r")
                || contentType.contains("\n")) {
            return "application/octet-stream";
        }
        return contentType;
    }

    private static String jsonString(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
