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
import java.util.Map;
import org.saiku.service.mail.MailConfigResolver;
import org.saiku.service.mail.MailConfigStore;
import org.saiku.service.mail.MailConfigView;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public void setMailConfigStore(MailConfigStore mailConfigStore) {
        this.mailConfigStore = mailConfigStore;
    }

    public void setMailConfigResolver(MailConfigResolver mailConfigResolver) {
        this.mailConfigResolver = mailConfigResolver;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
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

    // ---- helpers ----

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
