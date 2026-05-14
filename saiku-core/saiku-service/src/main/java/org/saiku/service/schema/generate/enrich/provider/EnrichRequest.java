package org.saiku.service.schema.generate.enrich.provider;

import java.util.List;
import java.util.Map;
import org.saiku.service.schema.generate.draft.DraftSchema;

/**
 * Input to an {@link LlmProvider}.
 *
 * <p>{@code draft} is the schema to enrich. {@code columnSamples} is an optional map from a
 * provider-defined column key (typically {@code tableName.columnName}) to a small list of sampled
 * values; remote providers may use these for type inference. {@code maxSuggestions} is a soft upper
 * bound — providers may return fewer and should not exceed it unless they have a good reason.
 *
 * <p>Records are Jackson-serialisable so this request can be transmitted over HTTP by providers
 * that delegate to an external LLM.
 */
public record EnrichRequest(DraftSchema draft, Map<String, List<String>> columnSamples, int maxSuggestions) {

    public EnrichRequest {
        if (draft == null) {
            throw new NullPointerException("draft");
        }
        columnSamples = columnSamples == null ? Map.of() : Map.copyOf(columnSamples);
        if (maxSuggestions < 0) {
            maxSuggestions = 0;
        }
    }
}
