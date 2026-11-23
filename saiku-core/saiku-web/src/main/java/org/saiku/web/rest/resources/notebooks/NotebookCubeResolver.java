/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.resources.notebooks;

import org.saiku.olap.dto.SaikuCube;
import org.saiku.service.datasource.DatasourceNameDecoration;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * Resolves the name-only {@link AiCubeRef} a notebook MDX cell stores into a
 * live {@link SaikuCube} (issue #1108).
 *
 * <p>A notebook cell persists (and the client sends) an {@link AiCubeRef} —
 * the same lightweight, Jackson-friendly reference the AI Query API uses —
 * rather than a full {@link SaikuCube}: {@code SaikuCube} has no JSON
 * setters (it is built only via its constructor, from a live schema scan),
 * so it round-trips fine as a server-authored response but is not a safe
 * target for client-authored request JSON. Resolution mirrors {@code
 * OlapAiCubeMetadataService#findCube}, minus the AiValidationException
 * candidate-list wrapping this package doesn't need.
 *
 * <p>Public: shared by {@link NotebookResource#run} (the owner-facing
 * execution path) and {@code org.saiku.web.rest.resources.share.NotebookShareViewResource}
 * (the account-free guest path, issue #941/#1108), which lives in a sibling
 * package.
 */
public final class NotebookCubeResolver {

    private NotebookCubeResolver() {}

    /** Returns the matching live cube, or {@code null} if {@code ref} is
     *  null, unresolved, or discovery fails. Callers turn a null into a
     *  client-facing 400/404 — this class stays exception-free so both the
     *  owner-facing run endpoint and the guest share-view path can treat a
     *  bad reference uniformly. */
    public static SaikuCube resolve(OlapDiscoverService discoverService, AiCubeRef ref) {
        if (ref == null || discoverService == null) {
            return null;
        }
        try {
            for (SaikuCube cube : discoverService.getAllCubes()) {
                if (matches(cube, ref)) {
                    return cube;
                }
            }
        } catch (Exception e) {
            // getAllCubes() throws the checked SaikuOlapException on a bad
            // connection/schema; treat that the same as "not found" here —
            // callers turn a null into a client-facing 400/404.
            return null;
        }
        return null;
    }

    private static boolean matches(SaikuCube cube, AiCubeRef ref) {
        if (!equalsCi(cube.getName(), ref.getCubeName())) {
            return false;
        }
        if (ref.getConnectionName() != null
                && !DatasourceNameDecoration.sameConnection(cube.getConnection(), ref.getConnectionName())) {
            return false;
        }
        if (ref.getCatalog() != null && !equalsCi(cube.getCatalog(), ref.getCatalog())) {
            return false;
        }
        if (ref.getSchema() != null && !equalsCi(cube.getSchema(), ref.getSchema())) {
            return false;
        }
        return true;
    }

    private static boolean equalsCi(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}
