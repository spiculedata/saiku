/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.generate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import bi.saiku.ossie.model.Dataset;
import bi.saiku.ossie.model.Expression;
import bi.saiku.ossie.model.Field;
import bi.saiku.ossie.model.Metric;
import bi.saiku.ossie.model.OssieDocument;
import bi.saiku.ossie.model.Relationship;
import bi.saiku.ossie.model.SemanticModel;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link OssieModelValidator}.
 *
 * <p>Each case is a defect the converter can genuinely produce, or can be argued to produce as the
 * converter grows: an unresolvable compound key, a dimension with no primary key, a duplicate
 * dataset name. A validator that only tests the happy path is a validator that will be trusted
 * until the first bad model reaches a dashboard.
 */
public class OssieModelValidatorTest {

    @Test
    public void acceptsASoundDocument() {
        assertEquals("no findings expected", List.of(), OssieModelValidator.validate(soundDocument()));
    }

    @Test
    public void rejectsANullDocument() {
        assertEquals(1, OssieModelValidator.validate(null).size());
    }

    @Test
    public void rejectsADocumentWithNoSemanticModels() {
        List<String> problems = OssieModelValidator.validate(new OssieDocument());
        assertEquals(1, problems.size());
        assertTrue("must say why, not just that: " + problems, problems.get(0).contains("zero semantic models"));
    }

    @Test
    public void rejectsAModelWithNoDatasets() {
        OssieDocument doc = new OssieDocument();
        SemanticModel sm = new SemanticModel();
        sm.setName("Sales");
        doc.getSemanticModel().add(sm);

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("no datasets"));
    }

    @Test
    public void rejectsAFieldWithNoExpression() {
        OssieDocument doc = soundDocument();
        Dataset fact = doc.getSemanticModel().get(0).getDatasets().get(0);
        Field orphan = new Field();
        orphan.setName("orphan");
        // Ossie's schema rejects an expression-less field; this is the invariant that matters.
        fact.getFields().add(orphan);

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("field 'orphan' has no expression"));
    }

    @Test
    public void rejectsAFieldWithAnEmptyExpression() {
        OssieDocument doc = soundDocument();
        Dataset fact = doc.getSemanticModel().get(0).getDatasets().get(0);
        Field empty = new Field();
        empty.setName("empty");
        empty.setExpression(new Expression());
        fact.getFields().add(empty);

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("field 'empty' has no expression"));
    }

    @Test
    public void rejectsAFieldWithNoName() {
        OssieDocument doc = soundDocument();
        Dataset fact = doc.getSemanticModel().get(0).getDatasets().get(0);
        Field nameless = new Field();
        nameless.setExpression(Expression.ansi("X"));
        fact.getFields().add(nameless);

        assertEquals(1, OssieModelValidator.validate(doc).size());
    }

    @Test
    public void rejectsADatasetWithNoSource() {
        OssieDocument doc = soundDocument();
        Dataset dim = doc.getSemanticModel().get(0).getDatasets().get(1);
        dim.setSource(null);

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("has no source table"));
    }

    @Test
    public void rejectsARelationshipPointingAtAMissingDataset() {
        OssieDocument doc = soundDocument();
        doc.getSemanticModel().get(0).getRelationships().get(0).setTo("GHOST");

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(1, problems.size());
        assertTrue(
                "the finding must name the available datasets so the operator can see the typo: " + problems,
                problems.get(0).contains("GHOST") && problems.get(0).contains("CUSTOMER"));
    }

    @Test
    public void rejectsARelationshipWithAnUnresolvableFrom() {
        OssieDocument doc = soundDocument();
        doc.getSemanticModel().get(0).getRelationships().get(0).setFrom(null);

        assertEquals(1, OssieModelValidator.validate(doc).size());
    }

    @Test
    public void rejectsARelationshipWithNoColumns() {
        OssieDocument doc = soundDocument();
        Relationship rel = doc.getSemanticModel().get(0).getRelationships().get(0);
        rel.getFromColumns().clear();
        rel.getToColumns().clear();

        List<String> problems = OssieModelValidator.validate(doc);
        assertEquals(2, problems.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("no from-columns")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("no to-columns")));
    }

    @Test
    public void rejectsDuplicateDatasetNames() {
        OssieDocument doc = soundDocument();
        // Ossie resolves relationships by name, so the second dataset is unreachable.
        Dataset dupe = new Dataset();
        dupe.setName("CUSTOMER");
        dupe.setSource("customer_v2");
        doc.getSemanticModel().get(0).getDatasets().add(dupe);

        List<String> problems = OssieModelValidator.validate(doc);
        assertTrue(
                "duplicate dataset name must be reported: " + problems,
                problems.stream().anyMatch(p -> p.contains("two datasets named 'CUSTOMER'")));
    }

    @Test
    public void rejectsAMetricWithNoExpression() {
        OssieDocument doc = soundDocument();
        Metric bare = new Metric();
        bare.setName("bare");
        doc.getSemanticModel().get(0).getMetrics().add(bare);

        List<String> problems = OssieModelValidator.validate(doc);
        assertTrue(problems.stream().anyMatch(p -> p.contains("metric 'bare' has no expression")));
    }

    @Test
    public void reportsEveryProblemNotJustTheFirst() {
        OssieDocument doc = soundDocument();
        Dataset fact = doc.getSemanticModel().get(0).getDatasets().get(0);
        Field a = new Field();
        a.setName("a");
        fact.getFields().add(a);
        Field b = new Field();
        b.setName("b");
        fact.getFields().add(b);

        // Two independent defects must both surface, so one regeneration run tells the operator
        // everything that needs fixing rather than one thing per attempt.
        assertEquals(2, OssieModelValidator.validate(doc).size());
    }

    // ------------------------------------------------------------------

    private static OssieDocument soundDocument() {
        OssieDocument doc = new OssieDocument();

        SemanticModel sm = new SemanticModel();
        sm.setName("Sales");

        Dataset fact = new Dataset();
        fact.setName("ORDERS");
        fact.setSource("ORDERS");
        fact.getPrimaryKey().add("ID");
        Field amount = new Field();
        amount.setName("Amount");
        amount.setExpression(Expression.ansi("AMOUNT"));
        fact.getFields().add(amount);
        sm.getDatasets().add(fact);

        Dataset dim = new Dataset();
        dim.setName("CUSTOMER");
        dim.setSource("CUSTOMER");
        dim.getPrimaryKey().add("ID");
        Field name = new Field();
        name.setName("Name");
        name.setExpression(Expression.ansi("NAME"));
        dim.getFields().add(name);
        sm.getDatasets().add(dim);

        Relationship rel = new Relationship();
        rel.setName("ORDERS_to_CUSTOMER");
        rel.setFrom("ORDERS");
        rel.setTo("CUSTOMER");
        rel.getFromColumns().add("CUSTOMER_ID");
        rel.getToColumns().add("ID");
        sm.getRelationships().add(rel);

        Metric revenue = new Metric();
        revenue.setName("Revenue");
        revenue.setExpression(Expression.ansi("SUM(ORDERS.AMOUNT)"));
        sm.getMetrics().add(revenue);

        doc.getSemanticModel().add(sm);
        return doc;
    }
}
