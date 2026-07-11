/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.ai;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

/**
 * Property-based fuzz tests for {@link OssieAiValidator}.
 *
 * <p>Parallel to {@code AiQueryRequestPropertyTest} on the MDX side. The Ossie AI validator sits
 * between the /ai/ossie/query endpoint and the downstream SQL translator; its job is to reject
 * requests that name non-existent datasets / fields / metrics with a structured
 * {@link OssieAiValidationException} carrying {@code field} + {@code available} lists so the
 * agent can self-correct.
 *
 * <p>Load-bearing invariants:
 * <ul>
 *   <li><b>never-throws-unexpectedly</b>: for any request shape, validator either passes silently
 *       or throws {@link OssieAiValidationException}. No NPE, ClassCastException, or bare
 *       RuntimeException should ever escape — those would translate to opaque 500s and blind the
 *       agent's self-correction loop.</li>
 *   <li><b>valid-body-passes</b>: requests built entirely from names in the schema always
 *       validate. Regression here would break every legitimate agent.</li>
 *   <li><b>unknown-name-rejects</b>: requests with names NOT in the schema always throw
 *       {@link OssieAiValidationException}. If they slipped through, the SQL translator would
 *       either NPE or (worse) emit SQL against phantom identifiers.</li>
 * </ul>
 *
 * <p>Fixed seed.
 */
public class OssieAiValidatorFuzzTest {

    private static final long SEED = 0xF00_BEEFL;

    private OssieAiValidator validator;
    private OssieAiSchema schema;

    @Before
    public void setUp() {
        validator = new OssieAiValidator();
        schema = buildFixtureSchema();
    }

    // ------------------------------------------------------------
    // 1. Validator never throws anything other than OssieAiValidationException
    // ------------------------------------------------------------

    @Test
    public void validateNeverThrowsUnexpectedExceptions() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 5000; i++) {
            OssieAiQueryRequest req = randomRequest(rng);
            try {
                validator.validate(req, schema);
            } catch (OssieAiValidationException expected) {
                // Structured rejection — invariant holds
            } catch (RuntimeException surprising) {
                fail("validator threw " + surprising.getClass().getSimpleName() + " on iteration " + i + " req="
                        + describe(req) + ": " + surprising);
                return;
            }
        }
    }

    // ------------------------------------------------------------
    // 2. Requests built entirely from schema names always validate
    // ------------------------------------------------------------

    @Test
    public void requestsBuiltFromSchemaAlwaysValidate() {
        Random rng = new Random(SEED + 1);
        for (int i = 0; i < 3000; i++) {
            OssieAiQueryRequest req = validRequest(rng);
            try {
                validator.validate(req, schema);
            } catch (OssieAiValidationException e) {
                fail("valid request rejected on iteration " + i + " field=" + e.getField() + " message="
                        + e.getMessage() + " req=" + describe(req));
            }
        }
    }

    // ------------------------------------------------------------
    // 3. Requests naming unknown datasets / fields / metrics always reject
    // ------------------------------------------------------------

    @Test
    public void requestsNamingUnknownEntitiesAlwaysReject() {
        Random rng = new Random(SEED + 2);
        for (int i = 0; i < 3000; i++) {
            // Start from a valid request, then poison exactly one field.
            // Note: 'model' is deliberately not covered — the validator gates it purely on
            // non-null / non-blank; cross-check against the schema is the caller's responsibility
            // (metadata service does it before calling validate). Poisoning it wouldn't produce
            // a rejection, which is by design.
            OssieAiQueryRequest req = validRequest(rng);
            int poison = rng.nextInt(3);
            String hostileName = HOSTILE[rng.nextInt(HOSTILE.length)];
            switch (poison) {
                case 0:
                    if (req.getRows().isEmpty()) continue;
                    req.getRows().get(0).setDataset(hostileName);
                    break;
                case 1:
                    if (req.getRows().isEmpty()) continue;
                    req.getRows().get(0).setField(hostileName);
                    break;
                default:
                    if (req.getValues().isEmpty()) continue;
                    req.getValues().get(0).setMetric(hostileName);
                    break;
            }
            // Poisoned names that happen to match a real one (through the alias tables or via
            // case-insensitive lookup) are OK — skip those iterations to avoid a false failure.
            if (nameCollides(hostileName)) continue;

            try {
                validator.validate(req, schema);
                fail("iteration " + i + " poison=" + poison + " expected rejection, got pass. req=" + describe(req));
            } catch (OssieAiValidationException expected) {
                // want
            }
        }
    }

    // ------------------------------------------------------------
    // 4. Empty / minimal shapes surface as structured rejections, not exceptions
    // ------------------------------------------------------------

    @Test
    public void emptyRequestRejectsCleanly() {
        try {
            validator.validate(new OssieAiQueryRequest(), schema);
            fail("empty request should reject");
        } catch (OssieAiValidationException expected) {
            assertNotNull(expected.getField());
        }
    }

    @Test
    public void nullRequestRejectsCleanly() {
        try {
            validator.validate(null, schema);
            fail("null request should reject");
        } catch (OssieAiValidationException expected) {
            assertNotNull(expected.getField());
        }
    }

    // ------------------------------------------------------------
    // Fixture schema — a tiny realistic Ossie model
    // ------------------------------------------------------------

    private static final String MODEL_NAME = "SALES";

    private static OssieAiSchema buildFixtureSchema() {
        OssieAiSchema s = new OssieAiSchema();
        s.setModelName(MODEL_NAME);
        s.setConnectionName("mem");
        s.setFactDataset("orders");

        addDataset(s, "orders", List.of("id", "customer_id", "amount"));
        addDataset(s, "customers", List.of("id", "region", "country"));
        addMetric(s, "revenue");
        addMetric(s, "order_count");
        return s;
    }

    private static void addDataset(OssieAiSchema schema, String name, List<String> fieldNames) {
        OssieAiSchema.Dataset ds = new OssieAiSchema.Dataset();
        ds.setName(name);
        for (String f : fieldNames) {
            OssieAiSchema.Field field = new OssieAiSchema.Field();
            field.setName(f);
            ds.getFields().put(f.toLowerCase(java.util.Locale.ROOT), field);
        }
        schema.getDatasets().put(name.toLowerCase(java.util.Locale.ROOT), ds);
    }

    private static void addMetric(OssieAiSchema schema, String name) {
        OssieAiSchema.Metric m = new OssieAiSchema.Metric();
        m.setName(name);
        schema.getMetrics().put(name.toLowerCase(java.util.Locale.ROOT), m);
    }

    // ------------------------------------------------------------
    // Random request builders
    // ------------------------------------------------------------

    private static final String[] HOSTILE = {
        "does_not_exist",
        "'; DROP TABLE users; --",
        "unknown-dataset",
        "🤖",
        "café",
        "",
        " ",
        "\n",
        "middle field name",
    };

    private static final String[] DATASETS = {"orders", "customers"};
    private static final String[] METRICS = {"revenue", "order_count"};
    private static final String[] OPS = {
        "EQ", "NEQ", "LT", "LTE", "GT", "GTE", "IN", "BETWEEN", "IS_NULL", "IS_NOT_NULL"
    };

    /** Requests built entirely from known schema entities — always valid. */
    private static OssieAiQueryRequest validRequest(Random rng) {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection("mem");
        req.setModel(MODEL_NAME);

        boolean anyShelf = false;
        int rowCount = rng.nextInt(3);
        for (int i = 0; i < rowCount; i++) {
            req.getRows().add(fieldRef("customers", rng.nextBoolean() ? "region" : "country"));
            anyShelf = true;
        }
        int valCount = rng.nextInt(METRICS.length + 1);
        for (int i = 0; i < valCount; i++) {
            OssieAiQueryRequest.MetricRef m = new OssieAiQueryRequest.MetricRef();
            m.setMetric(METRICS[rng.nextInt(METRICS.length)]);
            // Don't randomly assign an aggregation override — the validator gates overrides on the
            // metric's declared supportedOverrides list, and our fixture metrics don't declare any.
            // Aggregation-override validation gets its own dedicated coverage in the unit tests.
            req.getValues().add(m);
            anyShelf = true;
        }
        if (!anyShelf) {
            // The validator refuses fully-empty requests — add at least one row so the body is
            // shape-valid on average.
            req.getRows().add(fieldRef("customers", "region"));
        }
        // A conservative filter — always references a real (dataset, field) pair.
        if (rng.nextBoolean()) {
            OssieAiQueryRequest.FilterExpr f = new OssieAiQueryRequest.FilterExpr();
            f.setDataset("customers");
            f.setField("region");
            f.setOp("EQ");
            f.setValue("NA");
            req.getFilters().add(f);
        }
        return req;
    }

    /** Random requests including hostile names — validation should NEVER crash on these. */
    private static OssieAiQueryRequest randomRequest(Random rng) {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setConnection(pickMaybeHostile(rng, "mem"));
        req.setModel(pickMaybeHostile(rng, MODEL_NAME));

        int rowCount = rng.nextInt(4);
        for (int i = 0; i < rowCount; i++) req.getRows().add(randomFieldRef(rng));
        int colCount = rng.nextInt(3);
        for (int i = 0; i < colCount; i++) req.getColumns().add(randomFieldRef(rng));
        int valCount = rng.nextInt(3);
        for (int i = 0; i < valCount; i++) {
            OssieAiQueryRequest.MetricRef m = new OssieAiQueryRequest.MetricRef();
            m.setMetric(pickMaybeHostile(rng, METRICS[rng.nextInt(METRICS.length)]));
            req.getValues().add(m);
        }
        int filterCount = rng.nextInt(3);
        for (int i = 0; i < filterCount; i++) {
            OssieAiQueryRequest.FilterExpr f = new OssieAiQueryRequest.FilterExpr();
            f.setDataset(pickMaybeHostile(rng, DATASETS[rng.nextInt(DATASETS.length)]));
            f.setField(pickMaybeHostile(rng, "region"));
            f.setOp(rng.nextBoolean() ? OPS[rng.nextInt(OPS.length)] : pickMaybeHostile(rng, "EQ"));
            f.setValue(HOSTILE[rng.nextInt(HOSTILE.length)]);
            List<String> vals = new ArrayList<>();
            int v = rng.nextInt(3);
            for (int j = 0; j < v; j++) vals.add(HOSTILE[rng.nextInt(HOSTILE.length)]);
            f.setValues(vals);
            req.getFilters().add(f);
        }
        int sortCount = rng.nextInt(3);
        for (int i = 0; i < sortCount; i++) {
            OssieAiQueryRequest.SortRef s = new OssieAiQueryRequest.SortRef();
            if (rng.nextBoolean()) s.setMetric(pickMaybeHostile(rng, METRICS[rng.nextInt(METRICS.length)]));
            else {
                s.setDataset(pickMaybeHostile(rng, DATASETS[rng.nextInt(DATASETS.length)]));
                s.setField(pickMaybeHostile(rng, "region"));
            }
            s.setDirection(rng.nextBoolean() ? "DESC" : "ASC");
            req.getSorts().add(s);
        }
        if (rng.nextBoolean()) req.setLimit(rng.nextInt(1000) - 500);
        return req;
    }

    private static OssieAiQueryRequest.FieldRef fieldRef(String dataset, String field) {
        OssieAiQueryRequest.FieldRef f = new OssieAiQueryRequest.FieldRef();
        f.setDataset(dataset);
        f.setField(field);
        return f;
    }

    private static OssieAiQueryRequest.FieldRef randomFieldRef(Random rng) {
        OssieAiQueryRequest.FieldRef f = new OssieAiQueryRequest.FieldRef();
        f.setDataset(pickMaybeHostile(rng, DATASETS[rng.nextInt(DATASETS.length)]));
        f.setField(pickMaybeHostile(rng, rng.nextBoolean() ? "region" : "country"));
        return f;
    }

    private static String pickMaybeHostile(Random rng, String legitimate) {
        return rng.nextInt(4) == 0 ? HOSTILE[rng.nextInt(HOSTILE.length)] : legitimate;
    }

    /**
     * Return true if the hostile name — case-insensitively — matches a real dataset, field, or
     * metric in the fixture schema. Used to skip the poison test on iterations where the
     * "hostile" name accidentally becomes a valid one.
     */
    private boolean nameCollides(String hostile) {
        if (hostile == null) return false;
        String lower = hostile.toLowerCase(java.util.Locale.ROOT);
        if (schema.getDatasets().containsKey(lower)) return true;
        if (schema.getMetrics().containsKey(lower)) return true;
        if (schema.getModelName() != null && schema.getModelName().equalsIgnoreCase(hostile)) return true;
        for (OssieAiSchema.Dataset ds : schema.getDatasets().values()) {
            if (ds.getFields().containsKey(lower)) return true;
        }
        return false;
    }

    private static String describe(OssieAiQueryRequest req) {
        if (req == null) return "null";
        return "model=" + req.getModel() + " rows=" + req.getRows().size() + " values="
                + req.getValues().size() + " filters=" + req.getFilters().size();
    }
}
