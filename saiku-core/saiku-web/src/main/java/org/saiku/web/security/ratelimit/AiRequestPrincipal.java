/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security.ratelimit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Identity resolution for the AI per-minute rate limiter and the daily cost budget
 * (saiku#2529).
 *
 * <p>The caller's name is taken from {@link SecurityContextHolder} FIRST. The limiter and budget
 * used to derive it from {@code RequestContextHolder}'s request via {@code getRemoteUser()} — but
 * that request can be the unwrapped container request, which carries no Spring Security principal,
 * so {@code getRemoteUser()} returned null, the limiter key fell back to the client IP (every
 * authenticated user behind one NAT egress shared one bucket) and the budget saw a null principal,
 * which it treats as fail-open. The Spring context is bound to the same request thread and always
 * carries the authenticated principal after the security filter chain has run.
 *
 * <p>The request-derived user remains as a fallback so a deployment that authenticates outside
 * Spring Security (container-managed auth populates {@code getRemoteUser()}) still keys correctly.
 * Spring's anonymous token ("anonymousUser") is NOT an identity: it returns {@code null}, so the
 * limiter keys on the client IP and the budget stays fail-open for genuinely unauthenticated
 * traffic — the same contract as before, minus the authenticated callers who used to be misread
 * as anonymous.
 */
public final class AiRequestPrincipal {

    private AiRequestPrincipal() {}

    /**
     * The authenticated caller's name, or {@code null} when the request carries no real identity.
     * Spring Security context first, container request second, {@code null} last.
     */
    public static String currentName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && isReal(auth.getName())) {
            return auth.getName();
        }
        jakarta.servlet.http.HttpServletRequest req = currentRequest();
        if (req != null) {
            String user = req.getRemoteUser();
            if (!isReal(user) && req.getUserPrincipal() != null) {
                user = req.getUserPrincipal().getName();
            }
            if (isReal(user)) {
                return user;
            }
        }
        return null;
    }

    /** "Real" identity: present, non-blank, and not Spring's anonymous placeholder. */
    private static boolean isReal(String name) {
        return name != null && !name.isBlank() && !"anonymousUser".equals(name);
    }

    /** The request bound to the current thread via Spring, or {@code null} (unit tests, async). */
    private static jakarta.servlet.http.HttpServletRequest currentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
