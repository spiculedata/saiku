/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.dto.resultset.MemberCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.olap.ai.ask.AiAskService;
import org.saiku.service.olap.ai.ask.NlAskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link EvalAskAdapter} — plugs {@link AiAskService} plus the query executor into
 * the eval runner so CI can run a suite against a real launcher with a real LLM provider
 * (saiku#1424 follow-up).
 *
 * <p>Given a case's cube ref + question + history, the adapter:
 *
 * <ol>
 *   <li>Calls {@link AiAskService#ask} to route the ask and get the LLM's structured output.
 *   <li>For {@link AiAskService.AskOutcome.Kind#QUERY} — converts the {@link AiQueryRequest} via
 *       {@link AiSchemaConverter}, executes through {@link ThinQueryService}, and flattens the
 *       resulting {@link CellDataSet} into the neutral {@code List<Map<String, Object>>} the
 *       {@link RowComparator} expects.
 *   <li>For INSIGHT / VIEW_CHANGE / REFUSED — projects the outcome directly onto the neutral
 *       {@link EvalAskResult} without hitting the query executor.
 * </ol>
 *
 * <p>Records-format flattening is deliberately simpler than the resource's {@code buildResponse}
 * (~200 lines with matrix support, measures-only shape, and k-anonymity suppression). The
 * comparator does key normalisation, so this class doesn't repeat the resource's caption-caching
 * logic; every row is projected as {@code {rowHeaderCaption: memberString, dataColCaption: number}}
 * which is exactly what the eval framework compares. Follow-up work can extract {@code
 * buildResponse} into a shared helper if a subtle behavioural difference between the AI ask
 * response and the eval extractor surfaces in practice.
 */
public final class LiveEvalAskAdapter implements EvalAskAdapter {

    private static final Logger log = LoggerFactory.getLogger(LiveEvalAskAdapter.class);

    private final AiAskService askService;
    private final AiCubeMetadataService metadataService;
    private final AiSchemaConverter converter;
    private final ThinQueryService thinQueryService;

    public LiveEvalAskAdapter(
            AiAskService askService,
            AiCubeMetadataService metadataService,
            AiSchemaConverter converter,
            ThinQueryService thinQueryService) {
        this.askService = Objects.requireNonNull(askService, "askService");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.thinQueryService = Objects.requireNonNull(thinQueryService, "thinQueryService");
    }

    @Override
    public EvalAskResult ask(AiCubeRef cube, String question, List<Map<String, String>> history) {
        List<NlAskMessage> nlHistory = toNlHistory(history);
        AiAskService.AskOutcome outcome;
        try {
            outcome = askService.ask(cube, question, nlHistory);
        } catch (RuntimeException e) {
            log.warn("live eval adapter: ask failed for cube {}", cube, e);
            return EvalAskResult.forDegraded(
                    null, "ask failure: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (outcome.degraded()) {
            String reason = outcome.reason() == null ? "provider degraded" : outcome.reason();
            // Refusal is a routed intent, not a real degradation — the tool-choice picked
            // `refuse_off_topic` and the ask service surfaces that with an `OFF_TOPIC: ...`
            // reason prefix. Map it back to REFUSED so the eval framework's expectedIntent match
            // works cleanly.
            if (reason.startsWith("OFF_TOPIC:")) {
                return EvalAskResult.forRefusal(
                        outcome.model(), reason.substring("OFF_TOPIC:".length()).trim());
            }
            return EvalAskResult.forDegraded(outcome.model(), reason);
        }

        AiAskService.AskOutcome.Kind kind = outcome.kind();
        if (kind == null) {
            return EvalAskResult.forDegraded(outcome.model(), "provider returned no intent");
        }

        return switch (kind) {
            case QUERY -> handleQuery(outcome);
            case INSIGHT -> EvalAskResult.forInsight(
                    outcome.model(),
                    outcome.insight() == null ? "" : outcome.insight().getMarkdown());
            case VIEW_CHANGE -> EvalAskResult.forViewChange(
                    outcome.model(),
                    outcome.viewChange() == null ? "" : outcome.viewChange().getViewMode());
        };
    }

    private EvalAskResult handleQuery(AiAskService.AskOutcome outcome) {
        AiQueryRequest req = outcome.request();
        if (req == null) {
            return EvalAskResult.forDegraded(outcome.model(), "QUERY outcome missing request payload");
        }
        try {
            AiSchema schema = metadataService.getSchema(req.getCube());
            ThinQuery tq = converter.convert(req, schema);
            CellDataSet cds = thinQueryService.execute(tq);
            List<Map<String, Object>> rows = flattenToRecords(cds);
            return EvalAskResult.forQuery(outcome.model(), rows);
        } catch (RuntimeException e) {
            log.warn("live eval adapter: QUERY execution failed after successful translation", e);
            return EvalAskResult.forDegraded(
                    outcome.model(), "execute failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Flatten a {@link CellDataSet} into records format — one map per body row, keyed by column
     * caption. Row-header columns land as their formatted-value string; data columns land as the
     * numeric {@link DataCell#getRawNumber()} when the cell carries a raw value, falling back to
     * the formatted string otherwise. This mirrors what the eval comparator's numeric parser
     * expects — a numeric value if the schema knows one, else a display string it can compare as
     * text.
     *
     * <p>Simpler than the resource's {@code buildResponse} — no matrix mode, no measures-only
     * flattening, no k-anonymity. Package-visible so a targeted test can exercise the cellset →
     * records shape without spinning up an ask service.
     */
    static List<Map<String, Object>> flattenToRecords(CellDataSet cds) {
        if (cds == null) return List.of();
        AbstractBaseCell[][] headers = cds.getCellSetHeaders();
        AbstractBaseCell[][] body = cds.getCellSetBody();
        if (body == null || body.length == 0) return List.of();

        int totalWidth = body[0] == null ? 0 : body[0].length;
        int rowHeaderCount = countRowHeaderColumns(body);

        List<String> columnCaptions = extractColumnCaptions(headers, rowHeaderCount, totalWidth);
        List<String> rowHeaderCaptions = extractRowHeaderCaptions(headers, rowHeaderCount);

        List<Map<String, Object>> records = new ArrayList<>(body.length);
        for (AbstractBaseCell[] row : body) {
            if (row == null) continue;
            Map<String, Object> record = new LinkedHashMap<>();
            // Row-header columns first — kept as their formatted string.
            for (int c = 0; c < rowHeaderCount && c < row.length; c++) {
                String key = c < rowHeaderCaptions.size() ? rowHeaderCaptions.get(c) : ("row" + c);
                record.put(key, row[c] == null ? "" : safeText(row[c].getFormattedValue()));
            }
            // Data cells — prefer the numeric raw value; fall back to the formatted string.
            for (int c = rowHeaderCount; c < totalWidth && c < row.length; c++) {
                int colIdx = c - rowHeaderCount;
                String colKey = colIdx < columnCaptions.size() ? columnCaptions.get(colIdx) : ("col" + colIdx);
                Object value = cellToValue(row[c]);
                record.put(colKey, value);
            }
            records.add(record);
        }
        return records;
    }

    /**
     * Pull a comparable value out of a body cell. Data cells with a raw {@link Number} preserve
     * their type; text-only rows fall back to the formatted string.
     */
    private static Object cellToValue(AbstractBaseCell cell) {
        if (cell == null) return "";
        if (cell instanceof DataCell dc) {
            Number raw = dc.getRawNumber();
            if (raw != null) return raw;
            return safeText(dc.getFormattedValue());
        }
        return safeText(cell.getFormattedValue());
    }

    /**
     * Count the row-header columns — the leftmost columns whose entire body column is
     * {@link MemberCell}. Handles cubes with no rows axis (all body columns are data) by returning
     * zero.
     */
    private static int countRowHeaderColumns(AbstractBaseCell[][] body) {
        if (body == null || body.length == 0 || body[0] == null) return 0;
        int width = body[0].length;
        int count = 0;
        for (int c = 0; c < width; c++) {
            boolean allMembers = true;
            for (AbstractBaseCell[] row : body) {
                if (row == null || c >= row.length) continue;
                if (!(row[c] instanceof MemberCell)) {
                    allMembers = false;
                    break;
                }
            }
            if (allMembers) count++;
            else break;
        }
        return count;
    }

    private static List<String> extractColumnCaptions(
            AbstractBaseCell[][] headers, int rowHeaderCount, int totalWidth) {
        List<String> out = new ArrayList<>();
        if (headers == null || headers.length == 0 || totalWidth == 0) return out;
        AbstractBaseCell[] lastHeader = headers[headers.length - 1];
        for (int c = rowHeaderCount; c < totalWidth; c++) {
            String caption =
                    c < lastHeader.length && lastHeader[c] != null ? safeText(lastHeader[c].getFormattedValue()) : "";
            if (caption.isEmpty()) caption = "col" + (c - rowHeaderCount);
            out.add(caption);
        }
        return out;
    }

    private static List<String> extractRowHeaderCaptions(AbstractBaseCell[][] headers, int rowHeaderCount) {
        List<String> out = new ArrayList<>();
        if (headers == null || headers.length == 0) return out;
        AbstractBaseCell[] lastHeader = headers[headers.length - 1];
        for (int c = 0; c < rowHeaderCount && c < lastHeader.length; c++) {
            String cap = lastHeader[c] == null ? "" : safeText(lastHeader[c].getFormattedValue());
            if (cap.isEmpty()) cap = "row" + c;
            out.add(cap);
        }
        return out;
    }

    private static String safeText(String s) {
        return s == null ? "" : s;
    }

    /**
     * Convert the case's {@code List<Map<String, String>>} history (YAML shape — string / string)
     * into {@link NlAskMessage}s the ask service consumes. Skips entries missing {@code role} or
     * {@code content} rather than throwing — an eval case with a garbled history block should
     * degrade cleanly, not crash the run.
     */
    private static List<NlAskMessage> toNlHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<NlAskMessage> out = new ArrayList<>();
        for (Map<String, String> entry : history) {
            if (entry == null) continue;
            String role = entry.get("role");
            String content = entry.get("content");
            if (role == null || content == null) continue;
            String lc = role.toLowerCase(Locale.ROOT);
            if ("user".equals(lc)) out.add(NlAskMessage.user(content));
            else if ("assistant".equals(lc)) out.add(NlAskMessage.assistant(content));
            // Ignore unknown roles ("system", garbage) — the ask service only handles the two.
        }
        return out;
    }
}
