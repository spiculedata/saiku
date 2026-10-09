/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiDataKind;
import org.saiku.service.olap.ai.AiPolicyGuard;
import org.saiku.service.olap.ai.AiRequestJsonSchema;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.ask.AiInsight;
import org.saiku.service.olap.ai.ask.NlAskProvider;
import org.saiku.service.olap.ai.ask.NlAskRequest;
import org.saiku.service.olap.ai.ask.NlAskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LLM narrator (saiku#1119 phase 2): the structured deltas are handed to the configured ask
 * provider with a short narration prompt, and the model's {@code emit_insight} markdown is reduced to
 * at most {@code maxBullets} bullets.
 *
 * <p><b>Grounding.</b> The prompt is built from the deltas the digest already resolved — never from a
 * model-authored query — so the model can only describe figures the server computed. The cube schema is
 * passed as the PII-filtered agent view (the same {@code schema.toAgentView()} the {@code /ai/ask}
 * surface uses), and the {@code INSIGHT} tool is forced so no query can be emitted and nothing is
 * executed.
 *
 * <p><b>Egress gate.</b> The deltas are business figures, so this narrator refuses to send them when the
 * dedicated LLM-egress guard does not permit {@link AiDataKind#AGGREGATED_RESULT_VALUES} — including
 * when the guard is unwired, which fails closed. It then degrades to {@link TemplateDigestNarrator},
 * which sends nothing anywhere.
 *
 * <p><b>Degradation is silent and total.</b> A missing provider, a schema that will not load, a
 * transport error, a refusal, or an empty insight all fall back to the template bullets. The digest is
 * never withheld because narration failed; only the wording differs.
 */
public final class NlAskDigestNarrator implements DigestNarrator {

    private static final Logger log = LoggerFactory.getLogger(NlAskDigestNarrator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The schema source — narrowed to what this narrator needs, so tests can supply a stub. */
    @FunctionalInterface
    public interface CubeSchemaSource {
        /** The live schema for {@code cube}. Throws whatever the underlying service throws. */
        AiSchema schemaFor(AiCubeRef cube);
    }

    private final NlAskProvider provider;
    private final CubeSchemaSource schemaSource;
    private final AiPolicyGuard egressGuard;
    private final DigestNarrator fallback = new TemplateDigestNarrator();

    /**
     * Production constructor — the singleton cube-metadata service supplies the schema.
     *
     * <p>The ONLY public constructor, deliberately: Spring's XML wiring picks a constructor by
     * argument count, and a second equally-shaped one would make the choice ambiguous.
     */
    public NlAskDigestNarrator(
            NlAskProvider provider, AiCubeMetadataService metadataService, AiPolicyGuard egressGuard) {
        this(provider, requireMetadataSource(metadataService), egressGuard);
    }

    /** Test seam: build a narrator over any schema source. */
    static NlAskDigestNarrator forSchemaSource(
            NlAskProvider provider, CubeSchemaSource schemaSource, AiPolicyGuard egressGuard) {
        return new NlAskDigestNarrator(provider, schemaSource, egressGuard);
    }

    private NlAskDigestNarrator(NlAskProvider provider, CubeSchemaSource schemaSource, AiPolicyGuard egressGuard) {
        if (provider == null || schemaSource == null) {
            throw new IllegalArgumentException("provider and schemaSource are required");
        }
        this.provider = provider;
        this.schemaSource = schemaSource;
        // A null guard is the fail-closed posture: canSend() below denies on it, so no figures leave.
        this.egressGuard = egressGuard;
    }

    private static CubeSchemaSource requireMetadataSource(AiCubeMetadataService metadataService) {
        if (metadataService == null) {
            throw new IllegalArgumentException("metadataService is required");
        }
        return metadataService::getSchema;
    }

    @Override
    public List<String> narrate(String dashboardTitle, List<MeasureDelta> deltas, int maxBullets) {
        int cap = Math.max(1, maxBullets);
        if (deltas == null || deltas.isEmpty()) {
            return List.of();
        }
        if (!provider.isConfigured()) {
            return fallback.narrate(dashboardTitle, deltas, cap);
        }
        if (!egressPermits()) {
            log.info("Digest narration skipped: LLM egress policy withholds aggregates; using template bullets");
            return fallback.narrate(dashboardTitle, deltas, cap);
        }

        AiCubeRef anchor = deltas.get(0).cube();
        if (anchor == null) {
            return fallback.narrate(dashboardTitle, deltas, cap);
        }
        String schemaJson;
        try {
            // Agent view = PII-filtered (saiku#902): captions/samples of PII-tagged axes never cross.
            schemaJson =
                    MAPPER.writeValueAsString(schemaSource.schemaFor(anchor).toAgentView());
        } catch (Exception e) {
            // Schema unavailable (connection down, cube renamed, the schema's own policy refusal).
            // Grounding would be weaker, so narrate from the deltas alone via the template rather than
            // sending an ungrounded prompt — and never propagate: a digest is not worth failing a run.
            log.warn(
                    "Digest narration skipped: cube schema unavailable ({}); using template bullets",
                    e.getClass().getSimpleName());
            return fallback.narrate(dashboardTitle, deltas, cap);
        }

        NlAskRequest request = new NlAskRequest(
                anchor,
                prompt(dashboardTitle, deltas, cap),
                schemaJson,
                serializeRequestSchema(),
                List.of(),
                digestTable(dashboardTitle, deltas),
                NlAskRequest.ForceTool.INSIGHT,
                null,
                null);

        NlAskResponse response = provider.ask(request);
        if (response == null || response.degraded()) {
            log.info(
                    "Digest narration degraded ({}); using template bullets",
                    response == null ? "no response" : response.reason());
            return fallback.narrate(dashboardTitle, deltas, cap);
        }
        if (response.kind() != NlAskResponse.Kind.INSIGHT) {
            log.info("Digest narration returned an unexpected tool ({}); using template bullets", response.kind());
            return fallback.narrate(dashboardTitle, deltas, cap);
        }
        List<String> bullets = toBullets(response.payloadJson(), cap);
        if (bullets.isEmpty()) {
            return fallback.narrate(dashboardTitle, deltas, cap);
        }
        return bullets;
    }

    /** Fail-closed: an unwired guard denies, so nothing is sent without an explicit policy decision. */
    private boolean egressPermits() {
        return egressGuard != null && egressGuard.canSend(AiDataKind.AGGREGATED_RESULT_VALUES);
    }

    private static String serializeRequestSchema() {
        try {
            return MAPPER.writeValueAsString(AiRequestJsonSchema.forRequest());
        } catch (Exception e) {
            // The request schema is a constant object; failing to serialise it is a programming error,
            // and a blank value is what the record's own validation demands.
            return "{}";
        }
    }

    /** The narration prompt: capped, citation-demanding, and explicitly preamble-free. */
    static String prompt(String dashboardTitle, List<MeasureDelta> deltas, int maxBullets) {
        String title = (dashboardTitle == null || dashboardTitle.isBlank()) ? "this dashboard" : dashboardTitle;
        return "Write " + maxBullets + " bullet points describing what changed on '" + title
                + "' since the previous period, using ONLY the figures in the data below. "
                + "Name the specific measures you cite and their values. "
                + "Output a markdown bullet list and nothing else: no preamble, no heading, no closing line.";
    }

    /**
     * The grounded data block, given to the model as the cellset digest: one row per delta with both
     * period values and the computed change, so the model reports deltas rather than re-deriving them
     * (and cannot get the arithmetic wrong).
     */
    static String digestTable(String dashboardTitle, List<MeasureDelta> deltas) {
        StringBuilder sb = new StringBuilder();
        if (dashboardTitle != null && !dashboardTitle.isBlank()) {
            sb.append("Dashboard: ").append(dashboardTitle).append('\n');
        }
        sb.append("| Measure | Current period | Previous period | Change |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (MeasureDelta d : deltas) {
            sb.append("| ")
                    .append(d.label())
                    .append(" | ")
                    .append(d.formattedCurrent())
                    .append(" | ")
                    .append(d.formattedPrevious())
                    .append(" | ")
                    .append(d.formattedChange());
            String pct = d.formattedPercent();
            if (!pct.isEmpty()) {
                sb.append(" (").append(pct).append(')');
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    /**
     * Reduce the {@code emit_insight} markdown to bullets.
     *
     * <p>Markdown bullets ({@code -}, {@code *}, {@code +}) and {@code 1.}-style numbered items are
     * both accepted, because models pick either. Lines that are not bullets (a headline, a stray
     * sentence) are ignored rather than smuggled into the email — a bullet list the server did not
     * structure is the one shape that renders predictably. Returns empty when nothing usable came back,
     * which the caller turns into the template fallback.
     */
    static List<String> toBullets(String insightJson, int maxBullets) {
        if (insightJson == null || insightJson.isBlank()) {
            return List.of();
        }
        AiInsight insight;
        try {
            insight = MAPPER.readValue(insightJson, AiInsight.class);
        } catch (Exception e) {
            return List.of();
        }
        if (insight == null || insight.getMarkdown() == null) {
            return List.of();
        }
        int cap = Math.max(1, maxBullets);
        List<String> out = new ArrayList<>();
        for (String rawLine : insight.getMarkdown().split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String bullet = stripMarker(line);
            if (bullet == null) {
                continue;
            }
            bullet = collapse(bullet);
            if (bullet.isEmpty()) {
                continue;
            }
            out.add(bullet);
            if (out.size() >= cap) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** The bullet text if {@code line} is a markdown list item, else null. */
    private static String stripMarker(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
            return line.substring(2).trim();
        }
        // Numbered list: "1. text" / "2) text" — but NOT "1.5" (no space after the marker).
        for (int marker = 1; marker <= 3; marker++) {
            if (marker >= line.length()) {
                break;
            }
            char c = line.charAt(marker);
            if ((c != '.' && c != ')') || !isAllDigits(line, 0, marker)) {
                // Not a marker at this width; a longer digit run ("12) text") may still be one.
                continue;
            }
            int after = marker + 1;
            if (after < line.length() && line.charAt(after) == ' ') {
                return line.substring(after + 1).trim();
            }
        }
        return null;
    }

    /** True when every character in {@code line[from, to)} is a digit (and the range is non-empty). */
    private static boolean isAllDigits(String line, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Flatten a bullet to one line — the email body holds one sentence per row. */
    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
