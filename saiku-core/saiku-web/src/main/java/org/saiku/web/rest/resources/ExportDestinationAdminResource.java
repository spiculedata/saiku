/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDeliveryResult;
import org.saiku.service.export.destination.ExportDestination;
import org.saiku.service.export.destination.ExportDestinationConfig;
import org.saiku.service.export.destination.ExportDestinationConfigField;
import org.saiku.service.export.destination.ExportDestinationConfigStore;
import org.saiku.service.export.destination.ExportDestinationRegistry;
import org.saiku.service.user.UserService;

/**
 * Admin CRUD over export destinations (saiku#1987) at {@code /saiku/admin/export-destinations}.
 *
 * <p>Admin-gated twice over, like the other admin resources: {@code @RolesAllowed("ROLE_ADMIN")} on
 * the class plus the {@code /rest/saiku/admin/**} Spring URL intercept.
 *
 * <h2>Secrets are write-only</h2>
 *
 * A {@code PUT} may carry credentials; a {@code GET} <b>never</b> returns them. The list/read responses
 * carry the <i>names</i> of the secrets that are set ({@code secretFields}) and never their values, so
 * an admin UI can render "service-account key: configured ✓" without a second round trip and without
 * a credential ever entering a browser. The store keeps them in an owner-only file that is not part
 * of the per-destination settings.
 *
 * <p>Note this resource deliberately does <b>not</b> expose an on-demand "send this CSV to Drive"
 * button for an arbitrary query: that surface belongs to a future slice and, when it lands, must go
 * through the same owner-scoped execution path. The MVP job path is the scheduled one.
 */
@Path("/saiku/admin/export-destinations")
@RolesAllowed("ROLE_ADMIN")
public class ExportDestinationAdminResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExportDestinationRegistry registry;
    private ExportDestinationConfigStore configStore;
    private UserService userService;

    public void setRegistry(ExportDestinationRegistry registry) {
        this.registry = registry;
    }

    public void setConfigStore(ExportDestinationConfigStore configStore) {
        this.configStore = configStore;
    }

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Every registered destination with its published config fields and whether it is configured.
     * Never includes a secret value.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        if (denied()) {
            return forbidden();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExportDestination d : registry.list()) {
            out.add(describe(d));
        }
        return Response.ok(out).build();
    }

    /** One destination: its fields, its plain settings, and the names of the secrets that are set. */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id) {
        if (denied()) {
            return forbidden();
        }
        ExportDestination d = find(id);
        if (d == null) {
            return notFound(id);
        }
        return Response.ok(describe(d)).build();
    }

    /**
     * Create or replace a destination's configuration.
     *
     * <p>Body: {@code {"settings": {...}, "secrets": {...}}}. {@code secrets} is optional; a PUT with
     * no {@code secrets} block leaves the stored credentials untouched, so an admin editing a folder id
     * in a form does not have to re-paste a service-account key. There is no way to read a secret back
     * through this API, which is the point.
     */
    @POST
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("id") String id, String body) {
        if (denied()) {
            return forbidden();
        }
        ExportDestination d = find(id);
        if (d == null) {
            return notFound(id);
        }
        Map<String, Object> parsed;
        try {
            parsed = MAPPER.readValue(body == null ? "{}" : body, new TypeReference<>() {});
        } catch (Exception e) {
            return bad("body must be a JSON object with optional 'settings' and 'secrets' maps");
        }
        Map<String, String> settings = stringMap(parsed.get("settings"));
        Map<String, String> secrets = stringMap(parsed.get("secrets"));

        ExportDestinationConfig candidate = ExportDestinationConfig.of(
                settings, secrets.isEmpty() ? configStore.get(d.id()).secrets() : secrets);
        try {
            d.validateConfig(candidate);
        } catch (ExportDeliveryException e) {
            // The message names a field, never a value — it is safe to show an admin verbatim.
            return bad(e.getMessage());
        }
        configStore.save(d.id(), candidate);
        return Response.ok(describe(d)).build();
    }

    /** Forget a destination's settings AND its credentials. Idempotent. */
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") String id) {
        if (denied()) {
            return forbidden();
        }
        ExportDestination d = find(id);
        if (d == null) {
            return notFound(id);
        }
        configStore.delete(d.id());
        return Response.ok(Map.of("id", d.id(), "configured", false)).build();
    }

    /**
     * Deliver a tiny, fixed, server-generated CSV to a destination — a connectivity check that
     * exercises the real token + upload path without exporting anyone's data.
     *
     * <p>The body is a constant produced here, not a caller-supplied payload: this endpoint must not
     * become a general "upload arbitrary bytes to Drive" primitive for an admin session.
     */
    @POST
    @Path("/{id}/test")
    @Produces(MediaType.APPLICATION_JSON)
    public Response test(@PathParam("id") String id) {
        if (denied()) {
            return forbidden();
        }
        ExportDestination d = find(id);
        if (d == null) {
            return notFound(id);
        }
        ExportDestinationConfig config = configStore.get(d.id());
        if (config.isEmpty()) {
            return bad("destination " + d.id() + " has no saved configuration");
        }
        try {
            d.validateConfig(config);
            ExportArtifact probe = ExportArtifact.builder(
                            "saiku-export-destination-check.csv", "text/csv", probeBytes())
                    .metadata("kind", "destination-check")
                    .build();
            ExportDeliveryResult result = d.deliver(probe, config);
            return Response.ok(result.asMap()).build();
        } catch (ExportDeliveryException e) {
            // Sanitized by contract: a status code or a field name, never a token or a response body.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("id", d.id(), "error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    /** A constant, harmless two-line CSV. */
    private static byte[] probeBytes() {
        return ("source,status\r\nsaiku export destination,check\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Object> describe(ExportDestination d) {
        ExportDestinationConfig config = configStore.get(d.id());
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ExportDestinationConfigField f : d.configFields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("label", f.label());
            fm.put("required", f.required());
            fm.put("secret", f.secret());
            fm.put("help", f.help());
            // The current value, only for non-secret fields. A secret field reports `configured` and
            // nothing else — there is deliberately no "currentValue" key for it.
            if (f.secret()) {
                fm.put("configured", config.secret(f.name()) != null);
            } else {
                fm.put("value", config.get(f.name()));
            }
            fields.add(fm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.id());
        out.put("displayName", d.displayName());
        out.put("configured", !config.isEmpty());
        out.put("fields", fields);
        // Names only, never values.
        out.put("secretFields", config.secretNames().stream().toList());
        return out;
    }

    private ExportDestination find(String id) {
        return id == null ? null : registry.find(id.trim().toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return out;
    }

    private boolean denied() {
        return userService != null && !userService.isAdmin();
    }

    private static Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("error", "admin role required"))
                .build();
    }

    private static Response notFound(String id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "no export destination is registered with id '" + id + "'"))
                .build();
    }

    private static Response bad(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", message))
                .build();
    }
}
