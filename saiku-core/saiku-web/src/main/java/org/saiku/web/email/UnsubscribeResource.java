/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.saiku.service.mail.trust.SuppressionStore;
import org.saiku.service.mail.trust.UnsubscribeTokens;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PUBLIC one-click unsubscribe endpoint (saiku#1811, PR2). Mounted at
 * {@code /rest/saiku/mail/unsubscribe} and gated {@code permitAll} in Spring Security — an
 * unsubscribe link must be usable from a mail client without a Saiku login.
 *
 * <p><b>Fail-SAFE.</b> This endpoint can only ever ADD to the {@link SuppressionStore} (shrink the
 * mailable set); it can NEVER enable a send. The anti-relay boundary (a message's recipient is only
 * ever the server's own {@code selfTo}) is untouched — nothing here sends mail.
 *
 * <p><b>Anti-forgery.</b> The request must carry a valid HMAC {@code token} for the given
 * {@code address} ({@link UnsubscribeTokens}). A raw attacker-supplied address without a matching
 * token is NEVER suppressed, so an attacker can't unsubscribe an arbitrary third party.
 *
 * <p><b>No enumeration oracle.</b> The response is a GENERIC {@code 200} for every outcome — valid
 * token, invalid/tampered/expired token, malformed address, or already-suppressed. The address is
 * never echoed back and never logged (counts / booleans only), so the endpoint reveals nothing about
 * which addresses exist or were suppressed.
 *
 * <p><b>Rate-limited</b> per client IP via {@link AiRateLimiter} to blunt brute-force / abuse.
 */
@Path("/saiku/mail/unsubscribe")
public class UnsubscribeResource {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeResource.class);

    /** The single generic response body — identical for every outcome (no oracle). */
    private static final Map<String, String> GENERIC_OK =
            Map.of("status", "ok", "message", "If this address was subscribed, it has been unsubscribed.");

    private SuppressionStore suppressionStore;
    private UnsubscribeTokens unsubscribeTokens;

    @Context
    private HttpServletRequest request;

    /**
     * Per-IP rate limit for the public unsubscribe endpoint. Direct {@code new} + Spring/test setter,
     * mirroring {@link EmailResource#setEmailRateLimiter}. Keyed by client IP (no principal — this is
     * an unauthenticated endpoint).
     */
    private AiRateLimiter rateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.unsubscribe.ratelimit.maxPerMinute", 20), 60_000L);

    public void setSuppressionStore(SuppressionStore suppressionStore) {
        this.suppressionStore = suppressionStore;
    }

    public void setUnsubscribeTokens(UnsubscribeTokens unsubscribeTokens) {
        this.unsubscribeTokens = unsubscribeTokens;
    }

    /** Spring/test setter to override the per-IP rate limit. */
    public void setRateLimiter(AiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** Test setter for the request (production injects via {@code @Context}). */
    void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    /**
     * One-click unsubscribe (RFC 8058). Mail clients POST here with no body; the {@code address} and
     * {@code token} ride as query params on the {@code List-Unsubscribe} URL.
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response unsubscribePost(@QueryParam("address") String address, @QueryParam("token") String token) {
        return handle(address, token);
    }

    /** GET variant so a plain link in an email body also works (convenience). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response unsubscribeGet(@QueryParam("address") String address, @QueryParam("token") String token) {
        return handle(address, token);
    }

    // ---- core ----

    private Response handle(String address, String token) {
        if (!rateLimiter.tryAcquire(clientIp())) {
            // Even the throttle response stays generic-ish; 429 is a transport signal, not an oracle
            // about the address (we never looked at it before throttling).
            return Response.status(429)
                    .entity(Map.of("status", "rate_limited", "message", "Too many requests. Please retry shortly."))
                    .build();
        }
        boolean valid = unsubscribeTokens != null && unsubscribeTokens.verify(address, token);
        if (valid && suppressionStore != null) {
            // Idempotent add. Never log the address — record only whether a NEW suppression landed.
            boolean added = suppressionStore.suppress(address, "unsubscribe");
            log.info("Unsubscribe accepted (newSuppression={})", added);
        } else {
            // Invalid/tampered/expired token OR malformed address: do NOT suppress. No detail logged
            // and — critically — the SAME 200 body is returned below, so there's no enumeration oracle.
            log.debug("Unsubscribe rejected (invalid token)");
        }
        // GENERIC 200 for valid AND invalid alike — never echo the address, never reveal the outcome.
        return Response.ok(GENERIC_OK).build();
    }

    /** Client IP for the rate-limit key; {@code "unknown"} when no request is bound (tests). */
    private String clientIp() {
        try {
            String ip = request == null ? null : request.getRemoteAddr();
            return ip == null ? "unknown" : ip;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
