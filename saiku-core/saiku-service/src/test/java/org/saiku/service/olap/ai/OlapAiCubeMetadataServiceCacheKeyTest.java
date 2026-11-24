/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.olap.OlapDiscoverService;

/**
 * saiku#2539 — the AI schema cache key must be normalised to the RESOLVED
 * cube's canonical identifiers, and both caches must be bounded.
 *
 * <p>Before the fix the key was built from the caller's spelling of
 * connection/catalog/schema/cube while the cube lookup (see
 * {@code OlapAiCubeMetadataService#matchesRef}) is case-insensitive with
 * wildcard catalog/schema. Every case variant of one cube was therefore its own
 * cold build plus its own ~40 KB cache entry in an unbounded ConcurrentHashMap —
 * a heap-growth vector for any logged-in user, also through MCP
 * {@code describe_cube}. Reverting the fix flips the case-variant and wildcard
 * tests here (a second cold build would be counted) and the bound test (a fill
 * past the maximum size must rebuild the evicted cube instead of holding every
 * entry).
 */
public class OlapAiCubeMetadataServiceCacheKeyTest {

    /** Counts full schema builds — the "cold build" signal the AC counts. */
    private final AtomicInteger buildCount = new AtomicInteger();

    private CountingDiscover discover;
    private OlapAiCubeMetadataService svc;

    @Before
    public void setUp() {
        svc = new OlapAiCubeMetadataService();
        discover = new CountingDiscover();
        svc.setDiscoverService(discover);
    }

    private int builds() {
        return buildCount.get();
    }

    /**
     * AC 1: the same cube spelled differently in every component — one cold
     * build, one shared entry, one identical schema instance.
     */
    @Test
    public void caseVariantsShareOneColdBuild() {
        AiSchema first = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals("first describe is a cold build", 1, builds());

        AiSchema second = svc.getSchema(new AiCubeRef("foodMART", "FoodMart", "FoodMart", "sAlEs"));
        AiSchema third = svc.getSchema(new AiCubeRef("FOODMART", "FoodMart", "FoodMart", "SALES"));
        assertEquals("no case variant may trigger a second cold build", 1, builds());
        assertSame("all spellings share one cache entry", first, second);
        assertSame(first, third);
    }

    /**
     * AC 1: a wildcard catalog/schema (null → treated as a match-anything by
     * the resolver) lands on the same entry as the fully-spelled request.
     */
    @Test
    public void wildcardCatalogAndSchemaShareOneColdBuild() {
        AiSchema spelled = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals(1, builds());

        AiSchema wildcard = svc.getSchema(new AiCubeRef("foodmart", null, null, "SALES"));
        assertEquals("the wildcard spelling must reuse the resolved cube's entry", 1, builds());
        assertSame(spelled, wildcard);
    }

    /**
     * The connection's case is normalised too: the canonical connection name
     * (and, through it, the #2326 visibility fingerprint) comes from the
     * resolved cube, not the caller's spelling.
     */
    @Test
    public void connectionCaseVariantsShareOneColdBuild() {
        svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        svc.getSchema(new AiCubeRef("FOODMART", "FoodMart", "FoodMart", "Sales"));
        assertEquals("connection case variants share one entry", 1, builds());
    }

    /** The first spelling to fill the shared cache must load enrichment using
     *  the resolved connection name, as a case-sensitive sidecar store does. */
    @Test
    public void caseVariantFirstStillCachesCanonicalEnrichment() {
        AiSchemaEnrichment overlay = new AiSchemaEnrichment();
        overlay.getRenames().put("measures.M", "Revenue");
        AtomicInteger providerCalls = new AtomicInteger();
        List<AiCubeRef> providerRefs = new ArrayList<>();
        svc.setEnrichmentProvider(ref -> {
            providerCalls.incrementAndGet();
            providerRefs.add(ref);
            return "foodmart".equals(ref.getConnectionName()) ? overlay : null;
        });

        AiSchema first = svc.getSchema(new AiCubeRef("FOODMART", "foodmart", "FOODMART", "sales"));
        AiSchema canonical = svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));

        assertSame("both spellings must share the enriched entry", first, canonical);
        assertEquals("Revenue", canonical.measures.get(AiSchema.key("M")).displayName);
        assertEquals(1, builds());
        assertEquals("the provider runs only for the cold build", 1, providerCalls.get());
        assertEquals("foodmart", providerRefs.get(0).getConnectionName());
        assertEquals("FoodMart", providerRefs.get(0).getCatalog());
        assertEquals("FoodMart", providerRefs.get(0).getSchema());
        assertEquals("Sales", providerRefs.get(0).getCubeName());
    }

    /**
     * AC 2: the cache is bounded — with a maximum size of 1, describing a
     * second cube must evict the first, and asking for the first again must
     * rebuild instead of the cache having grown to two entries.
     */
    @Test
    public void cachesAreBounded_fillPastMaximumSizeRebuildsOldest() {
        OlapAiCubeMetadataService bounded = new OlapAiCubeMetadataService(1, Duration.ofMinutes(30));
        bounded.setDiscoverService(discover);

        AiCubeRef sales = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        AiCubeRef hr = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "HR");
        bounded.getSchema(sales); // cold build 1
        bounded.getSchema(hr); // cold build 2 — the second cube forces the first out (max 1)
        bounded.schemaCache.cleanUp();

        bounded.getSchema(sales); // must rebuild, not serve a stale growth
        bounded.schemaCache.cleanUp();
        assertEquals("fill past the bound rebuilds the evicted cube", 3, builds());
        assertTrue("the cache never holds more than its bound", bounded.schemaCache.estimatedSize() <= 1);
    }

    /** AC 2: entries expire — after the TTL the cube is rebuilt, not served. */
    @Test
    public void entriesExpireAfterTheTtl() {
        MutableTicker ticker = new MutableTicker();
        OlapAiCubeMetadataService ticking = new OlapAiCubeMetadataService(8, Duration.ofMinutes(30), ticker);
        ticking.setDiscoverService(discover);

        AiCubeRef ref = new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales");
        AiSchema fresh = ticking.getSchema(ref);
        assertEquals(1, builds());

        ticker.advance(Duration.ofMinutes(5));
        assertSame("inside the TTL the entry is still served", fresh, ticking.getSchema(ref));

        ticker.advance(Duration.ofMinutes(31));
        assertNotSame("past the TTL the entry expires and rebuilds", fresh, ticking.getSchema(ref));
        assertEquals(2, builds());
    }

    /** saiku#2326 separation preserved with the normalised key: two callers
     *  with different visibility fingerprints get DIFFERENT entries for the
     *  same cube — even when one spells it in another case. */
    @Test
    public void visibilityStillSeparatesAcrossCaseVariants() {
        svc.setAiVisibilityContext(connectionName -> "roles:ROLE_ADMIN");
        svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        assertEquals(1, builds());

        svc.setAiVisibilityContext(connectionName -> "roles:ROLE_USER");
        svc.getSchema(new AiCubeRef("FOODMART", "FoodMart", "FoodMart", "sAlEs"));
        assertEquals("a different fingerprint is a different entry", 2, builds());
    }

    /** saiku#2539 sanity: the PII suggestions cache is keyed identically —
     *  a case variant finds the scan stored under the canonical key. */
    @Test
    public void piiSuggestionsResolveAcrossCaseVariants() {
        svc.getSchema(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        List<PiiScanner.Match> fromCanonical =
                svc.getPiiSuggestions(new AiCubeRef("foodmart", "FoodMart", "FoodMart", "Sales"));
        List<PiiScanner.Match> fromVariant =
                svc.getPiiSuggestions(new AiCubeRef("FOODMART", "FoodMart", "FoodMart", "SALES"));
        assertEquals("case variants see the same scan", fromCanonical, fromVariant);
        assertEquals("the scan ran once, on the schema build", 1, builds());
    }

    /** Discover stub over the two fixed FoodMart cubes that counts every full
     *  schema build (each build runs {@link #getAllDimensions} exactly once). */
    private class CountingDiscover extends OlapDiscoverService {

        private SaikuCube cube(String name) {
            return new SaikuCube("foodmart", "[FoodMart].[" + name + "]", name, name, "FoodMart", "FoodMart");
        }

        @Override
        public List<SaikuCube> getAllCubes() throws SaikuOlapException {
            return Arrays.asList(cube("Sales"), cube("HR"));
        }

        @Override
        public List<SaikuMember> getMeasures(SaikuCube cube) {
            return Arrays.asList(new SaikuMember(
                    "M",
                    "[Measures].[M]",
                    "M",
                    "",
                    "[Measures]",
                    "[Measures].[MeasuresLevel]",
                    "[Measures].[MeasuresLevel]"));
        }

        @Override
        public List<SaikuDimension> getAllDimensions(SaikuCube cube) {
            buildCount.incrementAndGet();
            // SaikuDimension(name, uniqueName, caption, description, visible, hierarchies)
            return Arrays.asList(new SaikuDimension("D", "[D]", "D", "", true, new ArrayList<>()));
        }

        @Override
        public List<SaikuHierarchy> getAllDimensionHierarchies(SaikuCube cube, String dimension) {
            // SaikuHierarchy(name, uniqueName, caption, description, dimUniq, visible, levels, rootmembers)
            return Arrays.asList(
                    new SaikuHierarchy("H", "[D].[H]", "H", "", "[D]", true, new ArrayList<>(), new ArrayList<>()));
        }

        @Override
        public List<SaikuLevel> getAllHierarchyLevels(SaikuCube cube, String dimension, String hierarchy) {
            // SaikuLevel(name, uniqueName, caption, description, dimUniq, hierUniq, visible, levelType, annotations)
            return Arrays.asList(new SaikuLevel(
                    "L", "[D].[H].[L]", "L", "", "[D]", "[D].[H]", true, "Regular", new java.util.HashMap<>()));
        }
    }

    /** A controllable clock for the expiry test (nanoseconds since an epoch). */
    private static class MutableTicker implements com.github.benmanes.caffeine.cache.Ticker {

        private long nanos = 0;

        void advance(Duration d) {
            nanos += d.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}
