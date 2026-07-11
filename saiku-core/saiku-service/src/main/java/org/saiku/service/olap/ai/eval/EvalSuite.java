/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.List;
import java.util.Objects;
import org.saiku.service.olap.ai.AiCubeRef;

/**
 * A named collection of AI-ask ground-truth cases (saiku#1424). Suites are the unit of a CI run;
 * every case in a suite targets the same cube and the same LLM configuration.
 *
 * <p>Suites live as YAML on disk under {@code saiku-home/evals/}. The launcher walks that
 * directory on demand — evals are not run on boot; they run when {@link AgentEvalRunner} is
 * invoked (from the CLI, a REST endpoint, or CI).
 *
 * @param name a human-readable identifier for the suite. Used in reports and the JUnit-style
 *     CI summary. Should be filename-safe.
 * @param description free text — surfaced verbatim in the report header. Optional.
 * @param cube the cube every case in the suite targets. The runner passes this to the ask flow;
 *     cases don't override it.
 * @param cases the ground-truth cases. Ordered — the runner executes them in order so a failure
 *     surfaces the failing sequence when a case depends on the previous one (rare, but possible
 *     with history-threaded turns).
 */
public record EvalSuite(String name, String description, AiCubeRef cube, List<EvalCase> cases) {

    public EvalSuite {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cube, "cube");
        if (name.isBlank()) {
            throw new IllegalArgumentException("suite name must be non-blank");
        }
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
