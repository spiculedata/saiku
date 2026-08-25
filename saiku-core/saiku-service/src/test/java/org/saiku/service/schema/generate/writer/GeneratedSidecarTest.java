/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.IgnoreOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

/**
 * Task E1 — writer-scope sidecar. Verifies:
 *
 * <ol>
 *   <li>{@link GeneratedSidecarIo#build}, {@link GeneratedSidecarIo#write}, {@link
 *       GeneratedSidecarIo#read} round-trip to byte-identical canonical draft JSON and to a
 *       semantically equal op log.
 *   <li>{@link MondrianSchemaWriter#writeWithSidecar} emits both non-empty XML and a parseable
 *       sidecar JSON in a single call.
 * </ol>
 */
public class GeneratedSidecarTest {

    @Test
    public void buildWriteReadRoundTripsDraftAndOpLog() {
        DraftSchema draft = sampleDraft();
        List<SuggestionOp> opLog = sampleOpLog();
        Instant generatedAt = Instant.parse("2026-04-19T22:00:00Z");

        GeneratedSidecar built = GeneratedSidecarIo.build(draft, opLog, draft.name(), generatedAt, "3.17.0");
        String json = GeneratedSidecarIo.write(built);
        GeneratedSidecar roundTripped = GeneratedSidecarIo.read(json);

        // Metadata must round-trip verbatim.
        assertEquals(draft.name(), roundTripped.schemaName());
        assertEquals(generatedAt, roundTripped.generatedAt());
        assertEquals("3.17.0", roundTripped.saikuVersion());

        // Draft equality via canonical serializer (byte-identical JSON).
        assertEquals(DraftSchemaJson.toJson(draft), DraftSchemaJson.toJson(roundTripped.draft()));

        // Op log equality via Jackson round-trip — same length, same types, same fields.
        assertEquals(opLog.size(), roundTripped.opLog().size());
        for (int i = 0; i < opLog.size(); i++) {
            assertEquals(opLog.get(i), roundTripped.opLog().get(i));
        }
    }

    @Test
    public void writerEmitsXmlAndParseableSidecar() {
        DraftSchema draft = sampleDraft();
        List<SuggestionOp> opLog = sampleOpLog();

        MondrianSchemaWriter.WriteResult result = new MondrianSchemaWriter().writeWithSidecar(draft, opLog);

        assertNotNull(result);
        assertTrue(
                "xml should be non-empty", result.xml() != null && !result.xml().isEmpty());
        assertTrue(
                "sidecar should be non-empty",
                result.sidecarJson() != null && !result.sidecarJson().isEmpty());

        GeneratedSidecar parsed = GeneratedSidecarIo.read(result.sidecarJson());
        assertEquals(draft.name(), parsed.schemaName());
        assertEquals(opLog.size(), parsed.opLog().size());
        // Draft canonical equality.
        assertEquals(DraftSchemaJson.toJson(draft), DraftSchemaJson.toJson(parsed.draft()));
    }

    // --- fixtures ----------------------------------------------------------

    private static DraftSchema sampleDraft() {
        Provenance rule = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

        DraftSchema schema = new DraftSchema("Sales");

        // Shared time dim.
        DraftDimension time = new DraftDimension("Time", DraftDimension.Type.TIME, rule);
        time.setSourceTable("time_by_day");
        DraftHierarchy th = new DraftHierarchy("Time", "time_id", rule);
        th.levels().add(new DraftLevel("Year", "the_year", DraftLevel.Type.YEARS, rule));
        th.levels().add(new DraftLevel("Month", "the_month", DraftLevel.Type.MONTHS, rule));
        time.hierarchies().add(th);
        schema.sharedDimensions().add(time);

        DraftCube cube = new DraftCube("Sales", "sales_fact", rule);

        DraftDimension customer = new DraftDimension("Customer", DraftDimension.Type.STANDARD, rule);
        customer.setSourceTable("customer");
        customer.setForeignKey("customer_id");
        DraftHierarchy ch = new DraftHierarchy("Customer", "customer_id", rule);
        ch.levels().add(new DraftLevel("Name", "full_name", DraftLevel.Type.REGULAR, rule));
        customer.hierarchies().add(ch);
        cube.dimensions().add(customer);

        cube.measures().add(new DraftMeasure("Amount", "amount", DraftMeasure.Aggregator.SUM, rule));
        cube.measures().add(new DraftMeasure("Fact Count", null, DraftMeasure.Aggregator.COUNT_STAR, rule));

        schema.cubes().add(cube);
        return schema;
    }

    private static List<SuggestionOp> sampleOpLog() {
        return List.of(
                new RenameOp(
                        "cubes/Sales/measures/Amount", "Amount", "Revenue", "total revenue", 0.85, "prettier caption"),
                new AggregatorOp(
                        "cubes/Sales/measures/Amount",
                        DraftMeasure.Aggregator.SUM,
                        DraftMeasure.Aggregator.SUM,
                        0.7,
                        "confirm SUM"),
                new IgnoreOp("cubes/Sales/measures/Fact Count", 0.5, "noisy"));
    }
}
