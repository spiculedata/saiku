/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.service.ossie.converter.OssieValidationReport;
import org.saiku.service.ossie.converter.VendorModelFile;
import org.saiku.service.ossie.converter.VendorModelImportService;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vendor semantic-model import (saiku#1730) — {@code /saiku/api/ossie/import}.
 *
 * <pre>
 *   GET  /saiku/api/ossie/import/formats   - what can be imported (drives the UI picker)
 *   POST /saiku/api/ossie/import           - convert + validate; persists nothing
 *   POST /saiku/api/ossie/import/save      - write the confirmed YAML to disk; returns its path
 * </pre>
 *
 * <p>{@code POST /import} accepts either {@code application/json}
 * ({@code {"format":"lookml","modelName":"…","files":[{"name":"orders.view","content":"…"}]}})
 * — which is what the browser sends after reading the files locally, so the YAML never has to
 * survive a nested encoding — or {@code multipart/form-data} with one {@code file} part per
 * artefact, which is what curl and scripted imports use.
 *
 * <p>Splitting import from save is the whole point of the flow: the user sees the converted
 * model, the element counts and the per-element validation report, and only then does anything
 * touch the filesystem. Registering the datasource itself stays with the existing admin
 * datasource API (the UI pre-fills the OSSIE form with the path this endpoint returns).
 *
 * <p><b>Security.</b> Admin-only, class-level {@code @RolesAllowed("ROLE_ADMIN")} plus an
 * explicit {@code userService.isAdmin()} check on the write path — the convert reads nothing
 * private, but the save writes a model file that every datasource can then query, so it's the
 * same trust boundary as the admin datasource API it feeds.
 */
@Path("/saiku/api/ossie/import")
@RolesAllowed("ROLE_ADMIN")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OssieImportResource {

    private static final Logger log = LoggerFactory.getLogger(OssieImportResource.class);

    private VendorModelImportService importService;
    private UserService userService;

    public void setImportService(VendorModelImportService s) {
        this.importService = s;
    }

    public void setUserService(UserService s) {
        this.userService = s;
    }

    /**
     * Belt to the {@code @RolesAllowed} braces. Null-tolerant like the mail resources: an
     * unwired deployment (unit tests, the launcher before userServiceBean exists) fails
     * closed on the write paths rather than throwing.
     */
    private boolean isAdmin() {
        return userService != null && userService.isAdmin();
    }

    /** The formats this deployment can import. Drives the "source format" picker in the UI. */
    @GET
    @Path("/formats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response formats() {
        return Response.ok(importService.supportedFormats()).build();
    }

    /** Convert + validate. Nothing is persisted; see {@link #save(SaveRequest)}. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importJson(ImportRequest request) {
        if (request == null
                || request.getFormat() == null
                || request.getFormat().isBlank()) {
            return error(
                    Response.Status.BAD_REQUEST,
                    "Missing 'format'. Call GET /saiku/api/ossie/import/formats for the list.");
        }
        List<VendorModelFile> files = new ArrayList<>();
        for (FilePayload f : request.getFiles()) {
            if (f == null) continue;
            files.add(new VendorModelFile(f.getName(), f.getContent()));
        }
        if (files.isEmpty()
                && request.getContent() != null
                && !request.getContent().isBlank()) {
            files.add(new VendorModelFile(request.getName(), request.getContent()));
        }
        if (files.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "No file content in the request — attach at least one file.");
        }
        return runImport(request.getFormat(), files, request.getModelName());
    }

    /** Multipart variant: one {@code file} part per artefact, plus {@code format} / {@code modelName}. */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importMultipart(
            @FormDataParam("format") String format,
            @FormDataParam("modelName") String modelName,
            @FormDataParam("file") List<FormDataBodyPart> parts) {
        if (format == null || format.isBlank()) {
            return error(Response.Status.BAD_REQUEST, "Missing multipart part 'format'.");
        }
        List<VendorModelFile> files = new ArrayList<>();
        if (parts != null) {
            for (FormDataBodyPart part : parts) {
                try {
                    String name =
                            part.getFileName().orElseGet(() -> part.getName() == null ? "upload" : part.getName());
                    files.add(new VendorModelFile(name, part.getValue()));
                } catch (RuntimeException e) {
                    // ProcessingException / IllegalStateException from a non-text part.
                    return error(
                            Response.Status.BAD_REQUEST, "Could not read uploaded part as text: " + e.getMessage());
                }
            }
        }
        if (files.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "No 'file' parts in the multipart body.");
        }
        return runImport(format, files, modelName);
    }

    /**
     * Write the YAML the user confirmed to {@code <saiku.home>/semantic-models/<name>.ossie.yaml}
     * and return the path to paste into the OSSIE datasource form. Saving is refused when the
     * YAML has validation errors (an unjoinable relationship, a duplicate name) — re-validated
     * here rather than trusted from the client's copy of the report. Warnings still save.
     */
    @POST
    @Path("/save")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(SaveRequest request) {
        if (!isAdmin()) {
            return error(Response.Status.FORBIDDEN, "Admin only.");
        }
        if (request == null || request.getYaml() == null || request.getYaml().isBlank()) {
            return error(
                    Response.Status.BAD_REQUEST, "Nothing to save — re-run the import and send the returned YAML.");
        }
        OssieValidationReport report;
        try {
            report = importService.validateYaml(request.getYaml());
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
        if (report.hasErrors()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new SaveRejected(report))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        try {
            String path = importService.save(
                    request.getModelName(), request.getYaml(), Boolean.TRUE.equals(request.getOverwrite()));
            return Response.ok(new SaveResponse(path)).build();
        } catch (IllegalStateException e) {
            return error(Response.Status.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private Response runImport(String format, List<VendorModelFile> files, String modelName) {
        try {
            return Response.ok(importService.importModel(format, files, modelName))
                    .build();
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (org.saiku.service.ossie.converter.VendorModelConversionException e) {
            return error(Response.Status.BAD_REQUEST, "Conversion failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Vendor model import failed (format=" + format + ")", e);
            return error(Response.Status.INTERNAL_SERVER_ERROR, "Import failed: " + e.getMessage());
        }
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status)
                .entity(new ErrorResponse(message))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    // ------------------------------------------------------------------
    // wire types
    // ------------------------------------------------------------------

    /** JSON body of {@code POST /import}. */
    public static class ImportRequest {
        private String format;
        private String modelName;

        /** Convenience for the single-file / paste case. */
        private String name;

        private String content;

        private List<FilePayload> files = new ArrayList<>();

        public String getFormat() {
            return format;
        }

        public void setFormat(String v) {
            this.format = v;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String v) {
            this.modelName = v;
        }

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String v) {
            this.content = v;
        }

        public List<FilePayload> getFiles() {
            return files == null ? new ArrayList<>() : files;
        }

        public void setFiles(List<FilePayload> v) {
            this.files = v;
        }
    }

    /** One uploaded artefact in the JSON body. */
    public static class FilePayload {
        private String name;
        private String content;

        public String getName() {
            return name;
        }

        public void setName(String v) {
            this.name = v;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String v) {
            this.content = v;
        }
    }

    /** JSON body of {@code POST /save}. */
    public static class SaveRequest {
        private String modelName;
        private String yaml;
        private Boolean overwrite;

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String v) {
            this.modelName = v;
        }

        public String getYaml() {
            return yaml;
        }

        public void setYaml(String v) {
            this.yaml = v;
        }

        public Boolean getOverwrite() {
            return overwrite;
        }

        public void setOverwrite(Boolean v) {
            this.overwrite = v;
        }
    }

    /** 400 body when the submitted model still carries validation errors. */
    public static class SaveRejected {
        private final String error;
        private final OssieValidationReport validation;

        public SaveRejected(OssieValidationReport report) {
            this.error = "The model has " + report.getErrorCount()
                    + " validation error(s); fix them in the YAML (or re-import without the offending elements) before saving.";
            this.validation = report;
        }

        @JsonProperty("error")
        public String getError() {
            return error;
        }

        @JsonProperty("validation")
        public OssieValidationReport getValidation() {
            return validation;
        }
    }

    /** Response of {@code POST /save}. */
    public static class SaveResponse {
        private final String path;

        public SaveResponse(String path) {
            this.path = path;
        }

        @JsonProperty("path")
        public String getPath() {
            return path;
        }
    }

    /** Uniform error envelope — same shape the rest of the REST surface returns. */
    public static class ErrorResponse {
        private final String error;

        public ErrorResponse(String message) {
            this.error = message;
        }

        @JsonProperty("error")
        public String getError() {
            return error;
        }
    }
}
