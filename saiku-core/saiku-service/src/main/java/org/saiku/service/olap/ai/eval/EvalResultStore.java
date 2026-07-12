/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists scored eval runs to an embedded H2 database so accuracy can be tracked <em>over time</em>
 * (saiku#1424 — Phase 2). This is what turns the harness from a one-shot CI gate into a live-accuracy
 * monitor: every scheduled run appends a row, and the trend queries answer "is the model getting
 * worse against this cube?".
 *
 * <p>House style mirrors {@code Database.java} — a dedicated H2 file under {@code saiku-home/data/}
 * created with {@code CREATE TABLE IF NOT EXISTS}, accessed via plain JDBC. {@code AUTO_SERVER=TRUE}
 * lets a separate tool (a REST reader, a CLI) open the same file while the launcher holds it.
 *
 * <p>Two tables: {@code eval_run} (one row per suite run, with the pass/fail/degraded tallies) and
 * {@code eval_case_outcome} (one row per case, carrying the mismatch detail as JSON). The store owns
 * no connection pool — each call opens and closes its own connection, which is plenty for a
 * once-an-interval writer and a lightweight reader.
 */
public final class EvalResultStore {

    private static final Logger log = LoggerFactory.getLogger(EvalResultStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;

    /** @param jdbcUrl a full H2 JDBC URL — file-backed in production, {@code jdbc:h2:mem:...} in tests. */
    public EvalResultStore(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        init();
    }

    /** Convenience factory — the launcher's {@code saiku-home/data/eval-results} H2 file. */
    public static EvalResultStore forSaikuHome(String saikuHome) {
        String home = saikuHome == null || saikuHome.isBlank() ? "./saiku-home" : saikuHome;
        return new EvalResultStore("jdbc:h2:file:" + home + "/data/eval-results;AUTO_SERVER=TRUE");
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void init() {
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS eval_run ("
                    + "run_id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "suite_name VARCHAR(255) NOT NULL, "
                    + "cube_ref VARCHAR(512), "
                    + "started_at TIMESTAMP NOT NULL, "
                    + "elapsed_ms BIGINT, "
                    + "total INT, passed INT, failed INT, degraded INT, skipped INT)");
            s.execute("CREATE TABLE IF NOT EXISTS eval_case_outcome ("
                    + "run_id BIGINT NOT NULL, "
                    + "case_name VARCHAR(255), "
                    + "status VARCHAR(16), "
                    + "intent VARCHAR(32), "
                    + "model VARCHAR(128), "
                    + "duration_ms BIGINT, "
                    + "mismatches CLOB)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_run_suite_time ON eval_run(suite_name, started_at)");
        } catch (SQLException e) {
            throw new EvalStoreException("failed to initialise eval result store at " + jdbcUrl, e);
        }
    }

    /**
     * Persist one scored run. Returns the generated run id.
     *
     * @param report the scored suite report
     * @param cubeRef the suite's cube ref, stringified for the row (e.g. {@code conn/cat/sch/cube})
     * @param startedAt when the run began (the caller stamps this — the store never reads the clock)
     */
    public long save(EvalReport report, String cubeRef, Instant startedAt) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(startedAt, "startedAt");
        try (Connection c = connect()) {
            long runId = insertRun(c, report, cubeRef, startedAt);
            insertOutcomes(c, runId, report);
            return runId;
        } catch (SQLException e) {
            throw new EvalStoreException("failed to persist eval run for suite " + report.suiteName(), e);
        }
    }

    private static long insertRun(Connection c, EvalReport report, String cubeRef, Instant startedAt)
            throws SQLException {
        String sql = "INSERT INTO eval_run "
                + "(suite_name, cube_ref, started_at, elapsed_ms, total, passed, failed, degraded, skipped) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, report.suiteName());
            ps.setString(2, cubeRef);
            ps.setTimestamp(3, Timestamp.from(startedAt));
            ps.setLong(4, report.totalDurationMs());
            ps.setInt(5, report.totalCases());
            ps.setInt(6, report.passedCount());
            ps.setInt(7, report.failedCount());
            ps.setInt(8, report.degradedCount());
            ps.setInt(9, report.skippedCount());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertOutcomes(Connection c, long runId, EvalReport report) throws SQLException {
        String sql = "INSERT INTO eval_case_outcome "
                + "(run_id, case_name, status, intent, model, duration_ms, mismatches) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (EvalOutcome o : report.outcomes()) {
                ps.setLong(1, runId);
                ps.setString(2, o.caseName());
                ps.setString(3, o.status().name());
                ps.setString(4, o.actualIntent());
                ps.setString(5, o.actualModel());
                ps.setLong(6, o.durationMs());
                ps.setString(7, mismatchesJson(o));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String mismatchesJson(EvalOutcome o) {
        try {
            return MAPPER.writeValueAsString(o.mismatches());
        } catch (Exception e) {
            // A serialisation glitch on the detail must not lose the whole outcome row.
            log.warn("could not serialise mismatches for case {}", o.caseName(), e);
            return "[]";
        }
    }

    /** Most-recent runs for a suite, newest first. */
    public List<RunSummary> recentRuns(String suiteName, int limit) {
        String sql = "SELECT run_id, suite_name, cube_ref, started_at, elapsed_ms, total, passed, failed, degraded, "
                + "skipped FROM eval_run WHERE suite_name = ? ORDER BY started_at DESC, run_id DESC LIMIT ?";
        List<RunSummary> out = new ArrayList<>();
        try (Connection c = connect();
                PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, suiteName);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RunSummary(
                            rs.getLong("run_id"),
                            rs.getString("suite_name"),
                            rs.getString("cube_ref"),
                            rs.getTimestamp("started_at").toInstant(),
                            rs.getLong("elapsed_ms"),
                            rs.getInt("total"),
                            rs.getInt("passed"),
                            rs.getInt("failed"),
                            rs.getInt("degraded"),
                            rs.getInt("skipped")));
                }
            }
        } catch (SQLException e) {
            throw new EvalStoreException("failed to read recent runs for suite " + suiteName, e);
        }
        return out;
    }

    /** Distinct suite names that have at least one persisted run, alphabetical. Drives the dashboard's suite list. */
    public List<String> suites() {
        List<String> out = new ArrayList<>();
        try (Connection c = connect();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT DISTINCT suite_name FROM eval_run ORDER BY suite_name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new EvalStoreException("failed to list suites", e);
        }
        return out;
    }

    /** The most recent run for a suite, or {@code null} if it has never run. */
    public RunSummary latestRun(String suiteName) {
        List<RunSummary> runs = recentRuns(suiteName, 1);
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Pass-rate over time for a suite, oldest first — the series a trend chart plots. Derived from
     * {@link #recentRuns} so a single query backs both the table and the chart.
     */
    public List<TrendPoint> passRateSeries(String suiteName, int limit) {
        List<RunSummary> runs = recentRuns(suiteName, limit);
        List<TrendPoint> series = new ArrayList<>(runs.size());
        // recentRuns is newest-first; a time series reads oldest-first.
        for (int i = runs.size() - 1; i >= 0; i--) {
            RunSummary r = runs.get(i);
            series.add(new TrendPoint(r.startedAt(), r.passRate(), r.total()));
        }
        return series;
    }

    /** One persisted run's tallies. */
    public record RunSummary(
            long runId,
            String suiteName,
            String cubeRef,
            Instant startedAt,
            long elapsedMs,
            int total,
            int passed,
            int failed,
            int degraded,
            int skipped) {
        /** Pass rate in [0,1]; zero when the run had no cases. */
        public double passRate() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }

    /** One point on the accuracy-over-time series. */
    public record TrendPoint(Instant startedAt, double passRate, int total) {}

    /** Unchecked wrapper so callers don't have to handle {@link SQLException} on every store call. */
    public static final class EvalStoreException extends RuntimeException {
        public EvalStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
