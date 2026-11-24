/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.saiku.olap.util.SaikuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet-level request-body ceiling for the REST surface (issue #2535, CWE-400).
 *
 * <p>The launcher's {@code maxFormContentSize} (2 MB) only applies when the
 * servlet container parses a form. Jersey reads request entities itself, so
 * REST bodies — form, JSON, text, multipart — had no ceiling before a resource
 * ran, <em>including the pre-authentication login body</em>: a 30 MB repository
 * save answered {@code 200}, and a 5 MB anonymous login body was read and
 * parsed before it was answered {@code 401}.
 *
 * <p>This filter runs BEFORE the Spring Security filter chain (see web.xml), so
 * an oversized body is refused — {@code 413} — before authentication, before
 * matching, before any entity is buffered. Two layers:
 *
 * <ol>
 *   <li><b>Declared size</b>: a {@code Content-Length} above the route cap is
 *       rejected with 413 without reading a single body byte.</li>
 *   <li><b>Counted bytes</b>: the request entity is wrapped in a stream that
 *       counts what is actually read and throws {@link #TooLargeException} the
 *       moment the cap is exceeded — this covers {@code Transfer-Encoding:
 *       chunked} (no {@code Content-Length}) and a lying header. Jersey maps the
 *       exception to 413 via {@link RestBodyTooLargeMapper}; for non-Jersey
 *       consumers (Spring endpoints) the filter itself turns a propagating
 *       exception into a 413.</li>
 * </ol>
 *
 * <p><b>Caps are configurable</b> (environment wins over system property over
 * {@code saiku.properties}), with a conservative default and per-route
 * overrides so the documented upload endpoints keep working:
 *
 * <table>
 *   <tr><th>Key</th><th>Routes</th><th>Default</th></tr>
 *   <tr><td>{@code saiku.http.maxBodyBytes} / {@code SAIKU_HTTP_MAX_BODY_BYTES}</td>
 *       <td>every {@code /rest/*} body</td><td>10 MiB</td></tr>
 *   <tr><td>{@code saiku.http.sessionMaxBodyBytes} / {@code SAIKU_HTTP_SESSION_MAX_BODY_BYTES}</td>
 *       <td>{@code /rest/saiku/session} (the pre-auth login)</td><td>16 KiB</td></tr>
 *   <tr><td>{@code saiku.http.uploadMaxBodyBytes} / {@code SAIKU_HTTP_UPLOAD_MAX_BODY_BYTES}</td>
 *       <td>zip upload, image upload, Ossie import, quickstart upload, model
 *       diff, email self-send, chart (SVG) export</td><td>50 MiB</td></tr>
 * </table>
 *
 * <p>The first matching rule wins: session, then upload routes, then the
 * default. Every resolved value is clamped so a bad config can neither disable
 * nor absurdly widen a cap; see {@code docs/operator-hardening.md}.
 *
 * <p>Testing follows the {@code EmailRequestSizeFilter} precedent: the pure
 * decision core ({@link #capFor}, {@link #rejectsContentLength}) is static and
 * unit-testable without a servlet container.
 */
public class RestBodySizeFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RestBodySizeFilter.class);

    // ------------------------------------------------------------------ keys

    static final String KEY_MAX_BODY_BYTES = "saiku.http.maxBodyBytes";
    static final String KEY_SESSION_MAX_BODY_BYTES = "saiku.http.sessionMaxBodyBytes";
    static final String KEY_UPLOAD_MAX_BODY_BYTES = "saiku.http.uploadMaxBodyBytes";

    static final String ENV_MAX_BODY_BYTES = "SAIKU_HTTP_MAX_BODY_BYTES";
    static final String ENV_SESSION_MAX_BODY_BYTES = "SAIKU_HTTP_SESSION_MAX_BODY_BYTES";
    static final String ENV_UPLOAD_MAX_BODY_BYTES = "SAIKU_HTTP_UPLOAD_MAX_BODY_BYTES";

    // --------------------------------------------------------------- defaults

    /** Default cap for every {@code /rest/*} request body: {@value #DEFAULT_MAX_BODY_BYTES} bytes (10 MiB). */
    static final long DEFAULT_MAX_BODY_BYTES = 10L * 1024 * 1024;
    /** Default cap on the pre-auth login body: {@value #DEFAULT_SESSION_MAX_BODY_BYTES} bytes (16 KiB). */
    static final long DEFAULT_SESSION_MAX_BODY_BYTES = 16L * 1024;
    /**
     * Default cap on the documented upload routes: {@value #DEFAULT_UPLOAD_MAX_BODY_BYTES} bytes (50 MiB) —
     * matches the Jersey servlet's {@code multipart-config} max-request-size in web.xml, so the two
     * limits do not fight.
     */
    static final long DEFAULT_UPLOAD_MAX_BODY_BYTES = 50L * 1024 * 1024;

    static final long MIN_BODY_BYTES = 64L * 1024;
    static final long MAX_BODY_BYTES = 1024L * 1024 * 1024;
    static final long MIN_SESSION_BODY_BYTES = 1024;
    static final long MAX_SESSION_BODY_BYTES = 16L * 1024 * 1024;

    // --------------------------------------------------------------- routes

    /** Context-path-stripped paths (lowercase) served by the pre-auth login. */
    private static final String[] SESSION_PATHS = {"/rest/saiku/session", "/rest/saiku/session/"};

    /**
     * Context-path-stripped path prefixes (lowercase) of the documented upload
     * endpoints, capped at {@link #DEFAULT_UPLOAD_MAX_BODY_BYTES}. These are the
     * endpoints whose documented limits exceed the default cap: zip upload
     * (50 MB request), image upload, Ossie vendor-model import, quickstart
     * upload, admin model diff (two 8 MB sides), email self-send (48 MB JSON)
     * and the SVG chart export (20 MB server-side cap).
     */
    private static final String[] UPLOAD_PATH_PREFIXES = {
        "/rest/saiku/api/repository/zipupload",
        "/rest/saiku/api/dashboards/image/upload",
        "/rest/saiku/api/ossie/import",
        "/rest/saiku/admin/quickstart/upload",
        "/rest/saiku/api/admin/model/diff",
        "/rest/saiku/api/email/self"
    };

    /** Path suffix of the SVG chart export ({@code /rest/saiku/{username}/export/saiku/chart}). */
    private static final String CHART_EXPORT_SUFFIX = "/export/saiku/chart";

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to configure declaratively
    }

    @Override
    public void destroy() {
        // nothing to release
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String method = req.getMethod();
        if (!isBodyMethod(method)) {
            chain.doFilter(req, res);
            return;
        }

        String path = pathWithinApp(req);
        long cap = capFor(path, RestBodySizeFilter::setting);

        // Layer 1: refuse a declared oversized body without reading it.
        String contentLength = req.getHeader("Content-Length");
        if (rejectsContentLength(contentLength, cap)) {
            reject(res, cap, path);
            return;
        }

        // Layer 2: count what is actually read (chunked bodies, lying headers).
        CappedServletInputStream capped = new CappedServletInputStream(req.getInputStream(), cap, method, path);
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() {
                return capped;
            }

            @Override
            public BufferedReader getReader() {
                String enc = req.getCharacterEncoding();
                Charset cs = enc == null ? StandardCharsets.ISO_8859_1 : Charset.forName(enc);
                return new BufferedReader(new InputStreamReader(capped, cs));
            }
        };
        try {
            chain.doFilter(wrapped, res);
        } catch (TooLargeException e) {
            // Non-Jersey consumers (Spring endpoints) have no 413 mapper of their
            // own; turn the propagated exception into the same clean refusal.
            log.warn("Rejected oversized {} {} body (cap {} bytes)", method, path, cap);
            if (!res.isCommitted()) {
                res.resetBuffer();
                res.setStatus(413);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"request too large\"}");
                res.getWriter().flush();
            }
        }
    }

    /** True for the methods that carry a body and are worth capping. */
    static boolean isBodyMethod(String method) {
        if (method == null) {
            return false;
        }
        switch (method.toUpperCase()) {
            case "POST":
            case "PUT":
            case "PATCH":
                return true;
            default:
                return false;
        }
    }

    /**
     * The request path with the webapp's context path stripped (so the rules
     * below are written against the paths the REST surface is documented with).
     */
    static String pathWithinApp(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri != null && uri.startsWith(ctx)) {
            return uri.substring(ctx.length()).toLowerCase();
        }
        return uri == null ? "" : uri.toLowerCase();
    }

    /**
     * Pure decision core: does a declared {@code Content-Length} breach {@code cap}? A
     * missing, blank or malformed header returns {@code false} — the counted-stream
     * layer owns those (chunked) bodies, and a malformed header is the container's
     * problem.
     */
    static boolean rejectsContentLength(String contentLength, long cap) {
        if (contentLength == null || contentLength.isBlank()) {
            return false;
        }
        long length;
        try {
            length = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return length > cap;
    }

    /**
     * The cap for a request body on {@code path}: the session rule, then the
     * upload routes, then the default — each resolved from configuration and
     * clamped. Exposed for tests; the servlet path given here is
     * context-path-stripped and lowercased ({@link #pathWithinApp}).
     */
    static long capFor(String path, Function<String, String> setting) {
        if (isSessionPath(path)) {
            return resolve(
                    setting,
                    ENV_SESSION_MAX_BODY_BYTES,
                    KEY_SESSION_MAX_BODY_BYTES,
                    DEFAULT_SESSION_MAX_BODY_BYTES,
                    MIN_SESSION_BODY_BYTES,
                    MAX_SESSION_BODY_BYTES);
        }
        if (isUploadPath(path)) {
            return resolve(
                    setting,
                    ENV_UPLOAD_MAX_BODY_BYTES,
                    KEY_UPLOAD_MAX_BODY_BYTES,
                    DEFAULT_UPLOAD_MAX_BODY_BYTES,
                    MIN_BODY_BYTES,
                    MAX_BODY_BYTES);
        }
        return resolve(
                setting,
                ENV_MAX_BODY_BYTES,
                KEY_MAX_BODY_BYTES,
                DEFAULT_MAX_BODY_BYTES,
                MIN_BODY_BYTES,
                MAX_BODY_BYTES);
    }

    static boolean isSessionPath(String path) {
        for (String p : SESSION_PATHS) {
            if (p.equals(path)) {
                return true;
            }
        }
        return false;
    }

    static boolean isUploadPath(String path) {
        if (path == null) {
            return false;
        }
        for (String p : UPLOAD_PATH_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return path.endsWith(CHART_EXPORT_SUFFIX);
    }

    /** Resolve one cap: env, else system property, else {@code saiku.properties}, else the default; clamp. */
    private static long resolve(
            Function<String, String> setting, String envKey, String propKey, long defaultValue, long min, long max) {
        String v = setting.apply(envKey);
        if (v == null || v.isBlank()) {
            v = setting.apply(propKey);
        }
        long parsed = defaultValue;
        if (v != null && !v.isBlank()) {
            try {
                parsed = Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                log.warn("{}={} is not an integer; using default {}", propKey, v, defaultValue);
                parsed = defaultValue;
            }
        }
        if (parsed < min) {
            log.warn("{} clamped up to the floor {}", propKey, min);
            return min;
        }
        if (parsed > max) {
            log.warn("{} clamped down to the ceiling {}", propKey, max);
            return max;
        }
        return parsed;
    }

    /**
     * Setting lookup as the filter uses it live: environment first (matching the
     * {@code EmailRequestSizeFilter} idiom), then the live system property, then
     * {@code saiku.properties}.
     */
    static String setting(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        return SaikuProperties.getPropString(key, null);
    }

    private void reject(HttpServletResponse res, long cap, String path) throws IOException {
        log.warn("Rejected oversized POST/PUT/PATCH body for {} (Content-Length > cap {} bytes)", path, cap);
        res.resetBuffer();
        res.setStatus(413);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"request too large\"}");
        res.getWriter().flush();
    }

    /**
     * Thrown by {@link CappedServletInputStream} the moment a request body
     * exceeds its cap. Unchecked (an {@link IOException} would be wrapped by
     * Jersey into a generic processing failure); mapped to 413 for Jersey
     * consumers by {@link RestBodyTooLargeMapper} and caught by the filter for
     * the rest.
     */
    public static class TooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * A {@link ServletInputStream} that counts what the consumer actually reads
     * and refuses to hand over more than {@code cap} bytes — so a chunked (or
     * mis-declared) body can never be fully buffered. Blocking only: {@link
     * #isReady()} is true until EOF, and a read listener is not supported (the
     * REST surface reads entities synchronously).
     */
    static final class CappedServletInputStream extends ServletInputStream {
        private final java.io.InputStream in;
        private final long cap;
        private final String method;
        private final String path;
        private long count;
        private boolean finished;

        CappedServletInputStream(java.io.InputStream in, long cap, String method, String path) {
            this.in = in;
            this.cap = cap;
            this.method = method;
            this.path = path;
        }

        @Override
        public int read() throws IOException {
            if (finished) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                finished = true;
                return -1;
            }
            counted(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (finished) {
                return -1;
            }
            int n = in.read(b, off, len);
            if (n < 0) {
                finished = true;
                return -1;
            }
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        private void counted(int n) {
            count += n;
            if (count > cap) {
                log.warn(
                        "Rejected oversized {} {} body: {} bytes read exceeds the {}-byte cap",
                        method,
                        path,
                        count,
                        cap);
                throw new TooLargeException("request body exceeds the " + cap + "-byte limit");
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return !finished;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new IllegalStateException("async read listeners are not supported on the capped request stream");
        }
    }
}
