/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.rest.resources.schemagen;

/**
 * Poll body for {@code GET /saiku/admin/schema-generator/{sessionId}/status}.
 *
 * <p>{@code failureMessage} is {@code null} unless the pipeline transitioned to
 * {@code FAILED}. {@code cubeCount} / {@code suggestionCount} are snapshots of the current
 * draft + suggestions view — useful for progress bars before the full draft is fetched.
 *
 * <p>{@code deltaNewCount} / {@code deltaRemovedCount} are derived from the session's
 * {@code DeltaReport} (populated in re-run mode when a baseline sidecar is found). Both are
 * {@code 0} on first-run or when reconciliation has not yet produced a report — the UI uses
 * them to decide whether to show the "Changes detected" banner.
 */
public record StatusResponse(
        String sessionId,
        String stage,
        String failureMessage,
        int cubeCount,
        int suggestionCount,
        int deltaNewCount,
        int deltaRemovedCount) {}
