/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.saiku.service.snapshot.SnapshotReference.SnapshotPanel;

/**
 * End-to-end behaviour of the headless renderer: formats produced with no browser, owner identity
 * honoured, and the time/resource caps enforced (saiku#1810).
 */
public class SnapshotRenderServiceTest {

    private static final byte[] KEY = "test-install-key-material".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000_000L;

    private final SnapshotReferenceSigner signer = new SnapshotReferenceSigner(KEY);
    private ExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private static SnapshotReference reference(String owner, int panels, long expiresAt) {
        SnapshotReference.Builder b = new SnapshotReference.Builder()
                .owner(owner)
                .dashboardPath("shared/exec.saikudash")
                .title("Executive Overview")
                .expiresAtEpochMillis(expiresAt);
        for (int i = 0; i < panels; i++) {
            b.panel(new SnapshotPanel("Metric " + i, "foodmart/Sales/Foodmart/Sales_Cube", "Unit Sales", Map.of()));
        }
        return b.build();
    }

    /** A clock pinned to {@link #NOW} so the fixed expiry in the test tokens is deterministic. */
    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneOffset.UTC);

    private SnapshotRenderService service(MeasureValueReader reader, SnapshotLimits limits) {
        return new SnapshotRenderService(
                signer,
                reader,
                new PdfSnapshotRenderer(),
                new PngSnapshotRenderer(),
                limits == null ? SnapshotRenderService.defaultLimits() : limits,
                executor,
                CLOCK);
    }

    /** The production (owning-pool) ctor, with the clock pinned so the test tokens are valid. */
    private SnapshotRenderService ownedService(MeasureValueReader reader, SnapshotLimits limits) {
        return new SnapshotRenderService(
                signer,
                reader,
                new PdfSnapshotRenderer(),
                new PngSnapshotRenderer(),
                limits,
                java.util.concurrent.Executors.newCachedThreadPool(),
                CLOCK);
    }

    private static MeasureValueReader constant(double value) {
        return (cube, measure, filters) -> value;
    }

    // ---- formats, no browser ----

    @Test
    public void rendersPdfWithNoBrowserAttached() {
        String token = signer.sign(reference("alice", 3, NOW + 60_000));
        SnapshotRenderService.SnapshotArtifact art =
                service(constant(1234.5), null).render(token, "alice", SnapshotFormat.PDF);

        assertEquals(SnapshotFormat.PDF, art.format());
        assertEquals("application/pdf", art.mediaType());
        assertTrue("PDF must carry the %PDF- magic", art.size() > 4);
        String head = new String(art.bytes(), 0, 5, StandardCharsets.ISO_8859_1);
        assertEquals("%PDF-", head);
        assertTrue(art.fileName().endsWith(".pdf"));
    }

    @Test
    public void rendersPngWithNoBrowserAttached() {
        org.junit.Assume.assumeTrue(
                "no fonts available to the JVM; skipping PNG assertion", SnapshotRendererTest.fontsAvailableForTest());
        String token = signer.sign(reference("alice", 4, NOW + 60_000));
        SnapshotRenderService.SnapshotArtifact art =
                service(constant(42), null).render(token, "alice", SnapshotFormat.PNG);

        assertEquals(SnapshotFormat.PNG, art.format());
        assertEquals("image/png", art.mediaType());
        assertTrue("PNG must carry the 8-byte signature", art.size() > 8);
        int[] sig = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) {
            assertEquals("PNG signature byte " + i, sig[i], art.bytes()[i] & 0xff);
        }
        assertTrue(art.fileName().endsWith(".png"));
    }

    // ---- owner identity ----

    @Test
    public void anotherUsersReferenceIsRejected() {
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        SnapshotReferenceException e = assertThrows(SnapshotReferenceException.class, () -> service(constant(1), null)
                .render(token, "bob", SnapshotFormat.PDF));
        assertEquals("snapshot reference does not belong to the authenticated owner", e.getMessage());
    }

    @Test
    public void anAnonymousRenderIsRejected_failClosed() {
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        assertThrows(SnapshotReferenceException.class, () -> service(constant(1), null)
                .render(token, null, SnapshotFormat.PDF));
        assertThrows(SnapshotReferenceException.class, () -> service(constant(1), null)
                .render(token, "  ", SnapshotFormat.PDF));
    }

    @Test
    public void noMeasureIsReadWhenTheReferenceFailsVerification() {
        AtomicInteger reads = new AtomicInteger();
        MeasureValueReader counting = (cube, measure, filters) -> {
            reads.incrementAndGet();
            return 1;
        };
        // A tampered token must be refused BEFORE any cube read happens.
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        String tampered = token.substring(0, token.length() - 3) + "AAA";

        assertThrows(SnapshotReferenceException.class, () -> service(counting, null)
                .render(tampered, "alice", SnapshotFormat.PDF));
        assertEquals("no query may run before the signature verifies", 0, reads.get());
    }

    @Test
    public void theReadHappensUnderTheCallersCubeAndMeasure_noReInterpretation() {
        String token = signer.sign(new SnapshotReference(
                "alice",
                "shared/exec.saikudash",
                "T",
                List.of(new SnapshotPanel(
                        "Store Sales",
                        "foodmart/Sales/Foodmart/Sales_Cube",
                        "Store Sales",
                        Map.of("Time[Year]", List.of("[2001]")))),
                NOW + 60_000));

        List<AiCubeRef> seenCubes = new java.util.ArrayList<>();
        List<String> seenMeasures = new java.util.ArrayList<>();
        List<List<AiFilterSelection>> seenFilters = new java.util.ArrayList<>();
        MeasureValueReader recording = (cube, measure, filters) -> {
            seenCubes.add(cube);
            seenMeasures.add(measure);
            seenFilters.add(filters);
            return 7;
        };

        service(recording, null).render(token, "alice", SnapshotFormat.PDF);

        assertEquals(1, seenCubes.size());
        assertEquals("foodmart", seenCubes.get(0).getConnectionName());
        assertEquals("Sales", seenCubes.get(0).getCatalog());
        assertEquals("Sales_Cube", seenCubes.get(0).getCubeName());
        assertEquals("Store Sales", seenMeasures.get(0));
        assertEquals(1, seenFilters.get(0).size());
        assertEquals("Time[Year]", seenFilters.get(0).get(0).getDimension());
        assertEquals("in", seenFilters.get(0).get(0).getOp());
        assertEquals(List.of("[2001]"), seenFilters.get(0).get(0).getMembers());
    }

    // ---- bounds ----

    @Test
    public void aPathologicalDashboardTimesOutCleanly() {
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        MeasureValueReader slow = (cube, measure, filters) -> {
            Thread.sleep(30_000);
            return 1;
        };
        SnapshotLimits tight = new SnapshotLimits(64, 150, 1024 * 1024);

        long start = System.nanoTime();
        SnapshotRenderException e = assertThrows(
                SnapshotRenderException.class, () -> service(slow, tight).render(token, "alice", SnapshotFormat.PDF));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("snapshot render timed out", e.getMessage());
        assertTrue("must give up near the timeout, not run 30s (was " + elapsedMs + "ms)", elapsedMs < 5_000);
    }

    @Test
    public void thePanelCapIsEnforced() {
        String token = signer.sign(reference("alice", 5, NOW + 60_000));
        SnapshotLimits tight = new SnapshotLimits(2, 30_000, 1024 * 1024);

        SnapshotRenderException e = assertThrows(SnapshotRenderException.class, () -> service(constant(1), tight)
                .render(token, "alice", SnapshotFormat.PDF));
        assertEquals("snapshot reference exceeds the panel limit", e.getMessage());
    }

    @Test
    public void theOutputByteCapIsEnforced() {
        String token = signer.sign(reference("alice", 2, NOW + 60_000));
        SnapshotLimits tight = new SnapshotLimits(64, 30_000, 8); // smaller than any real artifact

        SnapshotRenderException e = assertThrows(SnapshotRenderException.class, () -> service(constant(1), tight)
                .render(token, "alice", SnapshotFormat.PDF));
        assertEquals("snapshot output exceeded the size limit", e.getMessage());
    }

    @Test
    public void aFailingReadIsSanitizedAndNotLeaked() {
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        MeasureValueReader broken = (cube, measure, filters) -> {
            throw new IllegalStateException("jdbc:postgresql://user:pw@10.0.0.5:5432/secret failed");
        };

        SnapshotRenderException e = assertThrows(
                SnapshotRenderException.class, () -> service(broken, null).render(token, "alice", SnapshotFormat.PDF));
        assertEquals("snapshot render failed", e.getMessage());
        assertTrue(
                "the cause's message must not reach the caller",
                !String.valueOf(e.getMessage()).contains("jdbc"));
    }

    @Test
    public void aMissingFormatIsRejected() {
        String token = signer.sign(reference("alice", 1, NOW + 60_000));
        assertThrows(
                SnapshotRenderException.class, () -> service(constant(1), null).render(token, "alice", null));
    }

    @Test
    public void theOwningCtorRendersAndShutsDownCleanly() {
        // The production wiring path: no executor supplied, the service builds its own bounded pool
        // and stops it on shutdown.
        SnapshotRenderService svc = ownedService(constant(99), new SnapshotLimits(8, 5_000, 1024 * 1024));
        try {
            String token = signer.sign(reference("alice", 2, NOW + 60_000));
            SnapshotRenderService.SnapshotArtifact art = svc.render(token, "alice", SnapshotFormat.PDF);
            assertEquals("%PDF-", new String(art.bytes(), 0, 5, StandardCharsets.ISO_8859_1));
        } finally {
            svc.shutdown();
        }
    }

    @Test
    public void aSaturatedPoolFailsCleanlyRatherThanQueueing() throws InterruptedException {
        // Fixed pool + a short queue + AbortPolicy: once saturated, render() reports capacity
        // instead of piling work onto the scheduler thread's behalf.
        java.util.concurrent.ThreadPoolExecutor tiny = new java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                r -> {
                    Thread t = new Thread(r, "tiny");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        try {
            SnapshotRenderService svc = new SnapshotRenderService(
                    signer,
                    (cube, measure, filters) -> {
                        Thread.sleep(2_000);
                        return 1;
                    },
                    new PdfSnapshotRenderer(),
                    new PngSnapshotRenderer(),
                    new SnapshotLimits(64, 30_000, 1024 * 1024),
                    tiny,
                    CLOCK);
            String token = signer.sign(reference("alice", 1, NOW + 60_000));
            // Occupy the single worker AND the single queue slot with tasks that actually block, so
            // the pool is genuinely saturated when the render arrives.
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            Runnable blocker = () -> {
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            java.util.concurrent.Future<?> a = tiny.submit(blocker);
            java.util.concurrent.Future<?> b = tiny.submit(blocker);
            // Wait for the first to actually be running before assuming the pool is full.
            for (int i = 0; i < 100 && tiny.getActiveCount() < 1; i++) {
                Thread.sleep(10);
            }
            SnapshotRenderException e =
                    assertThrows(SnapshotRenderException.class, () -> svc.render(token, "alice", SnapshotFormat.PDF));
            assertEquals("snapshot renderer is at capacity", e.getMessage());
            release.countDown();
            a.cancel(true);
            b.cancel(true);
        } finally {
            tiny.shutdownNow();
        }
    }

    @Test
    public void defaultLimitsAreSane() {
        SnapshotLimits limits = SnapshotRenderService.defaultLimits();
        assertEquals(64, limits.maxPanels());
        assertEquals(30_000L, limits.timeoutMillis());
        assertTrue(limits.maxOutputBytes() >= 1024 * 1024);
    }

    @Test
    public void formatParsingIsCaseInsensitiveAndTotal() {
        assertEquals(SnapshotFormat.PDF, SnapshotFormat.parse("pdf"));
        assertEquals(SnapshotFormat.PNG, SnapshotFormat.parse(" PNG "));
        assertNotNull(SnapshotFormat.parse("Pdf"));
        org.junit.Assert.assertNull(SnapshotFormat.parse("gif"));
        org.junit.Assert.assertNull(SnapshotFormat.parse(null));
        org.junit.Assert.assertNull(SnapshotFormat.parse(""));
    }
}
