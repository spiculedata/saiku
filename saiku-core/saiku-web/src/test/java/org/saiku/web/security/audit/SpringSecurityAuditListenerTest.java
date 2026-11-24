/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.audit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.web.rest.resources.SessionResource;
import org.saiku.web.security.ratelimit.LoginRateLimiter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * saiku#2523 — {@link SpringSecurityAuditListener} must record a credential check
 * exactly once. The listener is the recorder for the HTTP-Basic / filter-chain
 * path; {@link SessionResource#login} records its own outcome directly, and marks
 * its request with {@link SessionResource#LOGIN_RECORDED_ATTRIBUTE} before the
 * authentication manager publishes the same failure event. The mark must make the
 * listener skip — otherwise every failed form login was counted twice and the
 * lockout tripped after three wrong passwords instead of the documented five.
 */
public class SpringSecurityAuditListenerTest {

    private static final String USER = "admin";
    private static final String IP = "203.0.113.5";

    /** Budget of 1: a single recorded failure is enough to lock the (ip, user) bucket. */
    private LoginRateLimiter limiter;

    private SpringSecurityAuditListener listener;
    private MockHttpServletRequest request;

    @Before
    public void setUp() {
        limiter = new LoginRateLimiter(1, 60_000L, false);
        listener = new SpringSecurityAuditListener();
        listener.setRateLimiter(limiter);
        request = new MockHttpServletRequest();
        request.setRemoteAddr(IP);
        request.setMethod("POST");
        request.setRequestURI("/rest/saiku/session");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void unmarkedFailure_isRecorded_once() {
        publishFailure();
        assertTrue(
                "an unmarked credential check is the listener's own path (HTTP Basic); its failure must lock the budget",
                limiter.isBlocked(request, USER));
    }

    @Test
    public void sessionResourceMarkedFailure_isNotCounted() {
        request.setAttribute(SessionResource.LOGIN_RECORDED_ATTRIBUTE, Boolean.TRUE);
        publishFailure();
        assertFalse(
                "SessionResource records its own outcome; the listener must not count the same failure again",
                limiter.isBlocked(request, USER));
    }

    @Test
    public void unmarkedSuccess_clearsTheBucket() {
        limiter.recordFailure(request, USER);
        assertTrue(limiter.isBlocked(request, USER));
        publishSuccess();
        assertFalse(
                "a success the listener owns clears the (ip, user) bucket so a recovered user is not kept locked out",
                limiter.isBlocked(request, USER));
    }

    @Test
    public void sessionResourceMarkedSuccess_isNotRecorded() {
        limiter.recordFailure(request, USER);
        assertTrue(limiter.isBlocked(request, USER));
        request.setAttribute(SessionResource.LOGIN_RECORDED_ATTRIBUTE, Boolean.TRUE);
        publishSuccess();
        assertTrue(
                "SessionResource records its own success (it owns the request); the listener must not touch the budget",
                limiter.isBlocked(request, USER));
    }

    @Test
    public void failureWithoutRequestAttributes_isTolerated() {
        RequestContextHolder.resetRequestAttributes();
        publishFailure();
        // No request to attribute the failure to: the limiter no-ops, and the event must not throw.
        assertFalse(limiter.isBlocked(request, USER));
    }

    @Test
    public void unhandledEvent_isIgnored() {
        limiter.recordFailure(request, USER);
        listener.onApplicationEvent(new org.springframework.context.ApplicationEvent("not-auth") {});
        assertTrue("unrelated application events must not touch the budget", limiter.isBlocked(request, USER));
    }

    private void publishFailure() {
        Authentication auth = new UsernamePasswordAuthenticationToken(USER, "wrong");
        listener.onApplicationEvent(
                new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("nope")));
    }

    private void publishSuccess() {
        listener.onApplicationEvent(
                new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken(USER, "ok")));
    }
}
