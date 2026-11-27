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

    /**
     * saiku#2529 (review of PR #2556): request attribute {@link org.saiku.web.rest.resources.embed.EmbedViewResource}
     * stamps before delegating an embed AI guest into the AI resources. Inside those resources the
     * caller looks like the token OWNER (the embed path runs under {@code runAs(owner)}), so without
     * this stamp every guest's call keyed and charged on the owner's identity — one anonymous viewer
     * of an AI embed could exhaust the owner's per-minute bucket and daily allowance. The stamped
     * value ("embed:token:…" / "embed:grant:…") is used FIRST for the limiter key and the budget
     * principal, so guests share the embed's own bucket and cannot touch the owner's.
     */
    public static final String EMBED_BUCKET_ATTRIBUTE = "saiku.embed.ai.bucket";

    private AiRequestPrincipal() {}

    /**
     * The embed AI bucket stamped on the current request, or {@code null} when the call is not an
     * embed AI guest call. See {@link #EMBED_BUCKET_ATTRIBUTE}.
     */
    public static String currentEmbedBucket() {
        jakarta.servlet.http.HttpServletRequest req = currentRequest();
        if (req == null) {
            return null;
        }
        Object v = req.getAttribute(EMBED_BUCKET_ATTRIBUTE);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

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
