/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** saiku#2339: the chain-level 429 is keyed on the Basic username, not the IP alone. */
public class LoginRateLimitFilterTest {

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void basicUsername_parsesTheUser() {
        assertEquals("alice", LoginRateLimitFilter.basicUsername(basic("alice", "p:w")));
        assertNull(LoginRateLimitFilter.basicUsername("Bearer abc"));
        assertNull(LoginRateLimitFilter.basicUsername("Basic !!!not-base64"));
        assertNull(LoginRateLimitFilter.basicUsername(null));
    }

    private static String encoded(String user, String password) {
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void basicUsername_acceptsEverySpellingSpringsConverterAccepts() {
        String token = encoded("alice", "pw");
        assertEquals("alice", LoginRateLimitFilter.basicUsername("basic " + token));
        assertEquals("alice", LoginRateLimitFilter.basicUsername("BASIC " + token));
        assertEquals("alice", LoginRateLimitFilter.basicUsername("  Basic " + token + "  "));
        assertEquals("alice", LoginRateLimitFilter.basicUsername("Basic\t" + token));
        assertEquals("alice", LoginRateLimitFilter.basicUsername("Basicx" + token));
    }

    @Test
    public void basicUsername_noUserPartOrBareScheme_isNullButStillBasic() {
        assertNull(LoginRateLimitFilter.basicUsername("Basic " + encoded("", "pw")));
        assertNull(LoginRateLimitFilter.basicUsername("Basic"));
        assertTrue(LoginRateLimitFilter.isBasic("Basic " + encoded("", "pw")));
        assertTrue(LoginRateLimitFilter.isBasic("basic"));
        assertFalse(LoginRateLimitFilter.isBasic("Bearer abc"));
    }

    @Test
    public void spellingVariants_areBlockedLikeTheNormalHeader() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 60_000L, false);
        LoginRateLimitFilter filter = new LoginRateLimitFilter(limiter);
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr("1.1.1.1");
        limiter.recordFailure(probe, "nobody");
        limiter.recordFailure(probe, "nobody");
        String token = encoded("nobody", "x");

        assertEquals(429, runHeader(filter, "Basic " + token));
        assertEquals(429, runHeader(filter, "basic " + token));
        assertEquals(429, runHeader(filter, " Basic " + token));
        assertEquals(429, runHeader(filter, "Basic\t" + token));
        assertEquals(200, runHeader(filter, "Basic " + encoded("alice", "x")));
    }

    @Test
    public void basicWithoutAUsername_getsTheStrictPerAddressRule() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 60_000L, false);
        LoginRateLimitFilter filter = new LoginRateLimitFilter(limiter);
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr("1.1.1.1");
        limiter.recordFailure(probe, null);
        limiter.recordFailure(probe, null);

        assertEquals(429, runHeader(filter, "Basic " + encoded("", "x")));
        assertEquals(200, runHeader(filter, "Bearer token"));
    }

    @Test
    public void lockedOutUser_doesNotBlockAnotherUserFromTheSameIp() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 60_000L, false);
        LoginRateLimitFilter filter = new LoginRateLimitFilter(limiter);
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr("1.1.1.1");
        limiter.recordFailure(probe, "nobody");
        limiter.recordFailure(probe, "nobody");

        assertEquals(429, run(filter, "nobody"));
        assertEquals(200, run(filter, "alice"));
    }

    private static int run(LoginRateLimitFilter filter, String user) throws Exception {
        return runHeader(filter, basic(user, "x"));
    }

    private static int runHeader(LoginRateLimitFilter filter, String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("1.1.1.1");
        req.addHeader("Authorization", authorization);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp.getStatus();
    }
}
