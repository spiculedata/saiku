/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.olap4j.CellSet;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.OlapStatement;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.util.QueryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Explain this number" (saiku#1118): one right-clicked cell in, one explained cell out.
 *
 * <p>Reads the cell from the executed cellset already sitting in the session's query context, so the
 * common path costs no re-run of the user's query — the value, the formatted value, the coordinate
 * members and the MDX all come from cache. Two things are added on top:
 *
 * <ol>
 *   <li>the <em>cell MDX</em> — a single-cell query built from the coordinate, which is what
 *       actually computes this number; and, best-effort, the SQL the planner emits for it (see
 *       {@link SqlCaptureProbe} for why that's captured rather than generated);
 *   <li>the <em>story</em> — {@link CellDrivers} findings, narrated deterministically, then handed
 *       to the configured LLM provider for a better-written version grounded on exactly those
 *       findings. The LLM is optional in both directions: absent it the deterministic text is the
 *       narrative, and a provider failure falls back to the same text.
 * </ol>
 *
 * <p>The LLM is reached through {@link AiAskService}, not by talking to a provider directly, so the
 * egress policy, provider selection and audit trail are the ones every other AI surface uses — a
 * deployment that forbids cell data leaving the box gets a schema-only (or policy-denied) ask, and
 * the deterministic narrative instead.
 */
public class CellExplainService {

    private static final Logger log = LoggerFactory.getLogger(CellExplainService.class);

    private ThinQueryService thinQueryService;
    private OlapDiscoverService olapDiscoverService;
    private AiAskService askService;

    public void setThinQueryService(ThinQueryService thinQueryService) {
        this.thinQueryService = thinQueryService;
    }

    public void setOlapDiscoverService(OlapDiscoverService olapDiscoverService) {
        this.olapDiscoverService = olapDiscoverService;
    }

    /** Optional: without an ask service the explain still works, one layer short. */
    public void setAskService(AiAskService askService) {
        this.askService = askService;
    }

    public CellExplainResult explain(CellExplainRequest request) {
        long start = System.currentTimeMillis();
        if (request == null) {
            throw new CellExplainException(CellExplainException.Code.VALIDATION_ERROR, "request body required");
        }
        String queryName = StringUtils.trimToNull(request.getQueryName());
        if (queryName == null) {
            throw new CellExplainException(CellExplainException.Code.VALIDATION_ERROR, "queryName required");
        }
        CellExplainRequest.CellCoordinate position = request.getPosition();
        if (position == null) {
            throw new CellExplainException(CellExplainException.Code.VALIDATION_ERROR, "position required");
        }
        if (!position.isValid()) {
            throw new CellExplainException(
                    CellExplainException.Code.VALIDATION_ERROR,
                    "position must be non-negative, got " + position.row() + ":" + position.column());
        }
        if (thinQueryService == null) {
            throw new CellExplainException(
                    CellExplainException.Code.EXPLAIN_FAILED, "explain service is not wired (no query service)");
        }

        Resolved resolved = resolve(queryName);
        CellsetView view = viewFor(resolved.cellSet);
        int row = position.row();
        int column = position.column();
        if (!view.inBounds(row, column)) {
            throw new CellExplainException(
                    CellExplainException.Code.VALIDATION_ERROR,
                    "position " + row + ":" + column + " is outside the cellset (" + view.rowCount() + " rows x "
                            + view.columnCount() + " columns)");
        }

        SaikuCube cube = resolved.query.getCube();
        List<CellMember> rowHeader = view.rowHeader(row);
        List<CellMember> columnHeader = view.columnHeader(column);
        CellMember measure = view.measure();
        List<CellMember> coordinate = new ArrayList<>(rowHeader);
        coordinate.addAll(columnHeader);

        CellExplainResult result = new CellExplainResult();
        if (cube == null) {
            // A cellset can only reach here from a session query, which always has a cube; if that
            // ever stops being true the panel still explains the cell, it just can't re-run it.
            result.getNotes().add("no cube on the session query, so the cell MDX and SQL are unavailable");
        }
        result.setCube(cube == null ? null : cube.getUniqueName());
        result.setMeasure(measure == null ? null : measure.caption());
        result.setRowPath(path(rowHeader));
        result.setColumnPath(path(columnHeader, true));
        result.setValue(view.value(row, column));
        result.setFormatted(view.formatted(row, column));
        result.setMdx(StringUtils.trimToNull(resolved.query.getMdx()));

        String cellMdx = CellMdx.cellQuery(fromReference(cube, resolved.query.getMdx()), coordinate);
        result.setCellMdx(cellMdx);

        if (request.isIncludeDrivers()) {
            result.setDrivers(CellDrivers.analyze(view, row, column));
        }
        if (StringUtils.isBlank(cellMdx)) {
            result.getNotes().add("no usable member on this cell, so no cell query could be built");
        } else if (request.isIncludeSql()) {
            Sql sql = captureSql(cube, cellMdx);
            result.setSql(sql.statement);
            result.getNotes().addAll(sql.notes);
        }

        if (request.isIncludeNarrative()) {
            narrate(result, cube, measure, rowHeader, columnHeader);
        } else {
            result.setNarrativeSource(CellExplainResult.NarrativeSource.NONE);
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }

    /** The executed cellset for a named query, or a caller-fixable failure. */
    private Resolved resolve(String queryName) {
        QueryContext context = thinQueryService.getContext(queryName);
        if (context == null) {
            throw new CellExplainException(
                    CellExplainException.Code.UNKNOWN_QUERY, "no query named '" + queryName + "' in this session");
        }
        ThinQuery query;
        try {
            query = context.getOlapQuery();
        } catch (RuntimeException e) {
            // QueryContext throws rather than returning null when the query object is absent.
            throw new CellExplainException(
                    CellExplainException.Code.UNKNOWN_QUERY, "query '" + queryName + "' has no query object", e);
        }
        CellSet cellSet = context.getOlapResult();
        if (cellSet == null) {
            throw new CellExplainException(
                    CellExplainException.Code.NOT_EXECUTED, "query '" + queryName + "' has not been executed");
        }
        return new Resolved(query, cellSet);
    }

    /**
     * How the service reads an executed cellset. A seam rather than a hard {@code new} so a unit
     * test can hand in a {@link CellsetView} fake rather than a live olap4j {@link CellSet};
     * production always returns the olap4j adapter.
     */
    protected CellsetView viewFor(CellSet cellSet) {
        return new Olap4jCellsetView(cellSet);
    }

    /**
     * FROM clause for the cell query: whatever the parent query used (that's authoritative — it
     * already resolved), else the cube name.
     */
    private String fromReference(SaikuCube cube, String parentMdx) {
        String from = CellMdx.fromOf(parentMdx);
        if (from != null) {
            return from;
        }
        return cube == null ? null : "[" + cube.getName() + "]";
    }

    /**
     * Run the cell query with a {@link SqlCaptureProbe} open and take the statement it emitted.
     *
     * <p>Best-effort by design: the panel is more useful with MDX and no SQL than it is broken, so
     * every failure becomes a note and a {@code null} SQL. A cell query is a single aggregate over
     * one point, which is why this is affordable at all — the parent cellset is never re-run.
     */
    private Sql captureSql(SaikuCube cube, String cellMdx) {
        List<String> notes = new ArrayList<>();
        if (olapDiscoverService == null) {
            notes.add("no discover service wired, so the generated SQL was not captured");
            return new Sql(null, notes);
        }
        if (cube == null || StringUtils.isBlank(cube.getConnection())) {
            notes.add("cell has no connection, so the generated SQL was not captured");
            return new Sql(null, notes);
        }
        OlapConnection connection = null;
        OlapStatement statement = null;
        SqlCaptureProbe probe = SqlCaptureProbe.open();
        try {
            connection = olapDiscoverService.getNativeConnection(cube.getConnection());
            if (StringUtils.isNotBlank(cube.getCatalog())) {
                connection.setCatalog(cube.getCatalog());
            }
            statement = connection.createStatement();
            statement.executeOlapQuery(cellMdx);
            String captured = probe.representativeStatement();
            if (captured == null) {
                // Never a silently absent field: the panel would otherwise look as if SQL were
                // simply not offered. A warm Mondrian cache answers the cell without any SQL.
                notes.add("SQL was not captured: no statement was logged for this cell"
                        + " (it may have been answered from Mondrian's cache)");
            }
            return new Sql(captured, notes);
        } catch (OlapException | RuntimeException e) {
            // Note carries the class, not the message: planner messages quote the whole MDX and
            // member names, which this panel is about to display anyway, but the log should stay
            // quiet about a cell the user was merely curious about.
            log.debug("cell explain: SQL capture failed for query '{}'", cube.getName(), e);
            notes.add("the cell query could not be re-run for SQL capture ("
                    + e.getClass().getSimpleName() + ")");
            return new Sql(null, notes);
        } finally {
            closeQuietly(statement);
            closeQuietly(connection);
            probe.close();
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug(
                    "cell explain: failed to close {} on the way out",
                    closeable.getClass().getSimpleName(),
                    e);
        }
    }

    /**
     * The narrative, preferring the LLM. Whatever happens here, {@code result} ends with a
     * narrative a human can read and a {@code narrativeSource} that says who wrote it.
     */
    private void narrate(
            CellExplainResult result,
            SaikuCube cube,
            CellMember measure,
            List<CellMember> rowHeader,
            List<CellMember> columnHeader) {
        String computed = CellNarrative.describe(
                measure, rowHeader, columnHeader, result.getValue(), result.getFormatted(), result.getDrivers());
        result.setNarrative(computed);
        result.setNarrativeSource(CellExplainResult.NarrativeSource.COMPUTED);

        if (askService == null || !askService.isConfigured() || cube == null) {
            return;
        }
        try {
            AiAskService.AskOutcome outcome = askService.ask(
                    new AiCubeRef(cube.getConnection(), cube.getCatalog(), cube.getSchema(), cube.getName()),
                    question(measure, rowHeader, columnHeader, result),
                    List.of(),
                    CellNarrative.facts(
                            measure,
                            result.getFormatted(),
                            result.getRowPath(),
                            result.getColumnPath(),
                            result.getDrivers()),
                    NlAskRequest.ForceTool.INSIGHT,
                    null);
            if (outcome == null || outcome.degraded() || outcome.insight() == null) {
                result.getNotes().add("LLM narrative unavailable: " + reason(outcome));
                return;
            }
            String markdown = outcome.insight().getMarkdown();
            if (StringUtils.isBlank(markdown)) {
                result.getNotes().add("LLM returned an empty narrative");
                return;
            }
            result.setNarrative(markdown);
            result.setNarrativeSource(CellExplainResult.NarrativeSource.LLM);
            result.setModel(outcome.model());
        } catch (RuntimeException e) {
            log.debug("cell explain: LLM narrative failed", e);
            result.getNotes()
                    .add("LLM narrative failed (" + e.getClass().getSimpleName() + "); showing the computed one");
        }
    }

    private String reason(AiAskService.AskOutcome outcome) {
        if (outcome == null) {
            return "no outcome";
        }
        return StringUtils.isBlank(outcome.reason()) ? "provider returned no narrative" : outcome.reason();
    }

    private String question(
            CellMember measure, List<CellMember> rowHeader, List<CellMember> columnHeader, CellExplainResult result) {
        String what = measure == null ? "This cell" : measure.caption();
        return "Explain this number in two or three sentences: " + what + " for " + path(rowHeader) + " / "
                + path(columnHeader, true) + " is " + (result.getFormatted() == null ? "?" : result.getFormatted())
                + ". Name the dimension members responsible, using only the figures above. "
                + "If no clear driver is visible, say so.";
    }

    private static String path(List<CellMember> header) {
        return path(header, false);
    }

    private static String path(List<CellMember> header, boolean dropMeasure) {
        List<String> parts = new ArrayList<>();
        for (CellMember member : header) {
            if (dropMeasure && member.measure()) {
                continue;
            }
            parts.add(member.caption());
        }
        return parts.isEmpty() ? "the grid" : String.join(" / ", parts);
    }

    /** The pair the context gives us: what was asked, and what it answered. */
    private record Resolved(ThinQuery query, CellSet cellSet) {}

    private record Sql(String statement, List<String> notes) {}
}
