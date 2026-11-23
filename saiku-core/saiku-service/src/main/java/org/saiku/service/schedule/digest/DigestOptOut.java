/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.user.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The per-user opt-out from scheduled digests (saiku#1119).
 *
 * <p>The preference lives in the caller's own {@code /saiku/api/preferences} document under {@link
 * #KEY} — a key that already exists for exactly this purpose, is already scoped to the authenticated
 * user (the username comes from the security context, never from the request, so there is no path to
 * someone else's document), and needs no new storage, no new endpoint and no new admin surface. A user
 * sets it with the same {@code PUT} they use for any other preference.
 *
 * <p><b>Checked first, before anything else happens.</b> The handler consults this before it queries a
 * cube, before it calls the LLM, and before it composes mail — an opted-out user is not merely not
 * emailed, they are not queried. That matters beyond bandwidth: a query is a data access under the
 * user's own identity, and "stop sending me digests" is a reasonable thing to read as "stop touching my
 * data for this".
 *
 * <p><b>Fail-open on read errors, by design.</b> An absent, unreadable or corrupt preferences document
 * reads as "not opted out", because that is the state of every account that has never touched the
 * setting and the state the #943 digest shipped with. Failing closed here would silently stop every
 * digest on an instance whose repository hiccuped — a worse outcome than the one this guards.
 */
public final class DigestOptOut {

    private static final Logger log = LoggerFactory.getLogger(DigestOptOut.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The per-user preference key. {@code true} means "do not run or send my digests". */
    public static final String KEY = "dashboardDigestOptOut";

    private DigestOptOut() {}

    /**
     * Whether the CURRENT security principal has opted out. Returns {@code false} when there is no
     * principal, no repository, or no readable preferences document.
     */
    public static boolean isOptedOut(IDatasourceManager datasourceManager) {
        return isOptedOut(datasourceManager, currentUsername());
    }

    /**
     * Whether {@code username} has opted out. A blank {@code username} falls back to the current
     * security principal, so the scheduler path can pass the job owner explicitly (or nothing at all)
     * and both land on the same answer.
     */
    public static boolean isOptedOut(IDatasourceManager datasourceManager, String username) {
        if (datasourceManager == null) {
            return false;
        }
        String principal = (username == null || username.isBlank()) ? currentUsername() : username;
        if (principal == null || principal.isBlank()) {
            return false;
        }
        String path;
        try {
            path = UserPreferences.pathFor(principal);
        } catch (IllegalArgumentException e) {
            // A username with no usable characters cannot address a preferences document at all.
            return false;
        }
        try {
            String data = datasourceManager.getInternalFileData(path);
            if (data == null || data.isBlank()) {
                return false;
            }
            JsonNode root = MAPPER.readTree(data);
            JsonNode value = root == null ? null : root.get(KEY);
            return value != null && value.asBoolean(false);
        } catch (Exception e) {
            // Absent is the normal case (the repository signals it by throwing), so this stays quiet.
            log.debug("No readable preferences for the digest opt-out check; assuming not opted out");
            return false;
        }
    }

    /** The authenticated principal, or null when there is no usable security context. */
    static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            String name = auth.getName();
            return (name == null || name.isBlank() || "anonymousUser".equals(name)) ? null : name;
        } catch (RuntimeException e) {
            log.debug("No usable security context for the digest opt-out check", e);
            return null;
        }
    }
}
