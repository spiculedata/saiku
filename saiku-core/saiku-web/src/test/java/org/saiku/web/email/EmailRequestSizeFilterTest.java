package org.saiku.web.email;

import static org.junit.Assert.*;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

/**
 * F2 — request-size ceiling on {@code POST /saiku/api/email/self}. Exercises the pure decision core
 * of {@link EmailRequestSizeFilter} (the same pattern {@code AiAuditFilter} uses to stay testable
 * without a JAX-RS container). {@link EmailRequestSizeFilter#decide} returning a non-null 413 is,
 * by the JAX-RS {@code abortWith} contract, the point at which the chain short-circuits — the
 * resource method never runs and the entity stream (Jackson parse) is never read.
 */
public class EmailRequestSizeFilterTest {

    private static final long CAP = 48L * 1024 * 1024;
    private static final String SELF = EmailRequestSizeFilter.SELF_PATH;

    private static Function<String, String> map(Map<String, String> m) {
        return m::get;
    }

    private static final Function<String, String> NONE = k -> null;

    // ---------- 413 on the guarded endpoint ----------

    @Test
    public void overCap_onSelfPost_returns413_genericJsonError() {
        Response r = EmailRequestSizeFilter.decide("POST", SELF, Long.toString(CAP + 1), CAP);
        assertNotNull("over-cap request must be aborted", r);
        assertEquals(413, r.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, r.getMediaType());
        assertEquals("request too large", ((Map<?, ?>) r.getEntity()).get("error"));
        // Generic error only — no internals (no size, no field names) leaked.
        assertEquals(1, ((Map<?, ?>) r.getEntity()).size());
    }

    @Test
    public void guardedPath_toleratesLeadingAndTrailingSlash() {
        assertNotNull(EmailRequestSizeFilter.decide("POST", "/" + SELF, Long.toString(CAP + 1), CAP));
        assertNotNull(EmailRequestSizeFilter.decide("POST", SELF + "/", Long.toString(CAP + 1), CAP));
    }

    @Test
    public void postMethod_isCaseInsensitive() {
        assertNotNull(EmailRequestSizeFilter.decide("post", SELF, Long.toString(CAP + 1), CAP));
    }

    // ---------- under the cap → proceed ----------

    @Test
    public void underCap_onSelfPost_proceeds() {
        assertNull(EmailRequestSizeFilter.decide("POST", SELF, Long.toString(CAP - 1), CAP));
    }

    @Test
    public void exactlyAtCap_proceeds() {
        assertNull(EmailRequestSizeFilter.decide("POST", SELF, Long.toString(CAP), CAP));
    }

    // ---------- chunked / missing / malformed Content-Length ----------

    @Test
    public void chunkedRequest_noContentLength_proceeds() {
        // Documented caveat: a chunked body has no Content-Length and cannot be pre-checked here.
        assertNull(EmailRequestSizeFilter.decide("POST", SELF, null, CAP));
        assertNull(EmailRequestSizeFilter.decide("POST", SELF, "   ", CAP));
    }

    @Test
    public void malformedContentLength_proceeds() {
        assertNull(EmailRequestSizeFilter.decide("POST", SELF, "not-a-number", CAP));
    }

    // ---------- scoping: other endpoints/methods are NOT limited ----------

    @Test
    public void differentEndpoint_withHugeContentLength_isNotRejected() {
        // A huge body to another endpoint (e.g. an export) must pass this filter untouched.
        assertNull(EmailRequestSizeFilter.decide("POST", "saiku/api/exporter/export", Long.toString(CAP * 10), CAP));
        assertNull(EmailRequestSizeFilter.decide("POST", "saiku/api/ai/query", Long.toString(CAP * 10), CAP));
    }

    @Test
    public void nonPostToSelfPath_isNotRejected() {
        assertNull(EmailRequestSizeFilter.decide("GET", SELF, Long.toString(CAP * 10), CAP));
        assertNull(EmailRequestSizeFilter.decide("GET", "saiku/api/email/health", Long.toString(CAP * 10), CAP));
    }

    @Test
    public void nullMethodOrPath_isNotRejected() {
        assertNull(EmailRequestSizeFilter.decide(null, SELF, Long.toString(CAP * 10), CAP));
        assertNull(EmailRequestSizeFilter.decide("POST", null, Long.toString(CAP * 10), CAP));
    }

    // ---------- config resolution + clamp ----------

    @Test
    public void defaultCap_whenUnset() {
        assertEquals(
                EmailRequestSizeFilter.DEFAULT_MAX_REQUEST_BYTES,
                EmailRequestSizeFilter.resolveMaxRequestBytes(NONE, NONE));
    }

    @Test
    public void systemPropertyOverridesDefault() {
        long custom = 20L * 1024 * 1024;
        Function<String, String> prop = map(Map.of(EmailRequestSizeFilter.PROP_KEY, Long.toString(custom)));
        assertEquals(custom, EmailRequestSizeFilter.resolveMaxRequestBytes(NONE, prop));
    }

    @Test
    public void envOverridesSystemProperty() {
        long envVal = 30L * 1024 * 1024;
        long propVal = 20L * 1024 * 1024;
        Function<String, String> env = map(Map.of(EmailRequestSizeFilter.ENV_KEY, Long.toString(envVal)));
        Function<String, String> prop = map(Map.of(EmailRequestSizeFilter.PROP_KEY, Long.toString(propVal)));
        assertEquals(envVal, EmailRequestSizeFilter.resolveMaxRequestBytes(env, prop));
    }

    @Test
    public void unparseableConfig_fallsBackToDefault() {
        Function<String, String> prop = map(Map.of(EmailRequestSizeFilter.PROP_KEY, "garbage"));
        assertEquals(
                EmailRequestSizeFilter.DEFAULT_MAX_REQUEST_BYTES,
                EmailRequestSizeFilter.resolveMaxRequestBytes(NONE, prop));
    }

    @Test
    public void tinyConfig_isClampedToFloor() {
        Function<String, String> prop = map(Map.of(EmailRequestSizeFilter.PROP_KEY, "1000"));
        assertEquals(
                EmailRequestSizeFilter.MIN_MAX_REQUEST_BYTES,
                EmailRequestSizeFilter.resolveMaxRequestBytes(NONE, prop));
    }

    @Test
    public void hugeConfig_isClampedToCeiling() {
        Function<String, String> prop = map(Map.of(EmailRequestSizeFilter.PROP_KEY, Long.toString(Long.MAX_VALUE)));
        assertEquals(
                EmailRequestSizeFilter.MAX_MAX_REQUEST_BYTES,
                EmailRequestSizeFilter.resolveMaxRequestBytes(NONE, prop));
    }

    @Test
    public void setter_clampsAndIsReflectedByDecide() {
        EmailRequestSizeFilter f = new EmailRequestSizeFilter();
        f.setMaxRequestBytes(10L * 1024 * 1024);
        assertEquals(10L * 1024 * 1024, f.getMaxRequestBytes());
        // A 12 MB body now exceeds the 10 MB cap.
        assertNotNull(
                EmailRequestSizeFilter.decide("POST", SELF, Long.toString(12L * 1024 * 1024), f.getMaxRequestBytes()));
        // Below-floor setter value is clamped up to the floor.
        f.setMaxRequestBytes(1);
        assertEquals(EmailRequestSizeFilter.MIN_MAX_REQUEST_BYTES, f.getMaxRequestBytes());
    }
}
