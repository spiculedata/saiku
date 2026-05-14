package org.saiku.service.schema.generate.delta;

import java.util.List;
import java.util.Map;

/**
 * Result of reconciling a freshly inferred draft against a baseline sidecar.
 *
 * @param tags every known target path → its {@link DeltaTag}
 * @param newPaths paths present in current but not baseline
 * @param existingPaths paths matched in both trees by stable id
 * @param removedUpstreamPaths paths present in baseline but missing from current
 */
public record DeltaReport(
        Map<String, DeltaTag> tags,
        List<String> newPaths,
        List<String> existingPaths,
        List<String> removedUpstreamPaths) {}
