/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import java.util.List;

/**
 * Turns the structured deltas of one dashboard digest into the "here's what changed" bullets a human
 * reads (saiku#1119).
 *
 * <p>Two implementations ship:
 *
 * <ul>
 *   <li>{@link TemplateDigestNarrator} — deterministic bullets built from the deltas alone. No LLM, no
 *       network, no data egress. It is both <b>phase 1</b> of the issue's phasing and the <b>fallback</b>
 *       every other implementation degrades to, so a digest is never withheld because a model call
 *       failed.</li>
 *   <li>{@link NlAskDigestNarrator} — phase 2: grounds the narration on the same deltas via the
 *       configured LLM provider, and falls back to the template on any degradation.</li>
 * </ul>
 *
 * <p><b>Contract:</b> implementations MUST NOT throw. A narrator that cannot produce bullets returns an
 * empty list, and {@link InsightDigestBuilder} substitutes the template. Implementations MUST NOT log
 * measure values or prompts at INFO or above — the deltas are business data, and this code runs
 * unattended on a scheduler thread.
 */
@FunctionalInterface
public interface DigestNarrator {

    /**
     * Narrate one digest.
     *
     * @param dashboardTitle the dashboard's display title, or null
     * @param deltas the resolved period-over-period deltas (never null; may be empty)
     * @param maxBullets the soft cap on how many bullets to return (values &lt; 1 are read as 1)
     * @return the bullets, most interesting first; empty when nothing can be said
     */
    List<String> narrate(String dashboardTitle, List<MeasureDelta> deltas, int maxBullets);
}
