/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.saiku.service.olap.ai.AiCubeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every eval suite in a directory and persists each scored report to the {@link
 * EvalResultStore} (saiku#1424 — Phase 2b). Wired to a launcher cron so accuracy is sampled on a
 * schedule against the live model — the difference between a one-shot CI gate and continuous
 * monitoring.
 *
 * <p>Deliberately resilient: one unreadable / malformed / failing suite must not abort the sweep.
 * A YAML that won't parse and a suite whose run throws are both logged and recorded as a skipped
 * entry in the returned summary; the remaining suites still run and persist. That way a single bad
 * file never blinds the whole monitor.
 *
 * <p>Stateless apart from its collaborators. The {@code clock} supplier is injectable so tests get
 * deterministic {@code started_at} stamps; production passes {@link Instant#now}.
 */
public final class ScheduledEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledEvalRunner.class);
    private static final String SUITE_SUFFIX = ".eval.yaml";

    private final Path evalsDir;
    private final EvalAskAdapter adapter;
    private final EvalResultStore store;
    private final Supplier<Instant> clock;

    public ScheduledEvalRunner(Path evalsDir, EvalAskAdapter adapter, EvalResultStore store) {
        this(evalsDir, adapter, store, Instant::now);
    }

    ScheduledEvalRunner(Path evalsDir, EvalAskAdapter adapter, EvalResultStore store, Supplier<Instant> clock) {
        this.evalsDir = Objects.requireNonNull(evalsDir, "evalsDir");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Cron entry point — Spring calls this on the configured schedule. Swallows everything so a
     * scheduler thread is never killed by a stray exception; the per-suite detail is in the
     * returned summary and the log.
     */
    public void runScheduled() {
        try {
            SweepSummary summary = runAll();
            log.info(
                    "Scheduled eval sweep: {} suite(s) run, {} skipped ({})",
                    summary.results().size(),
                    summary.skipped().size(),
                    summary.oneLine());
        } catch (RuntimeException e) {
            log.error("Scheduled eval sweep failed", e);
        }
    }

    /** Run every {@code *.eval.yaml} in the directory, persist each report, and return a summary. */
    public SweepSummary runAll() {
        List<SuiteResult> results = new ArrayList<>();
        List<SkippedSuite> skipped = new ArrayList<>();

        List<Path> files = listSuiteFiles();
        for (Path file : files) {
            EvalSuite suite;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                suite = EvalYamlReader.read(r, file.getFileName().toString());
            } catch (EvalYamlReader.EvalParseException e) {
                log.warn("Skipping unparseable eval suite {}: {} ({})", file, e.getMessage(), e.code());
                skipped.add(new SkippedSuite(file.getFileName().toString(), e.code() + ": " + e.getMessage()));
                continue;
            } catch (IOException e) {
                log.warn("Skipping unreadable eval suite {}: {}", file, e.getMessage());
                skipped.add(new SkippedSuite(file.getFileName().toString(), "unreadable: " + e.getMessage()));
                continue;
            }

            Instant startedAt = clock.get();
            try {
                EvalReport report = new AgentEvalRunner(adapter).run(suite);
                long runId = store.save(report, cubeRef(suite.cube()), startedAt);
                results.add(new SuiteResult(
                        suite.name(), runId, report.passedCount(), report.totalCases(), report.oneLine()));
            } catch (RuntimeException e) {
                log.warn("Skipping eval suite {} — run/persist failed: {}", suite.name(), e.getMessage(), e);
                skipped.add(new SkippedSuite(suite.name(), "run failed: " + e.getMessage()));
            }
        }
        return new SweepSummary(results, skipped);
    }

    private List<Path> listSuiteFiles() {
        if (!Files.isDirectory(evalsDir)) {
            log.debug("Eval dir {} does not exist — nothing to run", evalsDir);
            return List.of();
        }
        try (Stream<Path> paths = Files.list(evalsDir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(SUITE_SUFFIX))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list eval dir {}: {}", evalsDir, e.getMessage());
            return List.of();
        }
    }

    static String cubeRef(AiCubeRef c) {
        if (c == null) return "";
        return c.getConnectionName() + "/" + c.getCatalog() + "/" + c.getSchema() + "/" + c.getCubeName();
    }

    /** One suite's persisted outcome from a sweep. */
    public record SuiteResult(String suiteName, long runId, int passed, int total, String oneLine) {}

    /** A suite that couldn't be run (parse error, unreadable, or a failing run). */
    public record SkippedSuite(String suiteName, String reason) {}

    /** The aggregate outcome of one directory sweep. */
    public record SweepSummary(List<SuiteResult> results, List<SkippedSuite> skipped) {
        public String oneLine() {
            int pass = results.stream().mapToInt(SuiteResult::passed).sum();
            int total = results.stream().mapToInt(SuiteResult::total).sum();
            return pass + "/" + total + " cases passed across " + results.size() + " suite(s), " + skipped.size()
                    + " skipped";
        }
    }
}
