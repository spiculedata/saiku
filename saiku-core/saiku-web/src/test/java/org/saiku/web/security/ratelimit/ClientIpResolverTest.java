/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** saiku#2427: the limiter and the audit log resolve the client address through this one class. */
public class ClientIpResolverTest {

    @After
    public void clearProperties() {
        System.clearProperty("saiku.auth.trustedProxyHops");
        System.clearProperty("saiku.auth.trustForwardedFor");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("10.0.0.9");
        r.addHeader("X-Forwarded-For", "6.6.6.6");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        return r;
    }

    @Test
    public void default_isThePeerAndForwardedForIsIgnored() {
        assertEquals("10.0.0.9", ClientIpResolver.resolve(request()));
    }

    @Test
    public void trustedProxyHops_countsTheImmediateProxyOverAllHeaderLines() {
        System.setProperty("saiku.auth.trustedProxyHops", "1");

        assertEquals("1.2.3.4", ClientIpResolver.resolve(request()));
    }

    @Test
    public void legacyFlag_isAnAliasForOneHop() {
        System.setProperty("saiku.auth.trustForwardedFor", "true");

        assertEquals(1, ClientIpResolver.configuredTrustedProxyHops());
        assertEquals("1.2.3.4", ClientIpResolver.resolve(request()));
    }

    @Test
    public void invalidHops_areIgnored() {
        System.setProperty("saiku.auth.trustedProxyHops", "lots");

        assertEquals(0, ClientIpResolver.configuredTrustedProxyHops());
    }
}
