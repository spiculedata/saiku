/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.olap.ai.AiPolicy;
import org.saiku.service.olap.ai.AiPolicyGuard;

/**
 * saiku#1554 — LLM-egress coverage for the Ossie {@code /ai/ossie/ask} path. Verifies the dedicated
 * egress guard strips the schema's raw column sample VALUES before they cross to a third-party LLM
 * vendor, while schema metadata always flows. Mirrors the MDX {@code AiAskServiceTest} egress tests.
 *
 * <p>The tests exercise {@code buildUserMessage} + {@link OssieAiSchema#toAgentView(boolean)}
 * directly, so no live LLM provider / network round-trip is needed.
 */
public class OssieAiAskServiceTest {

    /** A one-field schema whose sole field carries two distinct sample values. */
    private static OssieAiSchema schemaWithSamples() {
        OssieAiSchema schema = new OssieAiSchema();
        schema.setConnectionName("conn");
        schema.setModelName("model");

        OssieAiSchema.Field city = new OssieAiSchema.Field();
        city.setName("city");
        city.setType("VARCHAR");
        city.setSampleValues(new ArrayList<>(Arrays.asList("Springfield", "Shelbyville")));

        OssieAiSchema.Dataset customers = new OssieAiSchema.Dataset();
        customers.setName("customers");
        Map<String, OssieAiSchema.Field> fields = new LinkedHashMap<>();
        fields.put("city", city);
        customers.setFields(fields);

        Map<String, OssieAiSchema.Dataset> datasets = new LinkedHashMap<>();
        datasets.put("customers", customers);
        schema.setDatasets(datasets);
        return schema;
    }

    private static String message(OssieAiAskService svc, OssieAiSchema schema) throws Exception {
        return svc.buildUserMessage("how many by city?", schema, "conn", "model");
    }

    // ---------------- toAgentView projection ----------------

    @Test
    public void toAgentViewIncludeReturnsSameInstance() {
        OssieAiSchema schema = schemaWithSamples();
        assertSame("include=true is a no-op passthrough", schema, schema.toAgentView(true));
    }

    @Test
    public void toAgentViewExcludeStripsSampleValuesWithoutMutatingOriginal() {
        OssieAiSchema schema = schemaWithSamples();
        OssieAiSchema view = schema.toAgentView(false);

        List<String> viewSamples =
                view.getDatasets().get("customers").getFields().get("city").getSampleValues();
        assertTrue("sample values must be stripped in the projected view", viewSamples.isEmpty());

        // The projector caches + shares sample-value lists across requests — the original must be
        // left untouched by the projection.
        List<String> originalSamples =
                schema.getDatasets().get("customers").getFields().get("city").getSampleValues();
        assertTrue("original sample values must survive (no in-place mutation)", originalSamples.size() == 2);

        // Metadata (field name) survives the projection.
        assertTrue(view.getDatasets().get("customers").getFields().containsKey("city"));
    }

    // ---------------- egress gate through buildUserMessage ----------------

    @Test
    public void egressUnwiredFailsClosedAndStripsSampleValues() throws Exception {
        // No setEgressGuard() → guard is null → fail-closed: raw sample values must NOT egress.
        OssieAiAskService svc = new OssieAiAskService();
        String msg = message(svc, schemaWithSamples());

        assertFalse("unwired egress guard must fail closed and strip sample values", msg.contains("Springfield"));
        assertFalse(msg.contains("Shelbyville"));
        assertTrue("schema metadata (field name) must still flow", msg.contains("city"));
    }

    @Test
    public void egressSchemaOnlyStripsSampleValuesButMetadataFlows() throws Exception {
        OssieAiAskService svc = new OssieAiAskService();
        svc.setEgressGuard(new AiPolicyGuard(AiPolicy.SCHEMA_ONLY));
        String msg = message(svc, schemaWithSamples());

        assertFalse("schema-only egress must strip raw sample values", msg.contains("Springfield"));
        assertTrue("schema metadata must still flow at schema-only", msg.contains("city"));
    }

    @Test
    public void egressAggregatedPassesSampleValuesThrough() throws Exception {
        OssieAiAskService svc = new OssieAiAskService();
        svc.setEgressGuard(new AiPolicyGuard(AiPolicy.AGGREGATED));
        String msg = message(svc, schemaWithSamples());

        assertTrue("aggregated egress must pass sample values through", msg.contains("Springfield"));
        assertTrue(msg.contains("Shelbyville"));
        assertTrue(msg.contains("city"));
    }
}
