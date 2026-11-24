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
import org.saiku.service.datasource.DatasourceService;

/**
 * Flat internal-file names for a dashboard's sidecar files (comments, history).
 *
 * <p>saiku#2550d: the pre-#2550 name sanitised the dashboard path with
 * {@code replaceAll("[^A-Za-z0-9._-]", "_")}, so two DIFFERENT paths could
 * collapse onto one file — {@code homes/a_b/x.saikudash} and
 * {@code homes/a/b/x.saikudash} shared one comments file and one history file,
 * and a user who could read the second path could read and write the first
 * path's comments and read its version history. The new key is a SHA-256 of the
 * exact path, so distinct paths get distinct files. Existing sidecars are still
 * found: reads fall back to the legacy sanitised name, and the first write
 * retires the legacy file (its contents were merged into that write, so keeping
 * both would resurrect comments a user already deleted).
 */
public final class DashboardSidecars {

    private DashboardSidecars() {}

    /** Collision-free sidecar path for a dashboard path. */
    public static String path(String kind, String dashboardPath) {
        return "saiku-" + kind + "-v2-" + sha256Hex(dashboardPath) + ".jsonl";
    }

    /** Pre-#2550 sanitised sidecar path — reads fall back to it, writes retire it. */
    public static String legacyPath(String kind, String dashboardPath) {
        String flat = dashboardPath == null ? "null" : dashboardPath.replaceAll("[^A-Za-z0-9._-]", "_");
        flat = flat.replace("..", "_");
        return "saiku-" + kind + "-" + flat + ".jsonl";
    }

    /** Read the sidecar for a dashboard: the v2 file first, else the legacy file. Null/blank when neither exists. */
    public static String read(DatasourceService datasourceService, String kind, String dashboardPath) {
        String current = datasourceService.getInternalFileData(path(kind, dashboardPath));
        if (current != null && !current.isBlank()) {
            return current;
        }
        return datasourceService.getInternalFileData(legacyPath(kind, dashboardPath));
    }

    /**
     * Write the sidecar: always to the v2 file. When the legacy file exists it is
     * removed — the caller has just merged its contents into {@code content}.
     */
    public static void write(DatasourceService datasourceService, String kind, String dashboardPath, String content) {
        datasourceService.saveInternalFile(path(kind, dashboardPath), content, null);
        String legacy = legacyPath(kind, dashboardPath);
        if (datasourceService.getInternalFileData(legacy) != null) {
            datasourceService.removeInternalFile(legacy);
        }
    }

    /** Delete every version of the sidecar — called when the dashboard itself is removed. */
    public static void purge(DatasourceService datasourceService, String kind, String dashboardPath) {
        datasourceService.removeInternalFile(path(kind, dashboardPath));
        datasourceService.removeInternalFile(legacyPath(kind, dashboardPath));
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
