/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.enrich.provider;

import static org.junit.Assert.assertNotNull;

import java.time.Duration;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;

/**
 * Opt-in contract test against a live OpenAI-compatible endpoint. Skipped unless {@code
 * OPENAI_API_KEY} is set (or, for a local Ollama, {@code OPENAI_COMPAT_TEST_ENDPOINT} — no key
 * required). Asserts response SHAPE only — not content — so a model refusal or an empty-ops
 * response still passes.
 */
public class OpenAiCompatProviderContractTest {

    @Test
    public void contractShapeRoundTrip() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String localEndpoint = System.getenv("OPENAI_COMPAT_TEST_ENDPOINT");
        Assume.assumeTrue(
                "Set OPENAI_API_KEY (hosted OpenAI) or OPENAI_COMPAT_TEST_ENDPOINT (local Ollama) to run this test",
                (apiKey != null && !apiKey.isBlank()) || (localEndpoint != null && !localEndpoint.isBlank()));

        boolean useLocal = localEndpoint != null && !localEndpoint.isBlank();
        String key = useLocal ? "ollama" : apiKey;
        String model = useLocal ? "llama3.1" : OpenAiCompatProvider.DEFAULT_MODEL;
        String endpoint = useLocal ? localEndpoint : OpenAiCompatProvider.DEFAULT_ENDPOINT;

        // Low max_tokens to keep the contract test cheap.
        OpenAiCompatProvider.Config cfg =
                new OpenAiCompatProvider.Config(key, model, endpoint, 0.0, 2048, Duration.ofSeconds(60));
        OpenAiCompatProvider provider = new OpenAiCompatProvider(cfg);

        Provenance rule = new Provenance(Provenance.Source.RULE, "rule:test", 1.0);
        DraftSchema draft = new DraftSchema("test");
        DraftCube cube = new DraftCube("sales", "sales", rule);
        cube.dimensions().add(new DraftDimension("customer_id", DraftDimension.Type.STANDARD, rule));
        cube.measures().add(new DraftMeasure("amount", "amount", DraftMeasure.Aggregator.SUM, rule));
        draft.cubes().add(cube);

        EnrichRequest req = new EnrichRequest(draft, Map.of(), 10);
        EnrichResponse resp = provider.enrich(req);

        assertNotNull(resp);
        assertNotNull(resp.suggestions());
        assertNotNull(resp.suggestions().ops());
        // Content not asserted — shape only.
    }
}
