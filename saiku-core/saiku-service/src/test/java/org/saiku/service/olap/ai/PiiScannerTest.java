/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Unit coverage for {@link PiiScanner} (saiku#902 phase 3). Covers each
 * documented pattern, the deliberate false-positive bias, the
 * already-annotated-skip behaviour, and the {@code \\b} word-boundary
 * decisions that separate {@code "email"} (PII) from {@code "email_count"}
 * (aggregate, not PII).
 */
public class PiiScannerTest {

    @Test
    public void detects_email_in_measure_name() {
        AiSchema schema = freshSchema();
        addMeasure(schema, "Email");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertTrue("email pattern must fire", matches.stream().anyMatch(m -> "email".equals(m.pattern)));
    }

    @Test
    public void ignores_aggregate_named_email_count() {
        // The key word-boundary decision in the rule design: a column named
        // "email_count" is the result of `COUNT(email)`, not the raw value.
        // Flagging it would over-redact aggregates that are USEFUL to the
        // analyst. The \b anchors in the rule must reject this.
        AiSchema schema = freshSchema();
        addMeasure(schema, "email_count");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertFalse(
                "email_count is a COUNT aggregate, NOT PII — must not match",
                matches.stream().anyMatch(m -> "email".equals(m.pattern)));
    }

    @Test
    public void detects_ssn_variants() {
        // Three regional names for the same concept.
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "SSN");
        addLevel(schema, "Customers", "social_security");
        addLevel(schema, "Customers", "NIN"); // UK National Insurance
        addLevel(schema, "Customers", "SIN"); // Canada Social Insurance
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);

        long ssnHits = matches.stream().filter(m -> "ssn".equals(m.pattern)).count();
        assertEquals("all four regional national-id names must match", 4, ssnHits);
    }

    @Test
    public void detects_dob_variants() {
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "DOB");
        addLevel(schema, "Customers", "date_of_birth");
        addLevel(schema, "Customers", "birth_date");
        addLevel(schema, "Customers", "BirthDate");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals(4, matches.stream().filter(m -> "dob".equals(m.pattern)).count());
    }

    @Test
    public void detects_phone_variants() {
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "phone");
        addLevel(schema, "Customers", "mobile");
        addLevel(schema, "Customers", "tel");
        addLevel(schema, "Customers", "tel_no");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals(4, matches.stream().filter(m -> "phone".equals(m.pattern)).count());
    }

    @Test
    public void detects_address_fields() {
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "address");
        addLevel(schema, "Customers", "street");
        addLevel(schema, "Customers", "postcode");
        addLevel(schema, "Customers", "postal_code");
        addLevel(schema, "Customers", "zipcode");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals(
                5, matches.stream().filter(m -> "address".equals(m.pattern)).count());
    }

    @Test
    public void detects_medical_record_variants() {
        AiSchema schema = freshSchema();
        addLevel(schema, "Patients", "NHS");
        addLevel(schema, "Patients", "medical_record");
        addLevel(schema, "Patients", "MRN");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals(
                3,
                matches.stream().filter(m -> "medical_record".equals(m.pattern)).count());
    }

    @Test
    public void detects_financial_account_variants() {
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "account_number");
        addLevel(schema, "Customers", "IBAN");
        addLevel(schema, "Customers", "SWIFT");
        addLevel(schema, "Customers", "credit_card");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals(
                4, matches.stream().filter(m -> "financial".equals(m.pattern)).count());
    }

    @Test
    public void ignores_plain_account_id() {
        // Sanity: "account_id" / "account" alone are common surrogate-key
        // patterns and NOT inherently PII. Flagging them would over-redact
        // every SaaS app's primary tenant key. The financial pattern requires
        // "account_number" / "account_no", not bare "account".
        AiSchema schema = freshSchema();
        addLevel(schema, "Customers", "account_id");
        addLevel(schema, "Customers", "account");
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertFalse(
                "account_id / account must not match the financial pattern",
                matches.stream().anyMatch(m -> "financial".equals(m.pattern)));
    }

    @Test
    public void skips_columns_already_annotated_pii() {
        // If the schema author already opted the column in, the scanner
        // doesn't repeat the suggestion. Otherwise an annotated cube would
        // keep producing WARNs forever — pure noise.
        AiSchema schema = freshSchema();
        AiSchema.Measure m = new AiSchema.Measure("Email", "[Measures].[Email]");
        m.pii = true;
        schema.measures.put(AiSchema.key("Email"), m);
        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        assertEquals("already-annotated columns must be skipped", 0, matches.size());
    }

    @Test
    public void match_carries_path_for_admin_ui() {
        // The suggestion needs to point AT the column so the admin UI can
        // jump straight to it. path format is documented in PiiScanner.Match.
        AiSchema schema = freshSchema();
        AiSchema.Dimension d = new AiSchema.Dimension("Customers", "[Customers]");
        AiSchema.Hierarchy h = new AiSchema.Hierarchy("Customers", "[Customers]");
        h.levels.put(AiSchema.key("Customer Name"), new AiSchema.Level("Customer Name", "[Customers].[Name]"));
        d.hierarchies.put(AiSchema.key("Customers"), h);
        schema.dimensions.put(AiSchema.key("Customers"), d);

        List<PiiScanner.Match> matches = PiiScanner.scan(schema);
        boolean found = matches.stream()
                .anyMatch(m -> "dimensions.Customers.hierarchies.Customers.levels.Customer Name".equals(m.path));
        assertTrue("path must address the column unambiguously", found);
    }

    @Test
    public void null_schema_returns_empty_list_without_crashing() {
        // Defensive — the scanner runs on the schema-build hot path; a null
        // schema must NOT crash that path.
        assertTrue(PiiScanner.scan(null).isEmpty());
    }

    @Test
    public void empty_schema_returns_empty_list() {
        // Empty cube with no measures or dimensions — common in test fixtures.
        AiSchema schema = freshSchema();
        assertTrue(PiiScanner.scan(schema).isEmpty());
    }

    @Test
    public void no_match_returns_empty_list() {
        // Real cube full of innocent column names — no findings.
        AiSchema schema = freshSchema();
        addMeasure(schema, "Revenue");
        addMeasure(schema, "Units Sold");
        addLevel(schema, "Time", "Year");
        addLevel(schema, "Time", "Quarter");
        addLevel(schema, "Geography", "Country");
        addLevel(schema, "Geography", "State");
        assertTrue(PiiScanner.scan(schema).isEmpty());
    }

    /* ---------------------------- helpers ---------------------------- */

    private static AiSchema freshSchema() {
        return new AiSchema("c/cat/sch/Sales", "Sales", "[Sales]");
    }

    private static void addMeasure(AiSchema schema, String name) {
        schema.measures.put(AiSchema.key(name), new AiSchema.Measure(name, "[Measures].[" + name + "]"));
    }

    private static void addLevel(AiSchema schema, String dimName, String levelName) {
        String dimKey = AiSchema.key(dimName);
        AiSchema.Dimension d =
                schema.dimensions.computeIfAbsent(dimKey, k -> new AiSchema.Dimension(dimName, "[" + dimName + "]"));
        AiSchema.Hierarchy h =
                d.hierarchies.computeIfAbsent(dimKey, k -> new AiSchema.Hierarchy(dimName, "[" + dimName + "]"));
        h.levels.put(AiSchema.key(levelName), new AiSchema.Level(levelName, "[" + dimName + "].[" + levelName + "]"));
    }
}
