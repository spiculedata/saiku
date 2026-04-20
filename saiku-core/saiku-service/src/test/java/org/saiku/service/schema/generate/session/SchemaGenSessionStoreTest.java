package org.saiku.service.schema.generate.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.schema.generate.apply.OpApplier;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.AggregatorOp;
import org.saiku.service.schema.generate.enrich.ops.RenameOp;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;

public class SchemaGenSessionStoreTest {

    private MutableClock clock;

    @Before
    public void setUp() {
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    public void createReturnsSessionWithUuidAndPendingStage() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        SchemaGenSession s = store.create("ds-1");
        assertNotNull(s.id());
        // Looks like a UUID (rough check — 36 chars, 4 hyphens).
        assertEquals(36, s.id().length());
        assertEquals("ds-1", s.dataSourceId());
        assertEquals(SchemaGenSession.Stage.PENDING, s.stage());
        assertNotNull(s.opLog());
        assertTrue(s.opLog().isEmpty());
        assertEquals(clock.instant(), s.createdAt());
        assertEquals(clock.instant(), s.lastAccessedAt());
    }

    @Test
    public void createReturnsFreshUuidPerSession() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        SchemaGenSession a = store.create("ds-1");
        SchemaGenSession b = store.create("ds-1");
        assertFalse(a.id().equals(b.id()));
    }

    @Test
    public void getReturnsSessionAndRemoveEvictsIt() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        SchemaGenSession s = store.create("ds-1");
        Optional<SchemaGenSession> fetched = store.get(s.id());
        assertTrue(fetched.isPresent());
        assertSame(s, fetched.get());
        store.remove(s.id());
        assertFalse(store.get(s.id()).isPresent());
    }

    @Test
    public void evictExpiredRemovesSessionsPastTtl() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMillis(100), clock);
        SchemaGenSession s = store.create("ds-1");
        clock.advance(Duration.ofMillis(200));
        store.evictExpired();
        assertFalse(store.get(s.id()).isPresent());
    }

    @Test
    public void evictExpiredKeepsFreshSessions() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofHours(1), clock);
        SchemaGenSession s = store.create("ds-1");
        clock.advance(Duration.ofSeconds(30));
        store.evictExpired();
        assertTrue(store.get(s.id()).isPresent());
    }

    @Test
    public void getTouchesLastAccessedAndKeepsSessionAlive() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMillis(100), clock);
        SchemaGenSession s = store.create("ds-1");
        // Advance partway, then touch via get.
        clock.advance(Duration.ofMillis(80));
        assertTrue(store.get(s.id()).isPresent());
        // Advance again — would have been > 100ms since create, but since last access was at 80ms
        // the session should still be alive (80 -> 150 = 70ms since last access).
        clock.advance(Duration.ofMillis(70));
        store.evictExpired();
        assertTrue(store.get(s.id()).isPresent());
    }

    @Test
    public void getReturnsEmptyForUnknownId() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        assertFalse(store.get("nope").isPresent());
    }

    @Test
    public void defaultTtlIs30Minutes() {
        // Convenience ctor uses system clock + 30m default.
        SchemaGenSessionStore store = new SchemaGenSessionStore();
        assertEquals(Duration.ofMinutes(30), store.ttl());
    }

    @Test
    public void opLogReplayProducesEquivalentDraft() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        SchemaGenSession s = store.create("ds-1");
        s.setDraft(buildInitialDraft());

        OpApplier applier = new OpApplier();

        // Apply ops to live draft + append to op log.
        SuggestionOp op1 =
                new RenameOp("cubes/fact_sales/measures/amount_col", "amount", "Amount", null, 0.9, "caption");
        SuggestionOp op2 = new AggregatorOp(
                "cubes/fact_sales/measures/qty_col",
                DraftMeasure.Aggregator.SUM,
                DraftMeasure.Aggregator.AVG,
                0.85,
                "avg better");
        SuggestionOp op3 = new RenameOp(
                "cubes/fact_sales/dimensions/dim_customer", "customer", "Customer", null, 0.95, "title case");

        applier.apply(s.draft(), op1);
        s.appendOp(op1);
        applier.apply(s.draft(), op2);
        s.appendOp(op2);
        applier.apply(s.draft(), op3);
        s.appendOp(op3);

        // Build a fresh initial draft, replay log.
        DraftSchema replayed = buildInitialDraft();
        for (SuggestionOp op : s.opLog()) {
            applier.apply(replayed, op);
        }

        assertEquals(canonicalise(s.draft()), canonicalise(replayed));
        assertEquals(3, s.opLog().size());
    }

    @Test
    public void appendOpTouchesLastAccessedAt() {
        SchemaGenSessionStore store = new SchemaGenSessionStore(Duration.ofMinutes(30), clock);
        SchemaGenSession s = store.create("ds-1");
        Instant before = s.lastAccessedAt();
        clock.advance(Duration.ofSeconds(5));
        s.appendOp(new RenameOp("cubes/Sales", "Sales", "Sales2", null, 0.9, "r"));
        assertFalse(before.equals(s.lastAccessedAt()));
    }

    // -- helpers -----------------------------------------------------------

    private static DraftSchema buildInitialDraft() {
        Provenance rule = new Provenance(Provenance.Source.RULE, "rule:test", 0.8);
        DraftSchema schema = new DraftSchema("Main");
        DraftCube sales = new DraftCube("Sales", "fact_sales", rule);

        DraftDimension customer = new DraftDimension("customer", DraftDimension.Type.STANDARD, rule);
        customer.setSourceTable("dim_customer");
        customer.setForeignKey("customer_id");
        DraftHierarchy custHier = new DraftHierarchy("customer", "id", rule);
        custHier.levels().add(new DraftLevel("name", "name", DraftLevel.Type.REGULAR, rule));
        customer.hierarchies().add(custHier);
        sales.dimensions().add(customer);

        sales.measures().add(new DraftMeasure("amount", "amount_col", DraftMeasure.Aggregator.SUM, rule));
        sales.measures().add(new DraftMeasure("qty", "qty_col", DraftMeasure.Aggregator.SUM, rule));

        schema.cubes().add(sales);
        return schema;
    }

    /** Structural canonical form for equivalence comparison. */
    private static String canonicalise(DraftSchema s) {
        StringBuilder sb = new StringBuilder();
        sb.append("schema(").append(s.name()).append(")\n");
        for (DraftCube c : s.cubes()) {
            sb.append("  cube(")
                    .append(c.name())
                    .append("/")
                    .append(c.sourceFactTable())
                    .append("/")
                    .append(provStr(c.provenance()))
                    .append(")\n");
            for (DraftDimension d : c.dimensions()) {
                sb.append("    dim(")
                        .append(d.name())
                        .append("/")
                        .append(d.type())
                        .append("/")
                        .append(d.sourceTable())
                        .append("/")
                        .append(d.foreignKey())
                        .append("/")
                        .append(provStr(d.provenance()))
                        .append(")\n");
                for (DraftHierarchy h : d.hierarchies()) {
                    sb.append("      hier(")
                            .append(h.name())
                            .append("/")
                            .append(h.primaryKey())
                            .append("/")
                            .append(provStr(h.provenance()))
                            .append(")\n");
                    for (DraftLevel l : h.levels()) {
                        sb.append("        lvl(")
                                .append(l.name())
                                .append("/")
                                .append(l.column())
                                .append("/")
                                .append(l.type())
                                .append("/")
                                .append(provStr(l.provenance()))
                                .append(")\n");
                    }
                }
            }
            for (DraftMeasure m : c.measures()) {
                sb.append("    mes(")
                        .append(m.name())
                        .append("/")
                        .append(m.column())
                        .append("/")
                        .append(m.aggregator())
                        .append("/")
                        .append(provStr(m.provenance()))
                        .append(")\n");
            }
        }
        for (DraftDimension d : s.sharedDimensions()) {
            sb.append("  sharedDim(")
                    .append(d.name())
                    .append("/")
                    .append(d.type())
                    .append(")\n");
        }
        return sb.toString();
    }

    private static String provStr(Provenance p) {
        if (p == null) return "null";
        return p.source() + ":" + p.ruleId() + ":" + p.confidence();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
