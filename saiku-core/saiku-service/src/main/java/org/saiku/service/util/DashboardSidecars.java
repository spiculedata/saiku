/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.service.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.saiku.repository.IRepositoryObject;
import org.saiku.repository.RepositoryFileObject;
import org.saiku.repository.RepositoryFolderObject;
import org.saiku.service.datasource.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flat internal-file names for a dashboard's sidecar files (comments, history).
 *
 * <p>saiku#2550d: the pre-#2550 name sanitised the dashboard path with
 * {@code replaceAll("[^A-Za-z0-9._-]", "_")}, so two DIFFERENT paths could
 * collapse onto one file — {@code homes/a_b/x.saikudash} and
 * {@code homes/a/b/x.saikudash} shared one comments file and one history file,
 * and a user who could read the second path could read and write the first
 * path's comments and read its version history. The new key is a SHA-256 of the
 * exact path, so distinct paths get distinct files. A legacy sidecar is read only
 * when exactly one existing dashboard has its old flattened name. Ambiguous legacy
 * files stay untouched for operator recovery; request writes never delete them.
 */
public final class DashboardSidecars {

    private static final Logger log = LoggerFactory.getLogger(DashboardSidecars.class);

    private DashboardSidecars() {}

    /** Collision-free sidecar path for a dashboard path. */
    public static String path(String kind, String dashboardPath) {
        return "saiku-" + kind + "-v2-" + sha256Hex(dashboardPath) + ".jsonl";
    }

    /** Pre-#2550 sanitised sidecar path. */
    public static String legacyPath(String kind, String dashboardPath) {
        String flat = dashboardPath == null ? "null" : dashboardPath.replaceAll("[^A-Za-z0-9._-]", "_");
        flat = flat.replace("..", "_");
        return "saiku-" + kind + "-" + flat + ".jsonl";
    }

    /** Read the sidecar for a dashboard: the v2 file first, else the legacy file. Null/blank when neither exists. */
    public static String read(DatasourceService datasourceService, String kind, String dashboardPath) {
        String current = datasourceService.getInternalFileData(path(kind, dashboardPath));
        if (current != null) {
            return current;
        }
        String legacy = datasourceService.getInternalFileData(legacyPath(kind, dashboardPath));
        if (legacy == null) return null;
        if (soleLegacyOwner(datasourceService, dashboardPath)) return legacy;
        log.warn(
                "Ambiguous dashboard legacy sidecar {}; leaving it for operator recovery",
                legacyPath(kind, dashboardPath));
        return null;
    }

    /** Write only the collision-free file. The legacy file is retained for safe recovery. */
    public static void write(DatasourceService datasourceService, String kind, String dashboardPath, String content) {
        datasourceService.saveInternalFile(path(kind, dashboardPath), content, null);
    }

    /** Delete only this dashboard's unambiguous v2 sidecar. */
    public static void purge(DatasourceService datasourceService, String kind, String dashboardPath) {
        datasourceService.removeInternalFile(path(kind, dashboardPath));
    }

    private static boolean soleLegacyOwner(DatasourceService datasourceService, String dashboardPath) {
        // The internal-file API has no owner metadata. Enumerate the repository with the
        // service's admin role and fail closed if the listing is incomplete or ambiguous.
        List<IRepositoryObject> dashboards;
        try {
            dashboards = datasourceService.getFiles(List.of("saikudash"), "admin", List.of("ROLE_ADMIN"));
        } catch (RuntimeException e) {
            log.warn("Cannot enumerate dashboards for legacy sidecar ownership", e);
            return false;
        }
        if (dashboards == null) return false;
        String requested = normaliseRepositoryPath(dashboardPath);
        String flattened = legacyPath("owner", requested);
        int matches = countMatchingPaths(dashboards, flattened, requested);
        return matches == 1;
    }

    private static int countMatchingPaths(List<IRepositoryObject> objects, String flattened, String requested) {
        if (objects == null) return 0;
        int matches = 0;
        for (IRepositoryObject object : objects) {
            if (object instanceof RepositoryFolderObject folder) {
                matches += countMatchingPaths(folder.getRepoObjects(), flattened, requested);
            } else if (object instanceof RepositoryFileObject file) {
                String path = normaliseRepositoryPath(file.getPath());
                if (legacyPath("owner", path).equals(flattened)) {
                    if (!path.equals(requested)) return 2;
                    matches++;
                }
            }
            if (matches > 1) return matches;
        }
        return matches;
    }

    private static String normaliseRepositoryPath(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((value == null ? "null" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JRE; this cannot happen.
            throw new IllegalStateException(e);
        }
    }
}
