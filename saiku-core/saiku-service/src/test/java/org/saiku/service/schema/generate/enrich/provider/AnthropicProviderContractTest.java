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
 * Opt-in contract test against the live Anthropic API. Skipped unless {@code ANTHROPIC_API_KEY} is
 * set. Asserts response SHAPE only — not content — so a model refusal or an empty-ops response
 * still passes.
 */
public class AnthropicProviderContractTest {

    @Test
    public void contractShapeRoundTrip() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        Assume.assumeNotNull(apiKey);
        Assume.assumeTrue(!apiKey.isBlank());

        // Low max_tokens to keep the contract test cheap.
        AnthropicProvider.Config cfg = new AnthropicProvider.Config(
                apiKey, AnthropicProvider.DEFAULT_MODEL, 0.0, 2048, Duration.ofSeconds(60));
        AnthropicProvider provider = new AnthropicProvider(cfg);

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
