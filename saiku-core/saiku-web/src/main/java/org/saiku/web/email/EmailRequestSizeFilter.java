/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F2 — pre-parse request-size ceiling for {@code POST /saiku/api/email/self}.
 *
 * <p>The self-send endpoint accepts a JSON body carrying two base64 artifacts (chart PNG + PDF).
 * {@link EmailMessageAssembler#decodeCapped} caps each decoded artifact at 15&nbsp;MB, but that
 * in-handler check only runs <em>after</em> Jackson has already materialised the whole JSON body in
 * heap. A hostile client can therefore force a large allocation before any cap applies. This
 * {@code @PreMatching} filter rejects an oversized request by its declared {@code Content-Length}
 * <em>before</em> the entity is read (before matching, before Jackson) — so the allocation never
 * happens.
 *
 * <p><b>Scope.</b> A {@code @PreMatching} filter cannot be name-bound (name binding requires the
 * resource method to be matched first, which is exactly what pre-matching precedes), so the endpoint
 * scope is enforced by an explicit method+path check in {@link #decide}: only {@code POST} to the
 * exact JAX-RS path {@code saiku/api/email/self} is subject to the cap. Every other endpoint —
 * including large-but-legitimate ones such as exports — is returned unmodified. The filter is global
 * but its effect is not.
 *
 * <p><b>Chunked-transfer caveat.</b> A request sent with {@code Transfer-Encoding: chunked} (no
 * {@code Content-Length}) cannot be pre-checked this way; such a request passes this filter and the
 * per-artifact caps in {@link EmailMessageAssembler} remain the backstop. The webapp's servlet-level
 * {@code multipart-config} (50&nbsp;MB/request) does not apply to this JSON endpoint, so the
 * Content-Length pre-check is the primary defence for the common (length-declared) case.
 *
 * <p>Testing follows the {@code AiAuditFilter} precedent: the decision lives in the pure static
 * {@link #decide} core so it is exercisable without standing up a JAX-RS container.
 */
@Provider
@PreMatching
public class EmailRequestSizeFilter implements ContainerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EmailRequestSizeFilter.class);

    /**
     * The JAX-RS path (relative to the servlet mount, as reported by {@code UriInfo.getPath()}) of
     * the guarded endpoint — {@code @Path("/saiku/api/email")} + {@code @Path("/self")}.
     */
    static final String SELF_PATH = "saiku/api/email/self";

    /**
     * Default cap: 48&nbsp;MB. The endpoint's intended worst case is two 15&nbsp;MB raw artifacts;
     * base64 inflates by ~33%, so ~40&nbsp;MB encoded, plus the JSON envelope, {@code summaryHtml},
     * subject and field/quoting overhead. 48&nbsp;MB leaves ~8&nbsp;MB of headroom over that while
     * staying below the servlet-layer 50&nbsp;MB multipart backstop, so the two limits don't fight.
     */
    static final long DEFAULT_MAX_REQUEST_BYTES = 48L * 1024 * 1024;

    /** Clamp floor: a deliberately low admin value is honoured, but never below 1&nbsp;MB. */
    static final long MIN_MAX_REQUEST_BYTES = 1L * 1024 * 1024;

    /** Clamp ceiling: guards against an absurd value that would defeat the purpose. */
    static final long MAX_MAX_REQUEST_BYTES = 256L * 1024 * 1024;

    /** Config key idiom mirrors {@code MailConfig}: env wins over system property over default. */
    static final String ENV_KEY = "SAIKU_EMAIL_MAX_REQUEST_BYTES";

    static final String PROP_KEY = "saiku.email.maxRequestBytes";

    private long maxRequestBytes = resolveMaxRequestBytes(System::getenv, System::getProperty);

    /** Spring/test setter to override the resolved cap (clamped). */
    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = clamp(maxRequestBytes);
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        // Thin adapter over the pure core — pull the primitives off the JAX-RS envelope.
        Response abort = decide(
                request.getMethod(),
                request.getUriInfo() == null ? null : request.getUriInfo().getPath(),
                request.getHeaderString(HttpHeaders.CONTENT_LENGTH),
                maxRequestBytes);
        if (abort != null) {
            // Aborting here short-circuits the chain: the resource method is never invoked and the
            // entity stream is never read (Jackson never parses the body).
            log.warn(
                    "Rejected oversized {} request (Content-Length {} > cap {} bytes)",
                    SELF_PATH,
                    request.getHeaderString(HttpHeaders.CONTENT_LENGTH),
                    maxRequestBytes);
            request.abortWith(abort);
        }
    }

    /**
     * Pure decision core: returns a 413 {@link Response} to abort with, or {@code null} to let the
     * request proceed untouched. Extracted so it's unit-testable without a JAX-RS container.
     *
     * <p>Returns {@code null} (proceed) for: any non-guarded endpoint (wrong method or path), a
     * missing/blank {@code Content-Length} (chunked — cannot be pre-checked), a malformed
     * {@code Content-Length} (left to the container), and any length within the cap.
     */
    static Response decide(String method, String path, String contentLength, long maxRequestBytes) {
        if (!isGuarded(method, path)) {
            return null;
        }
        if (contentLength == null || contentLength.isBlank()) {
            return null; // chunked / length unknown — not pre-checkable here
        }
        long length;
        try {
            length = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return null; // malformed header — let the container reject it
        }
        if (length <= maxRequestBytes) {
            return null;
        }
        // Generic, internals-free client error.
        return Response.status(413)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "request too large"))
                .build();
    }

    /** True only for {@code POST} to the exact self-send path — the sole endpoint this filter caps. */
    static boolean isGuarded(String method, String path) {
        if (method == null || !HttpMethod.POST.equalsIgnoreCase(method.trim())) {
            return false;
        }
        return SELF_PATH.equals(normalise(path));
    }

    private static String normalise(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Resolve the cap: {@code SAIKU_EMAIL_MAX_REQUEST_BYTES} env, else {@code saiku.email.maxRequestBytes}
     *  system property, else the default — then clamp. */
    static long resolveMaxRequestBytes(Function<String, String> env, Function<String, String> prop) {
        String v = env.apply(ENV_KEY);
        if (v == null || v.isBlank()) {
            v = prop.apply(PROP_KEY);
        }
        long parsed = DEFAULT_MAX_REQUEST_BYTES;
        if (v != null && !v.isBlank()) {
            try {
                parsed = Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                parsed = DEFAULT_MAX_REQUEST_BYTES;
            }
        }
        return clamp(parsed);
    }

    static long clamp(long v) {
        if (v < MIN_MAX_REQUEST_BYTES) {
            return MIN_MAX_REQUEST_BYTES;
        }
        if (v > MAX_MAX_REQUEST_BYTES) {
            return MAX_MAX_REQUEST_BYTES;
        }
        return v;
    }
}
