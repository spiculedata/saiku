/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.charset.StandardCharsets;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.user.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Per-user preferences — a small, account-level key/value bag.
 *
 * <p>saiku#1868: the UI had nowhere to remember a per-user decision, so things like "I have seen the
 * onboarding tour" lived in {@code localStorage} and replayed on every new browser, machine or
 * private window. That is per-browser, not per-person.
 *
 * <p>The server does not interpret the contents — clients own the keys. What it owns is <b>whose</b>
 * preferences these are: the username comes from the security context, never from the request, so
 * there is no way to address another account's document. There is deliberately no path parameter
 * and no admin override; an admin reading someone's UI preferences has no use case and would only
 * be an authorisation surface to get wrong.
 */
@Path("/saiku/api/preferences")
public class UserPreferencesResource {

    private static final Logger log = LoggerFactory.getLogger(UserPreferencesResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY = "{}";

    private IDatasourceManager datasourceManager;

    public void setDatasourceManager(IDatasourceManager datasourceManager) {
        this.datasourceManager = datasourceManager;
    }

    /** The authenticated caller, or null when there is no usable security context. */
    private static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            String name = auth.getName();
            return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? null : name;
        } catch (RuntimeException e) {
            log.debug("no usable security context for preferences", e);
            return null;
        }
    }

    /**
     * Read the caller's preferences.
     *
     * @return {@code 200} with the stored object, or with {@code {}} when nothing has been saved —
     *     "no preferences yet" is a normal state for every new account, not a 404.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        String user = currentUsername();
        if (user == null) {
            return Response.status(Status.UNAUTHORIZED).build();
        }
        return Response.ok(readFor(user)).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Merge {@code patch} into the caller's preferences and return the result.
     *
     * <p>A merge rather than a replace, because two browser tabs each writing a different key must
     * not silently discard each other's. A key set to JSON {@code null} is removed, which is the
     * only way a client can delete one.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response put(String body) {
        String user = currentUsername();
        if (user == null) {
            return Response.status(Status.UNAUTHORIZED).build();
        }
        JsonNode patch;
        try {
            patch = MAPPER.readTree(body == null || body.isBlank() ? EMPTY : body);
        } catch (Exception e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity("preferences must be a JSON object")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        if (!patch.isObject()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity("preferences must be a JSON object")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        ObjectNode merged = existingFor(user);
        patch.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                merged.remove(entry.getKey());
            } else {
                merged.set(entry.getKey(), entry.getValue());
            }
        });

        String json = merged.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > UserPreferences.MAX_BYTES) {
            return Response.status(Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity("preferences exceed " + UserPreferences.MAX_BYTES + " bytes")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        try {
            datasourceManager.saveInternalFile(UserPreferences.pathFor(user), json, null);
        } catch (Exception e) {
            log.error("could not save preferences", e);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity("could not save preferences")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * The caller's stored document as a mutable object, or a fresh empty one.
     *
     * <p>A corrupt or non-object document yields an empty node rather than an error: whatever went
     * wrong, it must not lock a user out of their own preferences forever.
     */
    private ObjectNode existingFor(String user) {
        try {
            JsonNode existing = MAPPER.readTree(readFor(user));
            if (existing.isObject()) {
                return (ObjectNode) existing;
            }
            log.warn("stored preferences were not a JSON object, starting fresh");
        } catch (Exception e) {
            log.warn("unreadable stored preferences, starting fresh: {}", e.getMessage());
        }
        return MAPPER.createObjectNode();
    }

    /** Stored document for {@code user}, or {@code {}} when absent or unreadable. */
    private String readFor(String user) {
        try {
            String data = datasourceManager.getInternalFileData(UserPreferences.pathFor(user));
            return (data == null || data.isBlank()) ? EMPTY : data;
        } catch (Exception e) {
            // Absent is the overwhelmingly common case (every account before its first write) and
            // the repository signals it by throwing, so this stays at debug.
            log.debug("no stored preferences: {}", e.getMessage());
            return EMPTY;
        }
    }
}
