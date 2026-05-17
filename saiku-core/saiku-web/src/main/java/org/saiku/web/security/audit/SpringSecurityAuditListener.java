/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.saiku.web.security.ratelimit.LoginRateLimiter;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bridges Spring Security authentication events onto the Saiku structured
 * audit log AND the {@link LoginRateLimiter}. Covers the HTTP-Basic /
 * filter-chain auth path that doesn't go through {@code SessionResource.login}
 * (which audits + rate-limits directly).
 *
 * <p>Listens for both event subclasses — success + any failure — and skips
 * the anonymous-authentication noise. Success clears the limiter so a
 * recovered user isn't kept locked out; failure increments it.
 *
 * <p>The limiter is optional: if no bean is wired, only audit logging fires.
 * Issue #878 wires it for both Basic and form-login paths.
 */
public class SpringSecurityAuditListener implements ApplicationListener<org.springframework.context.ApplicationEvent> {

    private LoginRateLimiter rateLimiter;

    public void setRateLimiter(LoginRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
        if (event instanceof AuthenticationSuccessEvent success) {
            Authentication auth = success.getAuthentication();
            if (auth == null || auth.getPrincipal() == null) return;
            String principal = auth.getName();
            if ("anonymousUser".equals(principal)) return;
            HttpServletRequest req = currentRequest(auth);
            AuditLogger.loginSuccess(req, principal);
            if (rateLimiter != null && req != null) rateLimiter.recordSuccess(req);
        } else if (event instanceof AbstractAuthenticationFailureEvent failure) {
            Authentication auth = failure.getAuthentication();
            String principal = auth != null ? auth.getName() : null;
            String reason = failure.getException() != null
                    ? failure.getException().getClass().getSimpleName()
                    : "unknown";
            HttpServletRequest req = currentRequest(auth);
            AuditLogger.loginFailure(req, principal, reason);
            if (rateLimiter != null && req != null) rateLimiter.recordFailure(req);
        }
    }

    /**
     * Recover the HttpServletRequest for the event. Prefer
     * {@link WebAuthenticationDetails#getRemoteAddress()} via the spring web
     * RequestContextHolder; falls back to null which AuditLogger tolerates.
     */
    private static HttpServletRequest currentRequest(Authentication auth) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
