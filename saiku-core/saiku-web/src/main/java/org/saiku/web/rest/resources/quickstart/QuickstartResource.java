/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.quickstart;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.service.schema.generate.quickstart.CsvIngestException;
import org.saiku.service.schema.generate.quickstart.QuickstartIngestService;
import org.saiku.service.schema.generate.quickstart.QuickstartUploadResult;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for saiku#1117's CSV-upload quickstart route.
 *
 * <p>This resource does exactly one thing: turn an uploaded CSV into a queryable, dedicated H2
 * table (via {@link QuickstartIngestService}) and hand back the raw JDBC connection info. It
 * deliberately does NOT register a Saiku datasource or start a schema-generation session — the
 * client does both against the existing, already-shipped endpoints ({@code POST
 * /admin/datasources}, {@code POST /admin/schema-generator/start/{dataSourceId}}), the same way
 * {@code saiku-ui}'s cube-designer route publishes a hand-designed schema (see its {@code
 * publish.ts}). Keeping this resource that thin means the only genuinely new server-side surface
 * for the quickstart flow is "parse a CSV, load it into H2" — everything after that reuses code
 * that is already tested and already in production for the admin schema-generator.
 *
 * <p>Auth follows the same optional-{@link UserService} pattern as {@code SchemaGeneratorResource}
 * (see its class doc): {@code userService} is {@code null} in tests / headless mode, and production
 * wiring sets it so the guard 403s non-admins.
 */
@Path("/saiku/admin/quickstart")
public class QuickstartResource {

    private static final Logger LOG = LoggerFactory.getLogger(QuickstartResource.class);

    private final QuickstartIngestService ingestService;
    private UserService userService; // optional; see class doc

    public QuickstartResource(QuickstartIngestService ingestService) {
        this.ingestService = ingestService;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Parse {@code file} as CSV and load it into a fresh, dedicated H2 table.
     *
     * @param file the uploaded CSV bytes
     * @param detail multipart content-disposition for {@code file}; only its filename is used, as
     *     a fallback table name when {@code table} is blank
     * @param tableName requested table name (form field {@code table}); optional
     */
    @POST
    @Path("/upload")
    @Consumes("multipart/form-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(
            @FormDataParam("file") InputStream file,
            @FormDataParam("file") FormDataContentDisposition detail,
            @FormDataParam("table") String tableName) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (file == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("missing 'file' part in the upload")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        String fallbackName = detail == null || detail.getFileName() == null ? "quickstart" : detail.getFileName();
        try {
            QuickstartUploadResult result = ingestService.ingest(tableName, fallbackName, file);
            return Response.ok(QuickstartUploadResponse.from(result)).build();
        } catch (CsvIngestException badCsv) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(badCsv.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        } catch (IOException | SQLException e) {
            LOG.warn("Quickstart CSV upload failed: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("failed to load the CSV file: " + e.getMessage())
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }
}
