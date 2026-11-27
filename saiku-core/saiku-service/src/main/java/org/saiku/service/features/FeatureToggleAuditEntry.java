/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.features;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One audit record for a feature-toggle change (saiku#2618). Public fields +
 * no-arg constructor so Jackson can round-trip it to/from the JSONL audit file,
 * mirroring {@link org.saiku.service.sqlworkbench.SqlWorkbenchAuditEntry}.
 *
 * <p>{@code before} / {@code after} carry the role grant as stored (null =
 * unrestricted, empty list = admins only, listed = those roles plus admins), so
 * a reviewer can answer "what could ROLE_USER see on Monday that they cannot on
 * Tuesday" from the trail alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeatureToggleAuditEntry {

    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_RESET = "reset";

    /** ISO-8601 UTC timestamp, second precision. */
    public String ts;
    /** Authenticated principal that made the change (the admin). */
    public String actor;
    /** Feature key from {@link FeatureCatalogue}. */
    public String feature;
    /** What changed: {@link #ACTION_UPDATE} or {@link #ACTION_RESET}. */
    public String action;
    /** The grant before the write (null = unrestricted). */
    public List<String> before;
    /** The grant after the write (null = unrestricted). */
    public List<String> after;

    public FeatureToggleAuditEntry() {}
}
