/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.sqlworkbench;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.saiku.service.sqlworkbench.SqlWorkbenchAuditEntry;
import org.saiku.service.sqlworkbench.SqlWorkbenchAuditLog;

/**
 * saiku#1107 — admin-only read API over the SQL workbench audit log, mirroring
 * {@link org.saiku.web.rest.resources.AiAuditResource}. Path is {@code /saiku/admin/*} so it picks
 * up the existing ADMIN-only Spring Security intercept-url for free, and {@code @RolesAllowed} is
 * enforced by Jersey's RolesAllowedDynamicFeature — defense in depth.
 */
@Path("/saiku/admin/sql-workbench-audit")
@RolesAllowed("ROLE_ADMIN")
public class SqlWorkbenchAuditResource {

    private SqlWorkbenchAuditLog auditLog;

    public void setAuditLog(SqlWorkbenchAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AuditPage recent(
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("user") String user) {
        AuditPage page = new AuditPage();
        page.entries = auditLog.recent(limit, offset, user);
        page.total = auditLog.count();
        page.limit = limit;
        page.offset = offset;
        return page;
    }

    /** Paginated audit response envelope. Public fields for Jackson. */
    public static final class AuditPage {
        public List<SqlWorkbenchAuditEntry> entries;
        public long total;
        public int limit;
        public int offset;
    }
}
