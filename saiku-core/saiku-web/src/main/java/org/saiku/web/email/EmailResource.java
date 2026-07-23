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

    public void setMailSender(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void setMailConfig(MailConfig mailConfig) {
        this.mailConfig = mailConfig;
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        boolean configured = mailSender != null && mailSender.isConfigured();
        return Response.ok(Map.of("configured", configured)).build();
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
        if (mailSender == null || !mailSender.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "email not configured"))
                    .build();
        }
        try {
            MailMessage msg = assembler.assemble(body, mailConfig.from());
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
