/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.security.audit;

import jakarta.servlet.http.HttpServletRequest;
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
 * audit log. Covers the HTTP-Basic / filter-chain auth path that doesn't go
 * through {@code SessionResource.login} (which audits directly).
 *
 * <p>We listen for the two shaped events — success + any failure subclass —
 * and skip the anonymous-authentication noise.
 */
public class SpringSecurityAuditListener
        implements ApplicationListener<org.springframework.context.ApplicationEvent> {

    @Override
    public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
        if (event instanceof AuthenticationSuccessEvent success) {
            Authentication auth = success.getAuthentication();
            if (auth == null || auth.getPrincipal() == null) return;
            String principal = auth.getName();
            if ("anonymousUser".equals(principal)) return;
            AuditLogger.loginSuccess(currentRequest(auth), principal);
        } else if (event instanceof AbstractAuthenticationFailureEvent failure) {
            Authentication auth = failure.getAuthentication();
            String principal = auth != null ? auth.getName() : null;
            String reason = failure.getException() != null
                    ? failure.getException().getClass().getSimpleName()
                    : "unknown";
            AuditLogger.loginFailure(currentRequest(auth), principal, reason);
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
