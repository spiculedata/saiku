/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.model;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import org.saiku.service.schema.diff.ModelDiffException;
import org.saiku.service.schema.diff.ModelDiffReport;
import org.saiku.service.schema.diff.ModelDiffService;
import org.saiku.service.schema.diff.ModelSnapshot;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /saiku/admin/model/diff} — diff two semantic models and list the saved queries,
 * dashboards and apps the change would break (saiku#1434).
 *
 * <p>Request:
 *
 * <pre>{@code
 * {
 *   "before": { "content": "<Schema …>" },        // or { "path": "datasources/FoodMart4.xml" }
 *   "after":  { "path": "datasources/FoodMart4.proposed.xml" },
 *   "repository": "homes/admin"                   // optional, relative to the repository root
 * }
 * }</pre>
 *
 * <p>Responses: the JSON report by default, {@code text/markdown} with {@code ?format=markdown}
 * (the exact block the CI hook posts on a pull request), and 400 with a stable {@code reason} code
 * for anything that does not parse — {@code UNKNOWN_FORMAT}, {@code MALFORMED},
 * {@code CROSS_FORMAT}.
 *
 * <h2>Why the repository argument is confined</h2>
 *
 * A request may name a sub-directory of the configured repository root, never a path outside it.
 * Without that containment the endpoint would let any admin read and enumerate arbitrary server
 * files (a caller can point a "model" at {@code /etc/shadow} and read the parser's error message,
 * or point the repository root at {@code /} and enumerate it through the scan counts). Confining
 * every path to one root is the whole mitigation; there is no opt-out.
 */
@Path("/saiku/admin/model")
public class ModelDiffResource {

    private static final Logger LOG = LoggerFactory.getLogger(ModelDiffResource.class);

    /** Per-side payload ceiling. A semantic model is kilobytes; anything larger is a mistake. */
    static final int MAX_SIDE_BYTES = 8 * 1024 * 1024;

    private final ModelDiffService service = new ModelDiffService();
    private final java.nio.file.Path repositoryRoot;
    private UserService userService; // optional; null keeps the resource test-friendly

    /**
     * @param repositoryRoot the directory every path in a request is resolved against, normally
     *                       {@code <saiku-home>/repository/data}
     */
    public ModelDiffResource(java.nio.file.Path repositoryRoot) {
        this.repositoryRoot =
                repositoryRoot == null ? null : repositoryRoot.toAbsolutePath().normalize();
    }

    /**
     * String-root constructor for the Spring bean definition, which supplies a
     * {@code ${saiku.home}/repository/data} placeholder as text.
     */
    public ModelDiffResource(String repositoryRoot) {
        this(repositoryRoot == null || repositoryRoot.isBlank() ? null : java.nio.file.Paths.get(repositoryRoot));
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    @POST
    @Path("/diff")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, "text/markdown"})
    public Response diff(@QueryParam("format") @DefaultValue("json") String format, ModelDiffRequest request) {
        Response forbidden = adminGuard();
        if (forbidden != null) {
            return forbidden;
        }
        if (request == null || request.before == null || request.after == null) {
            return bad(ModelDiffException.Reason.MALFORMED, "both 'before' and 'after' are required");
        }
        if (request.before.isEmpty() || request.after.isEmpty()) {
            return bad(ModelDiffException.Reason.MALFORMED, "each side needs either 'content' or 'path'");
        }

        java.nio.file.Path repository;
        try {
            repository = resolveRepository(request.repository);
        } catch (ModelDiffException | SecurityException | InvalidPathException e) {
            return bad(ModelDiffException.Reason.MALFORMED, e.getMessage());
        }

        try {
            ModelSnapshot before = resolve(request.before, repository);
            ModelSnapshot after = resolve(request.after, repository);
            ModelDiffReport report = service.diff(before, after, repository);
            if ("markdown".equalsIgnoreCase(format)) {
                return Response.ok(report.markdown(), "text/markdown;charset=UTF-8")
                        .build();
            }
            return Response.ok(new ModelDiffResponse(report, false)).build();
        } catch (ModelDiffException e) {
            LOG.debug("model diff rejected: {}", e.getMessage());
            return bad(e.reason(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("model diff failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("model diff failed: " + e.getMessage())
                    .build();
        }
    }

    private ModelSnapshot resolve(ModelDiffRequest.Side side, java.nio.file.Path repository) {
        if (side.content != null && !side.content.isBlank()) {
            if (side.content.length() > MAX_SIDE_BYTES) {
                throw new ModelDiffException(
                        ModelDiffException.Reason.MALFORMED,
                        "inline model payload exceeds " + MAX_SIDE_BYTES + " bytes");
            }
            return service.parse(side.content, side.name == null || side.name.isBlank() ? "before" : side.name);
        }
        java.nio.file.Path file = resolveInside(side.path, repository);
        if (!Files.isRegularFile(file)) {
            throw new ModelDiffException(ModelDiffException.Reason.MALFORMED, "no model file at '" + side.path + "'");
        }
        // parseFile already converts an I/O failure into a MALFORMED ModelDiffException.
        return service.parseFile(file);
    }

    /** The directory to walk: the request's sub-path, or the whole repository root. */
    private java.nio.file.Path resolveRepository(String requested) {
        if (requested == null || requested.isBlank()) {
            return repositoryRoot;
        }
        return resolveInside(requested, repositoryRoot);
    }

    /**
     * Resolve a request-supplied path inside the repository root, rejecting anything that escapes
     * it — via {@code ..}, via an absolute path, or via a symlink pointing outside.
     */
    java.nio.file.Path resolveInside(String requested, java.nio.file.Path base) {
        if (requested == null || requested.isBlank()) {
            throw new ModelDiffException(ModelDiffException.Reason.MALFORMED, "empty path");
        }
        if (base == null) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "this server has no repository root configured");
        }
        java.nio.file.Path candidate;
        try {
            candidate = Paths.get(requested);
        } catch (InvalidPathException e) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "'" + requested + "' is not a usable path");
        }
        java.nio.file.Path resolved = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).normalize();
        if (!resolved.startsWith(base)) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "'" + requested + "' resolves outside the repository root");
        }
        // A symlink inside the repository can still point out of it; follow links and re-check.
        try {
            if (Files.exists(resolved) && !resolved.toRealPath().startsWith(base.toRealPath())) {
                throw new ModelDiffException(
                        ModelDiffException.Reason.MALFORMED, "'" + requested + "' links outside the repository root");
            }
        } catch (IOException e) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "could not resolve '" + requested + "': " + e.getMessage());
        }
        return resolved;
    }

    private Response adminGuard() {
        if (userService == null) {
            return null; // test / headless mode
        }
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("admin role required")
                    .build();
        }
        return null;
    }

    private static Response bad(ModelDiffException.Reason reason, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(reason.name() + ": " + message)
                .build();
    }
}
