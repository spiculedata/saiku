package org.saiku.service.schema.generate.enrich;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.saiku.service.schema.generate.draft.DraftCube;
import org.saiku.service.schema.generate.draft.DraftDimension;
import org.saiku.service.schema.generate.draft.DraftHierarchy;
import org.saiku.service.schema.generate.draft.DraftLevel;
import org.saiku.service.schema.generate.draft.DraftMeasure;
import org.saiku.service.schema.generate.draft.DraftSchema;
import org.saiku.service.schema.generate.draft.Provenance;
import org.saiku.service.schema.generate.enrich.ops.SuggestionOp;
import org.saiku.service.schema.generate.enrich.provider.EnrichRequest;
import org.saiku.service.schema.generate.enrich.provider.EnrichResponse;
import org.saiku.service.schema.generate.enrich.provider.LlmProvider;
import org.saiku.service.schema.generate.enrich.provider.NoopProvider;

public class LlmEnricherTest {

    private static final Provenance PROV = new Provenance(Provenance.Source.RULE, "rule:test", 0.9);

    private static DraftSchema twoCubeSchema() {
        DraftSchema s = new DraftSchema("Sales");

        DraftCube sales = new DraftCube("sales", "orders", PROV);
        DraftDimension cust = new DraftDimension("customer", DraftDimension.Type.STANDARD, PROV);
        DraftHierarchy custH = new DraftHierarchy("customer", null, PROV);
        custH.levels().add(new DraftLevel("customer_name", "customer_name", DraftLevel.Type.REGULAR, PROV));
        cust.hierarchies().add(custH);
        sales.dimensions().add(cust);
        sales.measures().add(new DraftMeasure("count_orders", null, DraftMeasure.Aggregator.SUM, PROV));
        s.cubes().add(sales);

        DraftCube inv = new DraftCube("inventory", "stock", PROV);
        DraftDimension prod = new DraftDimension("product", DraftDimension.Type.STANDARD, PROV);
        DraftHierarchy prodH = new DraftHierarchy("product", null, PROV);
        prodH.levels().add(new DraftLevel("product_name", "product_name", DraftLevel.Type.REGULAR, PROV));
        prod.hierarchies().add(prodH);
        inv.dimensions().add(prod);
        inv.measures().add(new DraftMeasure("avg_qty", "qty", DraftMeasure.Aggregator.SUM, PROV));
        s.cubes().add(inv);

        return s;
    }

    private static Map<String, List<String>> samples() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("customers.ssn", Arrays.asList("123-45-6789"));
        m.put("customers.email_address", Arrays.asList("a@x.com"));
        m.put("customers.customer_name", Arrays.asList("Alice"));
        m.put("products.product_name", Arrays.asList("Widget"));
        return m;
    }

    /** Provider that records every request it receives. */
    private static final class RecordingProvider implements LlmProvider {
        final List<EnrichRequest> seen = new ArrayList<>();
        private final LlmProvider delegate = new NoopProvider();

        @Override
        public EnrichResponse enrich(EnrichRequest request) {
            seen.add(request);
            return delegate.enrich(request);
        }
    }

    @Test
    public void returnsNonEmptySuggestionSetFromBothCubes() {
        LlmEnricher enr = new LlmEnricher(new NoopProvider(), new PiiFilter(), 2);
        SuggestionSet set = enr.enrich(twoCubeSchema(), samples());

        assertNotNull(set);
        assertFalse("ops must not be empty", set.ops().isEmpty());
        assertFalse("set should not be degraded for a clean run", set.degraded());

        // Stable-id paths: cube segment is the fact-table name ("orders", "stock"), not the
        // cube's user-visible name.
        boolean sawSales = false;
        boolean sawInventory = false;
        for (SuggestionOp op : set.ops()) {
            String path = op.targetPath();
            if (path.contains("cubes/orders")) sawSales = true;
            if (path.contains("cubes/stock")) sawInventory = true;
        }
        assertTrue("ops from sales cube", sawSales);
        assertTrue("ops from inventory cube", sawInventory);
    }

    @Test
    public void piiColumnsAreAbsentFromWhatProviderSees() {
        RecordingProvider rec = new RecordingProvider();
        LlmEnricher enr = new LlmEnricher(rec, new PiiFilter(), 2);
        enr.enrich(twoCubeSchema(), samples());

        assertFalse("provider should be called", rec.seen.isEmpty());
        for (EnrichRequest req : rec.seen) {
            assertFalse("ssn leaked to provider", req.columnSamples().containsKey("customers.ssn"));
            assertFalse("email_address leaked to provider", req.columnSamples().containsKey("customers.email_address"));
        }
    }

    @Test
    public void chunksPerCube() {
        RecordingProvider rec = new RecordingProvider();
        LlmEnricher enr = new LlmEnricher(rec, new PiiFilter(), 2);
        enr.enrich(twoCubeSchema(), samples());

        // At least one call per cube (shared-dim call is allowed in addition).
        int salesCalls = 0;
        int invCalls = 0;
        for (EnrichRequest req : rec.seen) {
            boolean hasSales = false;
            boolean hasInv = false;
            for (DraftCube c : req.draft().cubes()) {
                if ("sales".equals(c.name())) hasSales = true;
                if ("inventory".equals(c.name())) hasInv = true;
            }
            if (hasSales) salesCalls++;
            if (hasInv) invCalls++;
            // No sub-request should contain multiple cubes — per-cube chunking.
            assertTrue("request carries at most one cube", req.draft().cubes().size() <= 1);
        }
        assertEquals(1, salesCalls);
        assertEquals(1, invCalls);
    }

    /** Throws every call. */
    private static final class AlwaysThrowingProvider implements LlmProvider {
        int calls = 0;

        @Override
        public EnrichResponse enrich(EnrichRequest request) {
            calls++;
            throw new RuntimeException("boom");
        }
    }

    @Test
    public void fallbackKicksInAndMarksDegraded() {
        AlwaysThrowingProvider bad = new AlwaysThrowingProvider();
        LlmEnricher enr = new LlmEnricher(bad, new PiiFilter(), 2);
        SuggestionSet set = enr.enrich(twoCubeSchema(), samples());

        assertTrue("degraded must be true after fallback", set.degraded());
        assertFalse("fallback still produces ops", set.ops().isEmpty());
    }

    /** Fails N times then succeeds. */
    private static final class FlakyProvider implements LlmProvider {
        final int failuresBeforeSuccess;
        int calls = 0;
        private final LlmProvider delegate = new NoopProvider();

        FlakyProvider(int n) {
            this.failuresBeforeSuccess = n;
        }

        @Override
        public EnrichResponse enrich(EnrichRequest request) {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new RuntimeException("transient");
            }
            return delegate.enrich(request);
        }
    }

    @Test
    public void retriesThenSucceeds() {
        // maxRetries=2 → up to 3 calls per cube. Fail once, succeed on 2nd, then same for cube 2.
        FlakyProvider flaky = new FlakyProvider(1);
        LlmEnricher enr = new LlmEnricher(flaky, new PiiFilter(), 2);
        SuggestionSet set = enr.enrich(twoCubeSchema(), samples());

        // 2 cubes: cube 1 fails once then succeeds = 2 calls; cube 2 starts fresh — does it also
        // fail once? With a shared counter: cube 2 will succeed on first try (calls=3, >1). That
        // gives total 3. We care that retries happened AND succeeded (no fallback).
        assertFalse("not degraded — retry succeeded", set.degraded());
        assertTrue("expected retries", flaky.calls >= 2);
    }

    /** Null suggestions on first call, then success. */
    private static final class NullThenOkProvider implements LlmProvider {
        int calls = 0;
        private final LlmProvider delegate = new NoopProvider();

        @Override
        public EnrichResponse enrich(EnrichRequest request) {
            calls++;
            if (calls == 1) {
                // Malformed: suggestions null. Constructor of EnrichResponse rejects null so
                // simulate by throwing NullPointerException — enricher treats any throw as
                // malformed.
                throw new NullPointerException("null suggestions");
            }
            return delegate.enrich(request);
        }
    }

    @Test
    public void malformedResponseIsRetried() {
        NullThenOkProvider p = new NullThenOkProvider();
        LlmEnricher enr = new LlmEnricher(p, new PiiFilter(), 2);
        SuggestionSet set = enr.enrich(twoCubeSchema(), samples());
        assertFalse(set.degraded());
        assertTrue(p.calls >= 2);
    }

    @Test
    public void retriesExactlyMaxPlusOneBeforeFallback() {
        // maxRetries = 2 → 3 attempts per cube then fallback.
        AlwaysThrowingProvider bad = new AlwaysThrowingProvider();
        LlmEnricher enr = new LlmEnricher(bad, new PiiFilter(), 2);
        enr.enrich(twoCubeSchema(), samples());
        // 2 cubes × 3 attempts = 6 (shared-dim path has no shared dims in our schema, so no extra).
        assertEquals(6, bad.calls);
    }
}
