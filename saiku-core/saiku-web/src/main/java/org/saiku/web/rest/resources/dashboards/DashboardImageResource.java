/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.dashboards;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.web.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upload + serve image assets for dashboard image tiles (issue #918).
 *
 * <p>Storage rides the same ACL-checked text-file primitives the dashboards
 * themselves use ({@link DatasourceService#saveFile}/{@code getFileData}),
 * with the image bytes Base64-encoded into a file stored <em>in the uploader's
 * home folder</em> ({@code homes/<user>/<tileId>.<ext>}). That gives us, for
 * free: the repository's path-traversal defence ({@code resolveWithinDatadir}),
 * ACL-enforced reads (a viewer who can read the path sees the image, others
 * get a 404), and no new binary storage path to secure separately.
 *
 * <p>Hardening: session auth required; the uploaded bytes are sniffed by
 * magic number and only PNG/JPEG/GIF/WebP are accepted (SVG and everything
 * else are rejected — SVG can carry script); a {@value #MAX_BYTES}-byte cap
 * applies; {@code tileId} is restricted to a safe charset; and the download
 * is served with a fixed image content-type plus {@code nosniff} + a
 * locked-down CSP so the endpoint can't be coerced into serving active
 * content.
 */
@Path("/saiku/api/dashboards/image")
public class DashboardImageResource {

    private static final Logger log = LoggerFactory.getLogger(DashboardImageResource.class);
    private static final String SAVE_OK = "Save Okay";
    /** 2 MiB raw-byte cap (≈2.7 MB Base64 on disk). */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** tileId path-segment allowlist — mirrors the client's newTileId() shape. */
    private static final java.util.regex.Pattern TILE_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private DatasourceService datasourceService;
    private SessionService sessionService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    public void setSessionService(SessionService s) {
        this.sessionService = s;
    }

    /** Store an uploaded image beside its dashboard and return its download src. */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@FormDataParam("file") InputStream fileStream, @FormDataParam("tileId") String tileId) {
        String username = currentUsername();
        if (username == null) {
            return error(Response.Status.UNAUTHORIZED, "auth", "Not authenticated");
        }
        // Defence in depth — the session username is trusted, but it becomes a
        // path segment, so reject anything that could escape the home folder.
        if (username.contains("/") || username.contains("\\") || username.contains("..")) {
            return error(Response.Status.BAD_REQUEST, "auth", "Invalid username");
        }
        if (fileStream == null) {
            return error(Response.Status.BAD_REQUEST, "file", "file required");
        }
        if (tileId == null || !TILE_ID.matcher(tileId).matches()) {
            return error(Response.Status.BAD_REQUEST, "tileId", "invalid tileId");
        }

        byte[] bytes;
        try {
            bytes = readCapped(fileStream, MAX_BYTES);
        } catch (TooLargeException e) {
            return error(Response.Status.REQUEST_ENTITY_TOO_LARGE, "file", "Image exceeds 2 MB limit");
        } catch (Exception e) {
            log.warn("image upload read failed (user={}, tile={})", username, tileId, e);
            return error(Response.Status.BAD_REQUEST, "file", "Could not read upload");
        }
        if (bytes.length == 0) {
            return error(Response.Status.BAD_REQUEST, "file", "Empty upload");
        }

        String ext = sniffImageExt(bytes);
        if (ext == null) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "file",
                    "Unsupported image type — only PNG, JPEG, GIF and WebP are allowed");
        }

        // Store in the uploader's home folder (always present + writable for the
        // owner — no fragile dashboard-path parsing). The download is ACL-checked,
        // so a viewer who can read this path sees the image and others don't.
        String assetPath = "homes/" + username + "/" + tileId + "." + ext;

        String base64 = Base64.getEncoder().encodeToString(bytes);
        String resp;
        try {
            resp = datasourceService.saveFile(base64, assetPath, username, currentRoles());
        } catch (RuntimeException e) {
            log.warn("image save rejected (user={}, path={})", username, assetPath, e);
            return error(Response.Status.FORBIDDEN, "file", "Not permitted to store here");
        }
        if (!SAVE_OK.equals(resp)) {
            return error(Response.Status.INTERNAL_SERVER_ERROR, "file", "Save rejected: " + resp);
        }
        return Response.ok(Map.of("src", downloadSrc(assetPath)))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /** Serve a stored image asset, ACL-checked, with hardened headers. */
    @GET
    @Path("/get/{path:.+}")
    public Response get(@PathParam("path") String path) {
        String username = currentUsername();
        if (username == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String ext = extensionOf(path);
        String contentType = contentTypeFor(ext);
        if (contentType == null) {
            // Only ever serve known image extensions from this endpoint, so it
            // can't be coerced into streaming arbitrary repo files.
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String base64;
        try {
            base64 = datasourceService.getFileData(path, username, currentRoles());
        } catch (RuntimeException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (base64 == null || base64.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(bytes)
                .type(contentType)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Content-Disposition", "inline")
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    /* --------------------------- helpers ---------------------------- */

    /** Read at most {@code max} bytes; throw if the stream has more. */
    private static byte[] readCapped(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > max) throw new TooLargeException();
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static final class TooLargeException extends Exception {}

    /** Identify the image type by magic number; null = unsupported (incl. SVG).
     *  Package-private for unit testing the allowlist / SVG-block. */
    static String sniffImageExt(byte[] b) {
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 0x50
                && b[2] == 0x4E
                && b[3] == 0x47
                && b[4] == 0x0D
                && b[5] == 0x0A
                && b[6] == 0x1A
                && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 6
                && b[0] == 'G'
                && b[1] == 'I'
                && b[2] == 'F'
                && b[3] == '8'
                && (b[4] == '7' || b[4] == '9')
                && b[5] == 'a') {
            return "gif";
        }
        if (b.length >= 12
                && b[0] == 'R'
                && b[1] == 'I'
                && b[2] == 'F'
                && b[3] == 'F'
                && b[8] == 'W'
                && b[9] == 'E'
                && b[10] == 'B'
                && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    static String contentTypeFor(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> null;
        };
    }

    /** Build the same-origin download path, URL-encoding each segment but
     *  preserving the slashes that map to the {path:.+} matcher. */
    private static String downloadSrc(String assetPath) {
        String[] parts = assetPath.split("/");
        StringBuilder sb = new StringBuilder("/rest/saiku/api/dashboards/image/get/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private String currentUsername() {
        if (sessionService == null) return null;
        Object u = sessionService.getAllSessionObjects().get("username");
        return u == null ? null : u.toString();
    }

    // saiku#1752: roles come from the single authoritative SecurityContextHolder reader, not the
    // lazily-seeded session "roles" map (see SessionRoles / saiku#1747).
    private List<String> currentRoles() {
        return org.saiku.web.rest.util.SessionRoles.currentRoles();
    }

    private static Response error(Response.Status status, String field, String message) {
        return Response.status(status)
                .entity(Map.of("status", "ERROR", "field", field, "error", message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
