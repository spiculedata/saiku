/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Emails the authenticated user's current analysis (browser-rendered) to an address they supply. */
@Path("/saiku/api/email")
public class EmailResource {

    private static final Logger log = LoggerFactory.getLogger(EmailResource.class);

    private MailSender mailSender;
    private MailConfig mailConfig;
    private final EmailMessageAssembler assembler = new EmailMessageAssembler();

    /**
     * Per-user send rate limit for the self-send endpoint (SEC-harden slice,
     * task 2/2). Mirrors {@code AiQueryResource#askRateLimiter}: a direct
     * {@code new} field + Spring/test setter, keyed by the authenticated
     * principal so one user's abuse can't exhaust another's budget.
     */
    private org.saiku.web.security.ratelimit.AiRateLimiter emailRateLimiter =
            new org.saiku.web.security.ratelimit.AiRateLimiter(
                    Integer.getInteger("saiku.email.ratelimit.maxPerMinute", 10), 60_000L);

    public void setMailSender(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void setMailConfig(MailConfig mailConfig) {
        this.mailConfig = mailConfig;
    }

    /** Spring/test setter to override the per-user send rate limit. */
    public void setEmailRateLimiter(org.saiku.web.security.ratelimit.AiRateLimiter emailRateLimiter) {
        this.emailRateLimiter = emailRateLimiter;
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        boolean configured = mailSender != null && mailSender.isConfigured();
        boolean selfConfigured = mailConfig != null && mailConfig.selfSendConfigured();
        // Single-admin embedded build: the self-send recipient is the admin's own
        // configured address, so exposing it to an authenticated user is acceptable
        // and lets the composer show "Sends to: <address>". null when unconfigured.
        String to = selfConfigured ? mailConfig.selfTo() : null;
        Map<String, Object> health = new java.util.HashMap<>();
        health.put("configured", configured);
        health.put("selfConfigured", selfConfigured);
        health.put("to", to);
        return Response.ok(health).build();
    }

    @POST
    @Path("/self")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendSelf(EmailSelfRequest body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "authentication required"))
                    .build();
        }
        if (!emailRateLimiter.tryAcquire(auth.getName())) {
            return Response.status(429)
                    .entity(java.util.Map.of(
                            "error",
                            "Too many emails — limit is " + emailRateLimiter.getMaxCalls() + " per "
                                    + (emailRateLimiter.getWindowMs() / 1000) + "s. Please retry shortly."))
                    .build();
        }
        if (mailSender == null || !mailSender.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "email not configured"))
                    .build();
        }
        // Fail closed: the recipient comes ONLY from the admin-configured server value.
        // No client-supplied value ever reaches the recipient (open-relay fix, F1).
        if (mailConfig == null || !mailConfig.selfSendConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "email recipient not configured"))
                    .build();
        }
        // #1811 PR4: the multi-recipient send layer will live in a SEPARATE send path (not this
        // self-send path). There, the admin-supplied recipient list is passed through
        // recipientGate.clear(addresses) BEFORE any send, and ONLY the survivors are mailed — the gate
        // consults the SuppressionStore FIRST (top-veto), then the recipient-trust allowlist, then
        // CONFIRMED double-opt-in consent. This self-send path stays selfTo-ONLY and is intentionally
        // NOT gated (the recipient is the server's own configured address, never a third party).
        try {
            MailMessage msg = assembler.assemble(body, mailConfig.from(), mailConfig.selfTo());
            mailSender.send(msg);
            log.info("Emailed analysis for user {} to {}", auth.getName(), msg.to());
            return Response.ok(Map.of("status", "sent", "to", msg.to())).build();
        } catch (EmailRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (MailException e) {
            log.warn("Email send failed for user {}: {}", auth.getName(), e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "send failed"))
                    .build();
        }
    }
}
