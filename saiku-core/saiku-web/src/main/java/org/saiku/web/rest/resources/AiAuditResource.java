/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.saiku.service.olap.ai.audit.AiAuditEntry;
import org.saiku.service.olap.ai.audit.AiAuditLog;

/**
 * saiku#906 — admin-only read API over the AI audit log. Lets ops answer
 * "what did the AI surface do, by whom, allowed or denied?" without shell access
 * to the JSONL file. Returns the most recent entries newest-first, filterable by
 * user and start date, paginated.
 *
 * <p>Path is {@code /saiku/admin/ai-audit} (the {@code /saiku/admin} convention,
 * and deliberately NOT under {@code /ai/} so reads of the audit log aren't
 * themselves audited by {@link AiAuditFilter}). {@code @RolesAllowed} is enforced
 * by Jersey's RolesAllowedDynamicFeature AND the {@code /rest/saiku/admin/**}
 * Spring Security intercept-url — defence in depth.
 */
@Path("/saiku/admin/ai-audit")
@RolesAllowed("ROLE_ADMIN")
public class AiAuditResource {

    private AiAuditLog auditLog;

    public void setAuditLog(AiAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AiAuditPage recent(
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("user") String user,
            @QueryParam("since") String since) {
        AiAuditPage page = new AiAuditPage();
        page.entries = auditLog.recent(limit, offset, user, since);
        page.total = auditLog.count();
        page.limit = limit;
        page.offset = offset;
        return page;
    }

    /** Paginated audit response envelope. Public fields for Jackson. */
    public static final class AiAuditPage {
        public List<AiAuditEntry> entries;
        public long total;
        public int limit;
        public int offset;
    }
}
