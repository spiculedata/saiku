package org.saiku.web.email;

import jakarta.annotation.security.RolesAllowed;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.saiku.service.mail.MailConfig;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.mail.MailConfigView;
import org.saiku.service.mail.MailException;
import org.saiku.service.mail.MailMessage;
import org.saiku.service.mail.MailSender;
import org.saiku.service.mail.MailSenderFactory;
import org.saiku.service.user.UserService;
import org.saiku.web.security.ratelimit.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Admin mail-setup wizard endpoint (saiku#943 Phase 0, P0-B). Lets an admin configure SMTP in-app,
 * writing the encrypted, file-backed {@link MailConfigStore} layer — so email works without env-var
 * wrangling and the otherwise-dead Email button becomes a "set it up" path.
 *
 * <p><b>Security (SEC gates this track).</b>
 *
 * <ul>
 *   <li>Admin-only: class-level {@code @RolesAllowed("ROLE_ADMIN")} (the JAX-RS gate) AND an explicit
 *       {@code userService.isAdmin()} check per method — belt and braces, matching {@link
 *       org.saiku.web.rest.resources.AdminResource}. There is also a Spring URL gate over
 *       {@code /rest/saiku/admin/**} (saiku#1165).
 *   <li>Never returns the password: both GET and POST return a {@link MailConfigView} (password
 *       omitted, {@code passwordSet} flag only) — NEVER the {@code MailConfigFile}, which would
 *       serialise the ciphertext.
 *   <li>Env-wins: when env/system-property config is present the deployment is "managed by ops" — a
 *       save is refused (409) so a UI write can't shadow a deploy, and the view flags it read-only.
 *   <li>Inputs are CRLF-stripped (header-injection defence) and addresses RFC-validated, reusing the
 *       same discipline as {@link EmailMessageAssembler}.
 * </ul>
 */
@Path("/saiku/admin/mail-config")
@RolesAllowed("ROLE_ADMIN")
public class MailConfigResource {

    private static final Logger log = LoggerFactory.getLogger(MailConfigResource.class);

    private MailConfigStore mailConfigStore;
    private MailConfigResolver mailConfigResolver;
    private UserService userService;

    /**
     * Per-admin rate limit for the test-send endpoint (saiku#943, P0-C). A test send reaches an
     * external SMTP transport, so an unbounded frequency is a probe/abuse vector even behind the
     * admin gate — cap it low (default 5/min). Mirrors {@code EmailResource#emailRateLimiter}: a
     * direct {@code new} field + setter, keyed by the authenticated principal.
     */
    private AiRateLimiter testSendRateLimiter =
            new AiRateLimiter(Integer.getInteger("saiku.mail.test.ratelimit.maxPerMinute", 5), 60_000L);

    /**
     * Seam over {@link MailSenderFactory#create(MailConfig)} so tests inject a capturing mock instead
     * of opening a real socket. Production default builds the real sender from the effective config.
     */
    private java.util.function.Function<MailConfig, MailSender> senderFactory = MailSenderFactory::create;

    public void setMailConfigStore(MailConfigStore mailConfigStore) {
        this.mailConfigStore = mailConfigStore;
    }

    public void setMailConfigResolver(MailConfigResolver mailConfigResolver) {
        this.mailConfigResolver = mailConfigResolver;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** Spring/test setter to override the per-admin test-send rate limit. */
    public void setTestSendRateLimiter(AiRateLimiter testSendRateLimiter) {
        this.testSendRateLimiter = testSendRateLimiter;
    }

    /** Test seam: override how a {@link MailSender} is built from the effective config. */
    void setSenderFactory(java.util.function.Function<MailConfig, MailSender> senderFactory) {
        this.senderFactory = senderFactory;
    }

    /** Current effective settings for the wizard — password omitted; {@code managedByOps} drives read-only. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        if (!isAdmin()) {
            return forbidden();
        }
        // MailConfigView never carries the password — safe to return as-is.
        return Response.ok(mailConfigResolver.view()).build();
    }

    /** Persist the wizard's settings to the encrypted file layer. Refused when ops manages mail config. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(MailConfigRequest body) {
        if (!isAdmin()) {
            return forbidden();
        }
        if (body == null) {
            return badRequest("request body is required");
        }
        // Env-wins: never let an in-app save shadow an ops-managed (env/prop) deployment.
        if (mailConfigResolver.managedByOps()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                            "error",
                            "email is configured by the environment (managed by ops) — the in-app wizard is read-only"))
                    .build();
        }

        String host = stripCrlf(body.getHost());
        String username = stripCrlf(body.getUsername());
        String from = validatedAddressOrNull(body.getFrom(), "from");
        String selfTo = validatedAddressOrNull(body.getSelfTo(), "selfTo");
        if (from == INVALID || selfTo == INVALID) {
            return badRequest("invalid email address");
        }

        MailConfigView view = mailConfigStore.save(
                host,
                body.getPort(),
                username,
                body.getPassword(), // plaintext in; encrypted at rest by the store; never returned
                from,
                body.isStartTls(),
                body.isSsl(),
                selfTo);
        log.info("Admin {} updated mail configuration (host set={})", currentUser(), host != null);
        // Returns the redacted view — never the password.
        return Response.ok(view).build();
    }

    /**
     * Send a FIXED test email to the admin's own configured address so they can verify SMTP works
     * (saiku#943, P0-C).
     *
     * <p><b>Anti-relay boundary (SEC gates this).</b> There is deliberately <b>no request body and no
     * recipient parameter</b>: the recipient is ALWAYS {@code effective().selfTo()} — the admin's own
     * server-owned address — so this endpoint can never be coerced into relaying mail to a
     * client-supplied target or used as an SMTP probe. The message subject and body are compile-time
     * constants, so there is no client-controlled input to inject a header with.
     *
     * <ul>
     *   <li>Anonymous caller -> 401; authenticated non-admin -> 403 (nothing sent).
     *   <li>Rate-limited per principal (default 5/min) -> 429.
     *   <li>Fail-closed: not configured, or no {@code selfTo}, -> 503.
     *   <li>Sanitized outcome: transport failures return {@code 502 {"error":"send failed"}} — the
     *       SMTP host / exception detail is logged server-side only, never returned. The {@link
     *       MailConfig} instance is never logged (only outcome booleans).
     * </ul>
     */
    @POST
    @Path("/test")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendTest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "authentication required"))
                    .build();
        }
        if (!isAdmin()) {
            return forbidden();
        }
        if (!testSendRateLimiter.tryAcquire(auth.getName())) {
            return Response.status(429)
                    .entity(Map.of(
                            "error",
                            "Too many test sends — limit is " + testSendRateLimiter.getMaxCalls() + " per "
                                    + (testSendRateLimiter.getWindowMs() / 1000) + "s. Please retry shortly."))
                    .build();
        }

        // Env-wins effective config — the same config the sender uses in production.
        MailConfig cfg = mailConfigResolver.effective();
        // Fail-closed: SMTP not configured (no host/from) -> 503.
        if (cfg == null || !cfg.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "email not configured"))
                    .build();
        }
        // Fail-closed: the recipient comes ONLY from the admin-configured selfTo. No selfTo -> refuse.
        if (!cfg.selfSendConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "test recipient (selfTo) not configured"))
                    .build();
        }

        MailSender sender = senderFactory.apply(cfg);
        if (sender == null || !sender.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "email not configured"))
                    .build();
        }

        // FIXED message. Recipient = cfg.selfTo() ONLY; subject/body are constants (no client input).
        MailMessage msg = new MailMessage(cfg.selfTo(), cfg.from(), TEST_SUBJECT, TEST_HTML_BODY, List.of(), List.of());
        try {
            sender.send(msg);
            // Never log the MailConfig instance — outcome booleans only.
            log.info("Admin {} sent an SMTP test email (delivered=true)", currentUser());
            // selfTo is the admin's own server-owned address, safe to echo for UX; never client-supplied.
            return Response.ok(Map.of("status", "sent", "to", cfg.selfTo())).build();
        } catch (MailException e) {
            // Sanitized: never leak host / exception detail to the client. Log server-side only,
            // and log booleans/outcome — not the MailConfig, not the recipient beyond a boolean.
            log.warn(
                    "SMTP test send failed for admin {} (configured=true, selfToSet=true): {}",
                    currentUser(),
                    e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "send failed"))
                    .build();
        }
    }

    // ---- helpers ----

    /** Fixed test-email subject (no client input — closes the header-injection surface). */
    private static final String TEST_SUBJECT = "Saiku SMTP test email";

    /** Fixed test-email body. */
    private static final String TEST_HTML_BODY =
            "<p>This is a test email from Saiku confirming your SMTP settings are working.</p>"
                    + "<p>If you received this, outgoing email is configured correctly.</p>";

    /** Sentinel distinguishing "validation failed" from "no value supplied" (null). */
    private static final String INVALID = new String("__INVALID__");

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

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "admin only"))
                .build();
    }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg))
                .build();
    }

    /** Strip CR/LF so a config value can't smuggle SMTP/log header injection (mirrors EmailMessageAssembler). */
    private static String stripCrlf(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.isEmpty() ? null : t;
    }

    /** null when blank; a validated address when valid; the INVALID sentinel when present-but-malformed. */
    private static String validatedAddressOrNull(String address, String field) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            InternetAddress ia = new InternetAddress(address.trim(), true);
            ia.validate();
            return ia.getAddress();
        } catch (AddressException e) {
            return INVALID;
        }
    }
}
