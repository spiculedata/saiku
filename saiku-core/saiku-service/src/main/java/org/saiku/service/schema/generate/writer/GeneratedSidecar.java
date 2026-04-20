package org.saiku.service.schema.generate.writer;

import java.time.Instant;
import java.util.List;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Value type representing the {@code <schemaName>.generated.json} sidecar written alongside a
 * Mondrian schema XML by {@link MondrianSchemaWriter#writeWithSidecar(DraftSchema, List)}.
 *
 * <p>The sidecar captures the inputs to the generator run so we can later re-open a generated
 * schema for continued review (draft tree + applied op log). Serialisation is handled by {@link
 * GeneratedSidecarIo}.
 */
public record GeneratedSidecar(
        String schemaName, Instant generatedAt, String saikuVersion, DraftSchema draft, List<SuggestionOp> opLog) {

    public GeneratedSidecar {
        opLog = opLog == null ? List.of() : List.copyOf(opLog);
    }
}
