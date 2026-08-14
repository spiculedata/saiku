package org.saiku.web.email;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.saiku.service.mail.trust.RecipientTrustStore;
import org.saiku.service.mail.trust.RecipientTrustView;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin endpoint for the recipient allowlist (saiku#1811, PR1). Lets an admin curate the set of
 * addresses and domain suffixes that a future recipient-trust gate will honour.
 *
 * <p><b>DORMANT infrastructure — this endpoint cannot mail anyone.</b> It only reads/writes the
 * file-backed {@link RecipientTrustStore}; nothing here sends mail, nothing here is consulted by any
 * existing send path, and the anti-relay boundary (recipient is only ever the server's own {@code
 * selfTo}) is unchanged. Consent, suppression, env-seed and the actual gate land in later phases.
 *
 * <p><b>Security.</b>
 *
 * <ul>
 *   <li>Admin-only: class-level {@code @RolesAllowed("ROLE_ADMIN")} (the JAX-RS gate) AND an explicit
 *       {@code userService.isAdmin()} check per method — belt and braces, mirroring {@link
 *       MailConfigResource}. There is also the Spring URL gate over {@code /rest/saiku/admin/**}.
 *   <li>Every stored entry is normalised (lowercase, CRLF-strip) and RFC-validated in the store, so
 *       header/log injection and malformed addresses are dropped before anything is persisted.
 * </ul>
 */
@Path("/saiku/admin/mail-recipients")
@RolesAllowed("ROLE_ADMIN")
public class RecipientTrustResource {

    private static final Logger log = LoggerFactory.getLogger(RecipientTrustResource.class);

    private RecipientTrustStore recipientTrustStore;
    private UserService userService;

    public void setRecipientTrustStore(RecipientTrustStore recipientTrustStore) {
        this.recipientTrustStore = recipientTrustStore;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /** Current recipient allowlist (addresses + domain suffixes + total count). */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        if (!isAdmin()) {
            return forbidden();
        }
        return Response.ok(recipientTrustStore.readView()).build();
    }

    /** Replace the allowlist. Entries are normalised + validated by the store; malformed ones dropped. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(RecipientTrustRequest body) {
        if (!isAdmin()) {
            return forbidden();
        }
        if (body == null) {
            return badRequest("request body is required");
        }
        List<String> addresses = body.getAddresses() == null ? List.of() : body.getAddresses();
        List<String> domains = body.getDomains() == null ? List.of() : body.getDomains();

        RecipientTrustView view = recipientTrustStore.save(addresses, domains);
        // Log counts only — never the individual addresses (avoid a PII/allowlist dump in the log).
        log.info(
                "Admin {} updated recipient allowlist ({} addresses, {} domains)",
                currentUser(),
                view.addresses().size(),
                view.domains().size());
        return Response.ok(view).build();
    }

    // ---- helpers ----

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
}
