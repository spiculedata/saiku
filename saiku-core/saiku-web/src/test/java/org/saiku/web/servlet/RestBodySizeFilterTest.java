/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit coverage for the #2535 REST body-size filter: the pure decision core
 * (route → cap, declared Content-Length) and the counted-stream layer, driven
 * through mock servlet objects so no container is needed. Configuration is
 * injected for the route tests and pinned via a system property (cleared in a
 * {@code finally}) for the live-filter tests.
 */
public class RestBodySizeFilterTest {

    private static final String NORMAL = "/rest/saiku/api/repository/resource";
    private static final String SESSION = "/rest/saiku/session";
    private static final String UPLOAD = "/rest/saiku/api/repository/zipupload";
    private static final Function<String, String> NO_CONFIG = key -> null;

    // --------------------------- route caps ---------------------------

    @Test
    public void defaultCap_forOrdinaryRoutes() {
        assertEquals(RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES, RestBodySizeFilter.capFor(NORMAL, NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/ai/ask", NO_CONFIG));
    }

    @Test
    public void sessionRoute_hasItsOwnSmallCap() {
        assertEquals(RestBodySizeFilter.DEFAULT_SESSION_MAX_BODY_BYTES, RestBodySizeFilter.capFor(SESSION, NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_SESSION_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/session/", NO_CONFIG));
        assertTrue(RestBodySizeFilter.DEFAULT_SESSION_MAX_BODY_BYTES < RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES);
    }

    @Test
    public void uploadRoutes_keepDocumentedHeadroom() {
        assertEquals(RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES, RestBodySizeFilter.capFor(UPLOAD, NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/dashboards/image/upload", NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/email/self", NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/ossie/import", NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/ossie/import/save", NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/admin/quickstart/upload", NO_CONFIG));
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/api/admin/model/diff", NO_CONFIG));
        // The chart export path carries the username segment.
        assertEquals(
                RestBodySizeFilter.DEFAULT_UPLOAD_MAX_BODY_BYTES,
                RestBodySizeFilter.capFor("/rest/saiku/admin/export/saiku/chart", NO_CONFIG));
    }

    @Test
    public void configuredValue_winsThenClamps() {
        // A lower value is honoured (session, 1 MiB, under its 16 MiB ceiling).
        assertEquals(1024L * 1024, RestBodySizeFilter.capFor(SESSION, key -> "1048576"));
        // Above the ceiling: clamped down.
        assertEquals(RestBodySizeFilter.MAX_BODY_BYTES, RestBodySizeFilter.capFor(NORMAL, key -> "999999999999"));
        // Below the floor: clamped up.
        assertEquals(RestBodySizeFilter.MIN_BODY_BYTES, RestBodySizeFilter.capFor(NORMAL, key -> "10"));
        // Malformed: falls back to the default.
        assertEquals(
                RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES, RestBodySizeFilter.capFor(NORMAL, key -> "not-a-number"));
    }

    // --------------------- declared Content-Length ---------------------

    @Test
    public void declaredOversizedBody_isRejected() {
        assertTrue(RestBodySizeFilter.rejectsContentLength("30000000", RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES));
        // Exactly at the cap is allowed; below too.
        assertFalse(RestBodySizeFilter.rejectsContentLength(
                Long.toString(RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES), RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES));
        assertFalse(RestBodySizeFilter.rejectsContentLength("1024", RestBodySizeFilter.DEFAULT_MAX_BODY_BYTES));
    }

    @Test
    public void missingOrMalformedContentLength_isNotRejectedByHeader() {
        assertFalse(RestBodySizeFilter.rejectsContentLength(null, 1024));
        assertFalse(RestBodySizeFilter.rejectsContentLength("", 1024));
        assertFalse(RestBodySizeFilter.rejectsContentLength("   ", 1024));
        assertFalse(RestBodySizeFilter.rejectsContentLength("not-a-number", 1024));
    }

    @Test
    public void bodyMethods_andNotOthers() {
        assertTrue(RestBodySizeFilter.isBodyMethod("POST"));
        assertTrue(RestBodySizeFilter.isBodyMethod("put"));
        assertTrue(RestBodySizeFilter.isBodyMethod("PATCH"));
        assertFalse(RestBodySizeFilter.isBodyMethod("GET"));
        assertFalse(RestBodySizeFilter.isBodyMethod("HEAD"));
        assertFalse(RestBodySizeFilter.isBodyMethod("DELETE"));
        assertFalse(RestBodySizeFilter.isBodyMethod(null));
    }

    // --------------------- full filter, mock objects ---------------------

    /** A chain that drains the request entity it is handed and records what it read. */
    private static final class CapturingChain implements jakarta.servlet.FilterChain {
        boolean invoked;
        byte[] read;
        RuntimeException seen;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s)
                throws IOException, ServletException {
            invoked = true;
            ServletInputStream in = ((HttpServletRequest) r).getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (RuntimeException e) {
                seen = e;
                throw e;
            }
            read = out.toByteArray();
        }
    }

    private static MockHttpServletRequest request(String method, String path, String contentLength, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setContextPath("");
        if (body != null) {
            req.setContent(body);
        }
        if (contentLength != null) {
            req.addHeader("Content-Length", contentLength);
        } else {
            req.removeHeader("Content-Length");
        }
        return req;
    }

    @Test
    public void oversizedDeclaredBody_answers413_withoutRunningTheChain() throws Exception {
        MockHttpServletRequest req = request("POST", NORMAL, Long.toString(30L * 1024 * 1024), null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new RestBodySizeFilter().doFilter(req, res, chain);

        assertEquals(413, res.getStatus());
        assertTrue(res.getContentAsString().contains("request too large"));
        assertFalse("the request never reaches the resource", chain.invoked);
    }

    @Test
    public void oversizedAnonymousLoginBody_answers413_atTheSessionCap() throws Exception {
        // 30 MB against the 16 KiB session cap — the issue's pre-auth case, via the route rule.
        MockHttpServletRequest req = request("POST", SESSION, Long.toString(30L * 1024 * 1024), null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new RestBodySizeFilter().doFilter(req, res, chain);

        assertEquals(413, res.getStatus());
        assertFalse(chain.invoked);
    }

    @Test
    public void smallBody_roundTripsThroughTheCountedStream() throws Exception {
        byte[] body = "username=admin&password=admin".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest req = request("POST", SESSION, Long.toString(body.length), body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new RestBodySizeFilter().doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertTrue(chain.invoked);
        assertEquals("username=admin&password=admin", new String(chain.read, StandardCharsets.UTF_8));
    }

    @Test
    public void chunkedOversizedBody_isCaughtByTheCountedStream() throws Exception {
        // No Content-Length (chunked); a 200 KB body against the 64 KiB default floor.
        System.setProperty(RestBodySizeFilter.KEY_MAX_BODY_BYTES, "65536");
        try {
            MockHttpServletRequest req = request("POST", NORMAL, null, new byte[200 * 1024]);
            MockHttpServletResponse res = new MockHttpServletResponse();
            CapturingChain chain = new CapturingChain();

            new RestBodySizeFilter().doFilter(req, res, chain);

            assertEquals("the counted stream turns the overrun into a 413", 413, res.getStatus());
            assertTrue(
                    "the reader saw the TooLargeException", chain.seen instanceof RestBodySizeFilter.TooLargeException);
        } finally {
            System.clearProperty(RestBodySizeFilter.KEY_MAX_BODY_BYTES);
        }
    }

    @Test
    public void getRequests_areNotCapped() throws Exception {
        MockHttpServletRequest req = request("GET", NORMAL, null, null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new RestBodySizeFilter().doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertTrue(chain.invoked);
    }

    @Test
    public void deletes_areNotCapped() throws Exception {
        MockHttpServletRequest req = request("DELETE", NORMAL, null, null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        new RestBodySizeFilter().doFilter(req, res, chain);

        assertEquals(200, res.getStatus());
        assertTrue(chain.invoked);
    }

    // ----------------------- counted stream -----------------------

    @Test
    public void countedStream_throwsBeyondCap() throws Exception {
        RestBodySizeFilter.CappedServletInputStream in = new RestBodySizeFilter.CappedServletInputStream(
                new ByteArrayInputStream(new byte[100]), 50, "POST", NORMAL);
        assertFalse(in.isFinished());
        assertTrue(in.isReady());
        try {
            in.read(new byte[60], 0, 60);
            throw new AssertionError("expected TooLargeException");
        } catch (RestBodySizeFilter.TooLargeException e) {
            // The read that exceeds the cap must throw, never fully buffer.
        }
    }

    @Test
    public void countedStream_atTheCapIsAllowedAndReachesEof() throws Exception {
        RestBodySizeFilter.CappedServletInputStream in = new RestBodySizeFilter.CappedServletInputStream(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 50, "POST", NORMAL);
        byte[] buf = new byte[16];
        int n = in.read(buf, 0, buf.length);
        assertEquals(5, n);
        assertEquals(-1, in.read(buf, 0, buf.length));
        assertTrue(in.isFinished());
        assertFalse(in.isReady());
    }

    @Test
    public void countedStream_rejectsReadListeners() {
        RestBodySizeFilter.CappedServletInputStream in = new RestBodySizeFilter.CappedServletInputStream(
                new ByteArrayInputStream(new byte[10]), 50, "POST", NORMAL);
        try {
            in.setReadListener((ReadListener) null);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // the REST surface reads entities synchronously
        }
    }

    @Test
    public void wrapperDelegatesInputStreamToTheCountedStream() throws Exception {
        MockHttpServletRequest req = request("POST", NORMAL, null, new byte[] {1, 2, 3});
        InputStream wrapped =
                new RestBodySizeFilter.CappedServletInputStream(req.getInputStream(), 1024, "POST", NORMAL);
        assertEquals(3, wrapped.read(new byte[8], 0, 8));
    }
}
