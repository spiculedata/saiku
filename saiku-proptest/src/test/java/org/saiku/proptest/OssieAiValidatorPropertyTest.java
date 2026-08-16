/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.proptest;

import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.saiku.service.ossie.ai.OssieAiQueryRequest;
import org.saiku.service.ossie.ai.OssieAiSchema;
import org.saiku.service.ossie.ai.OssieAiValidationException;
import org.saiku.service.ossie.ai.OssieAiValidator;

/**
 * Property-based tests for {@link OssieAiValidator} — the SQL-side twin of
 * {@code AiRequestSchemaValidator}, gating {@code POST /ai/ossie/query}.
 *
 * <p>Same stakes, different surface: the request names datasets, fields and metrics that are
 * resolved against a semantic model and then compiled into SQL. A reference that slips through
 * unvalidated reaches the query builder, so validation is the boundary between "agent typo" and
 * "unexpected SQL". And as with the MDX side, the agent's whole recovery strategy is reading the
 * error's {@code field} and {@code available} list, so a rejection that names neither is a retry
 * loop that never terminates.
 */
class OssieAiValidatorPropertyTest {

    private static final OssieAiValidator VALIDATOR = new OssieAiValidator();

    private static final String MODEL = "sales_model";
    private static final String DATASET = "orders";
    private static final String FIELD = "region";
    private static final String METRIC = "revenue";

    /** A schema with one dataset carrying one field, plus one metric. */
    private static OssieAiSchema schema() {
        OssieAiSchema s = new OssieAiSchema();
        s.setModelName(MODEL);
        s.setModelId(MODEL);
        s.setConnectionName("ossie");
        s.setFactDataset(DATASET);

        OssieAiSchema.Dataset ds = new OssieAiSchema.Dataset();
        ds.setName(DATASET);
        OssieAiSchema.Field f = new OssieAiSchema.Field();
        f.setName(FIELD);
        Map<String, OssieAiSchema.Field> fields = new LinkedHashMap<>();
        fields.put(FIELD.toLowerCase(Locale.ROOT), f);
        ds.setFields(fields);

        Map<String, OssieAiSchema.Dataset> datasets = new LinkedHashMap<>();
        datasets.put(DATASET.toLowerCase(Locale.ROOT), ds);
        s.setDatasets(datasets);

        OssieAiSchema.Metric m = new OssieAiSchema.Metric();
        m.setName(METRIC);
        Map<String, OssieAiSchema.Metric> metrics = new LinkedHashMap<>();
        metrics.put(METRIC.toLowerCase(Locale.ROOT), m);
        s.setMetrics(metrics);

        return s;
    }

    private static OssieAiQueryRequest.FieldRef fieldRef(String dataset, String field) {
        OssieAiQueryRequest.FieldRef r = new OssieAiQueryRequest.FieldRef();
        r.setDataset(dataset);
        r.setField(field);
        return r;
    }

    private static OssieAiQueryRequest.MetricRef metricRef(String metric) {
        OssieAiQueryRequest.MetricRef r = new OssieAiQueryRequest.MetricRef();
        r.setMetric(metric);
        return r;
    }

    /** A request that the validator accepts. */
    private static OssieAiQueryRequest validRequest() {
        OssieAiQueryRequest req = new OssieAiQueryRequest();
        req.setModel(MODEL);
        req.setRows(new ArrayList<>(List.of(fieldRef(DATASET, FIELD))));
        req.setValues(new ArrayList<>(List.of(metricRef(METRIC))));
        return req;
    }

    /** A conforming request is accepted. */
    @HegelTest
    void aConformingRequestIsAccepted(TestCase tc) {
        int rowCount = tc.draw(integers().min(0).max(3), "rowCount");

        OssieAiQueryRequest req = validRequest();
        List<OssieAiQueryRequest.FieldRef> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(fieldRef(DATASET, FIELD));
        }
        req.setRows(rows);

        assertDoesNotThrow(() -> VALIDATOR.validate(req, schema()));
    }

    /** An unknown field is rejected wherever it appears — rows or columns. */
    @HegelTest
    void anUnknownFieldIsRejectedOnEitherAxis(TestCase tc) {
        String bogus = tc.draw(fromRegex("[a-z_]{1,12}"), "bogus");
        tc.assume(!bogus.equalsIgnoreCase(FIELD));
        boolean onRows = tc.draw(dev.hegel.Generators.booleans(), "onRows");

        OssieAiQueryRequest req = validRequest();
        if (onRows) {
            req.setRows(new ArrayList<>(List.of(fieldRef(DATASET, bogus))));
        } else {
            req.setColumns(new ArrayList<>(List.of(fieldRef(DATASET, bogus))));
        }

        assertThrows(
                OssieAiValidationException.class,
                () -> VALIDATOR.validate(req, schema()),
                "unknown field accepted: " + bogus);
    }

    /** An unknown dataset is rejected — it would otherwise reach the SQL builder. */
    @HegelTest
    void anUnknownDatasetIsRejected(TestCase tc) {
        String bogus = tc.draw(fromRegex("[a-z_]{1,12}"), "bogus");
        tc.assume(!bogus.equalsIgnoreCase(DATASET));

        OssieAiQueryRequest req = validRequest();
        req.setRows(new ArrayList<>(List.of(fieldRef(bogus, FIELD))));

        assertThrows(OssieAiValidationException.class, () -> VALIDATOR.validate(req, schema()));
    }

    /** An unknown metric is rejected. */
    @HegelTest
    void anUnknownMetricIsRejected(TestCase tc) {
        String bogus = tc.draw(fromRegex("[a-z_]{1,12}"), "bogus");
        tc.assume(!bogus.equalsIgnoreCase(METRIC));

        OssieAiQueryRequest req = validRequest();
        req.setValues(new ArrayList<>(List.of(metricRef(bogus))));

        assertThrows(OssieAiValidationException.class, () -> VALIDATOR.validate(req, schema()));
    }

    /** A missing model is rejected — every reference resolves relative to it. */
    @HegelTest
    void aMissingModelIsRejected(TestCase tc) {
        String blank = tc.draw(sampledFrom(List.of("", " ", "\t")), "blank");

        OssieAiQueryRequest req = validRequest();
        req.setModel(blank);

        assertThrows(OssieAiValidationException.class, () -> VALIDATOR.validate(req, schema()));

        OssieAiQueryRequest nullModel = validRequest();
        nullModel.setModel(null);
        assertThrows(OssieAiValidationException.class, () -> VALIDATOR.validate(nullModel, schema()));
    }

    /** A null request body is a typed rejection, not an NPE. */
    @HegelTest
    void aNullRequestIsTypedNotAnNpe(TestCase tc) {
        OssieAiValidationException e =
                assertThrows(OssieAiValidationException.class, () -> VALIDATOR.validate(null, schema()));

        assertFalse(e.getField() == null || e.getField().isBlank(), "the null-body rejection named no field");
    }

    /**
     * THE property. Every rejection names the field the agent must fix — that is its entire
     * self-correction mechanism, and an error without one is a non-terminating retry loop.
     */
    @HegelTest
    void everyRejectionNamesTheOffendingField(TestCase tc) {
        String bogus = tc.draw(fromRegex("[a-z_]{1,10}"), "bogus");
        tc.assume(
                !bogus.equalsIgnoreCase(FIELD) && !bogus.equalsIgnoreCase(METRIC) && !bogus.equalsIgnoreCase(DATASET));
        int shape = tc.draw(integers().min(0).max(3), "shape");

        OssieAiQueryRequest req = validRequest();
        switch (shape) {
            case 0 -> req.setRows(new ArrayList<>(List.of(fieldRef(DATASET, bogus))));
            case 1 -> req.setColumns(new ArrayList<>(List.of(fieldRef(bogus, FIELD))));
            case 2 -> req.setValues(new ArrayList<>(List.of(metricRef(bogus))));
            default -> req.setModel(bogus);
        }

        try {
            VALIDATOR.validate(req, schema());
        } catch (OssieAiValidationException e) {
            assertFalse(
                    e.getField() == null || e.getField().isBlank(),
                    "a rejection named no field, so the agent cannot self-correct");
        }
    }

    /** The rejection points at the exact index, so an agent knows WHICH entry to fix. */
    @HegelTest
    void theRejectionIdentifiesTheOffendingIndex(TestCase tc) {
        int good = tc.draw(integers().min(0).max(3), "good");
        String bogus = tc.draw(fromRegex("[a-z_]{4,10}"), "bogus");
        tc.assume(!bogus.equalsIgnoreCase(FIELD));

        List<OssieAiQueryRequest.FieldRef> rows = new ArrayList<>();
        for (int i = 0; i < good; i++) {
            rows.add(fieldRef(DATASET, FIELD));
        }
        rows.add(fieldRef(DATASET, bogus)); // the bad one, at index `good`

        OssieAiQueryRequest req = validRequest();
        req.setRows(rows);

        try {
            VALIDATOR.validate(req, schema());
            fail("expected a rejection for the unknown field");
        } catch (OssieAiValidationException e) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    e.getField().contains("[" + good + "]"),
                    "rejection pointed at the wrong entry: " + e.getField() + " (bad index was " + good + ")");
        }
    }

    /** Validation never mutates the request it was handed. */
    @HegelTest
    void validationNeverMutatesTheRequest(TestCase tc) {
        OssieAiQueryRequest req = validRequest();
        int rowsBefore = req.getRows().size();
        int valuesBefore = req.getValues().size();
        String modelBefore = req.getModel();

        try {
            VALIDATOR.validate(req, schema());
        } catch (OssieAiValidationException ignored) {
            // irrelevant
        }

        org.junit.jupiter.api.Assertions.assertEquals(rowsBefore, req.getRows().size(), "rows were mutated");
        org.junit.jupiter.api.Assertions.assertEquals(
                valuesBefore, req.getValues().size(), "values were mutated");
        org.junit.jupiter.api.Assertions.assertEquals(modelBefore, req.getModel(), "model was mutated");
    }

    /** Total: junk references produce a typed rejection, never an unchecked failure. */
    @HegelTest
    void validationIsTotalOverJunkReferences(TestCase tc) {
        String junk = tc.draw(fromRegex("[a-zA-Z0-9_.\\[\\]\" -]{0,20}"), "junk");

        OssieAiQueryRequest req = validRequest();
        req.setRows(new ArrayList<>(List.of(fieldRef(junk, junk))));

        try {
            VALIDATOR.validate(req, schema());
        } catch (OssieAiValidationException expected) {
            // typed rejection is the contract
        } catch (RuntimeException unchecked) {
            fail("validator threw unchecked " + unchecked.getClass().getSimpleName() + " for: [" + junk + "]");
        }
    }
}
