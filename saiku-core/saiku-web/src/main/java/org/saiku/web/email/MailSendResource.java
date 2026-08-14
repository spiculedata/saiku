/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.email;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.MailSenderFactory;
import org.saiku.service.mail.send.ConsentInviteService;
import org.saiku.service.mail.send.MailSendDisabledException;
import org.saiku.service.mail.send.MultiRecipientMailService;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Admin surface for the send gate (saiku#1811, PR4): the consent-invite action and the multi-recipient
 * send. <b>This is the first REST surface that can cause mail to a non-self address</b> — every control
 * below is load-bearing.
 *
 * <p><b>Master switch (default OFF) first.</b> Both endpoints refuse with {@code 403} unless the ops-only
 * {@link org.saiku.service.mail.send.MailSendPolicy} flag ({@code saiku.mail.sendToOthers.enabled}) is
 * on. The underlying services also fail closed independently ({@link MailSendDisabledException}) — the
 * resource never sends when the flag is off, and neither does the service.
 *
 * <p><b>Admin-only.</b> Class {@code @RolesAllowed("ROLE_ADMIN")} (JAX-RS gate) AND per-method {@code
 * userService.isAdmin()}, plus the Spring URL gate over {@code /rest/saiku/admin/**} — the same
 * belt-and-braces posture as {@link MailConfigResource}.
 *
 * <p><b>Gate, no override.</b> The multi-send passes the admin's recipient list through the fail-closed
 * {@link org.saiku.service.mail.trust.RecipientGate}; only cleared (not suppressed ∧ allowlisted ∧
 * consent CONFIRMED) recipients are mailed, individually addressed, each with its own {@code
 * List-Unsubscribe}. Dropped recipients are reported only as a COUNT — never an address. The invite is
 * allowlist-gated + never-to-suppressed + consent-exempt (the bootstrap).
 *
 * <p><b>Rate-limited</b> per admin principal: a low invite cap and a low multi-send cap, each its own
 * {@link AiRateLimiter} (mirroring the other mail endpoints). Responses never echo recipient addresses.
 */
@Path("/saiku/admin/mail-send")
@RolesAllowed("ROLE_ADMIN")
public class MailSendResource {

    private static final Logger log = LoggerFactory.getLogger(MailSendResource.class);

    private MultiRecipientMailService multiRecipientMailService;
    private ConsentInviteService consentInviteService;
    private MailConfigResolver mailConfigResolver;
    private UserService userService;
    private final EmailMessageAssembler assembler = new EmailMessageAssembler();

    /** Per-admin cap on invite sends (each reaches a real SMTP transport). Default 5/min. */
    private AiRateLimiter inviteRateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.invite.ratelimit.maxPerMinute", 5), 60_000L);

    /** Per-admin cap on multi-recipient send BATCHES. Default 3/min. */
    private AiRateLimiter sendRateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.send.ratelimit.maxPerMinute", 3), 60_000L);

    /** Test seam over the sender build (production builds the real sender from the effective config). */
    private java.util.function.Function<MailConfig, MailSender> senderFactory = MailSenderFactory::create;

    public void setMultiRecipientMailService(MultiRecipientMailService multiRecipientMailService) {
        this.multiRecipientMailService = multiRecipientMailService;
    }

    public void setConsentInviteService(ConsentInviteService consentInviteService) {
        this.consentInviteService = consentInviteService;
    }

    public void setMailConfigResolver(MailConfigResolver mailConfigResolver) {
        this.mailConfigResolver = mailConfigResolver;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public void setInviteRateLimiter(AiRateLimiter inviteRateLimiter) {
        this.inviteRateLimiter = inviteRateLimiter;
    }

    public void setSendRateLimiter(AiRateLimiter sendRateLimiter) {
        this.sendRateLimiter = sendRateLimiter;
    }

    void setSenderFactory(java.util.function.Function<MailConfig, MailSender> senderFactory) {
        this.senderFactory = senderFactory;
    }

    /** Whether non-self send is enabled — lets the UI show the gate state without attempting a send. */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        Authentication auth = requireAdminAuth();
        if (auth == null) {
            return notAuthed();
        }
        if (!isAdmin()) {
            return forbidden();
        }
        boolean enabled = multiRecipientMailService != null && multiRecipientMailService.isEnabled();
        return Response.ok(Map.of("sendToOthersEnabled", enabled)).build();
    }

    /**
     * Invite a recipient: mint consent + mail the allowlisted address a confirm link. Consent-exempt
     * bootstrap send; allowlist-gated; never to a suppressed address; server-constant content.
     */
    @POST
    @Path("/invite")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response invite(MailInviteRequest body) {
        Authentication auth = requireAdminAuth();
        if (auth == null) {
            return notAuthed();
        }
        if (!isAdmin()) {
            return forbidden();
        }
        // Master switch first — 403 with nothing attempted when non-self send is off.
        if (consentInviteService == null || !consentInviteService.isEnabled()) {
            return sendDisabled();
        }
        if (!inviteRateLimiter.tryAcquire(auth.getName())) {
            return rateLimited(inviteRateLimiter);
        }
        if (body == null || body.getAddress() == null || body.getAddress().isBlank()) {
            return badRequest("address is required");
        }

        MailConfig cfg = mailConfigResolver == null ? null : mailConfigResolver.effective();
        if (cfg == null || !cfg.isConfigured()) {
            return notConfigured();
        }
        MailSender sender = senderFactory.apply(cfg);
        if (sender == null || !sender.isConfigured()) {
            return notConfigured();
        }

        try {
            ConsentInviteService.Outcome outcome = consentInviteService.invite(sender, cfg.from(), body.getAddress());
            switch (outcome) {
                case SENT:
                    log.info("Admin {} sent a consent invite", currentUser());
                    return Response.ok(Map.of("status", "invited")).build();
                case DISABLED:
                    return sendDisabled();
                case NOT_ALLOWLISTED:
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity(Map.of("error", "address is not on the recipient allowlist"))
                            .build();
                case SUPPRESSED:
                    return Response.status(Response.Status.FORBIDDEN)
                            .entity(Map.of("error", "address has unsubscribed and cannot be invited"))
                            .build();
                case INVALID_ADDRESS:
                    return badRequest("invalid email address");
                case LINK_UNCONFIGURED:
                    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .entity(Map.of("error", "public base URL (SAIKU_PUBLIC_BASE_URL) is not configured"))
                            .build();
                case SEND_FAILED:
                default:
                    return Response.status(Response.Status.BAD_GATEWAY)
                            .entity(Map.of("error", "send failed"))
                            .build();
            }
        } catch (MailSendDisabledException e) {
            return sendDisabled();
        } catch (MailException e) {
            log.warn("Consent invite failed for admin {}: {}", currentUser(), e.getMessage());
            return notConfigured();
        }
    }

    /**
     * Multi-recipient send: deliver the composed message to the cleared subset of {@code recipients},
     * one individually-addressed message per cleared recipient, each with its own {@code
     * List-Unsubscribe}. Per-recipient partial success; dropped recipients reported as a count only.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response send(MailSendRequest body) {
        Authentication auth = requireAdminAuth();
        if (auth == null) {
            return notAuthed();
        }
        if (!isAdmin()) {
            return forbidden();
        }
        if (multiRecipientMailService == null || !multiRecipientMailService.isEnabled()) {
            return sendDisabled();
        }
        if (!sendRateLimiter.tryAcquire(auth.getName())) {
            return rateLimited(sendRateLimiter);
        }
        if (body == null || body.getRecipients() == null || body.getRecipients().isEmpty()) {
            return badRequest("recipients are required");
        }
        if (body.getMessage() == null) {
            return badRequest("message is required");
        }

        MailConfig cfg = mailConfigResolver == null ? null : mailConfigResolver.effective();
        if (cfg == null || !cfg.isConfigured()) {
            return notConfigured();
        }
        MailSender sender = senderFactory.apply(cfg);
        if (sender == null || !sender.isConfigured()) {
            return notConfigured();
        }

        EmailMessageAssembler.Content content;
        try {
            content = assembler.assembleContent(body.getMessage());
        } catch (EmailRequestException e) {
            return badRequest(e.getMessage());
        }
        MultiRecipientMailService.MailContent mc = new MultiRecipientMailService.MailContent(
                content.subject(), content.htmlBody(), content.inlineImages(), content.attachments());

        try {
            MultiRecipientMailService.Result r =
                    multiRecipientMailService.send(sender, cfg.from(), body.getRecipients(), mc);
            // Counts only — never an address, never the recipient list.
            log.info(
                    "Admin {} ran a multi-recipient send (requested={} cleared={} sent={} failed={} dropped={})",
                    currentUser(),
                    r.requested(),
                    r.cleared(),
                    r.sent(),
                    r.failed(),
                    r.dropped());
            return Response.ok(Map.of(
                            "status", "done",
                            "requested", r.requested(),
                            "cleared", r.cleared(),
                            "sent", r.sent(),
                            "failed", r.failed(),
                            "dropped", r.dropped()))
                    .build();
        } catch (MailSendDisabledException e) {
            return sendDisabled();
        } catch (MailException e) {
            log.warn("Multi-recipient send failed for admin {}: {}", currentUser(), e.getMessage());
            return notConfigured();
        }
    }

    // ---- helpers ----

    private Authentication requireAdminAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth;
    }

    private boolean isAdmin() {
        return userService != null && userService.isAdmin();
    }

    private String currentUser() {
        try {
            return userService == null ? "?" : userService.getActiveUsername();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static Response notAuthed() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "authentication required"))
                .build();
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "admin only"))
                .build();
    }

    private static Response sendDisabled() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "non-self send is disabled (saiku.mail.sendToOthers.enabled is off)"))
                .build();
    }

    private static Response notConfigured() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", "email not configured"))
                .build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg))
                .build();
    }

    private static Response rateLimited(AiRateLimiter limiter) {
        return Response.status(429)
                .entity(Map.of(
                        "error",
                        "Too many requests — limit is " + limiter.getMaxCalls() + " per "
                                + (limiter.getWindowMs() / 1000) + "s. Please retry shortly."))
                .build();
    }
}
