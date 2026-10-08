/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.schedule.alert.MeasureValueReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The headless dashboard snapshot renderer (saiku#1810) — verify a signed reference, read its panels
 * under the caller's identity, encode a PDF or PNG, all without a browser.
 *
 * <p><b>Order of operations is the security property.</b> The reference is signature-verified
 * <b>first</b>; only then is anything read. There is no code path in which unverified input reaches a
 * query, and no code path in which the renderer opens a socket — the dashboard is resolved from a
 * repository path this install minted itself.
 *
 * <p><b>Owner identity.</b> The signed reference names the owner, and {@link #render} additionally
 * requires the caller's authenticated name to equal it. The measure reads themselves go through the
 * caller's ambient {@code SecurityContext} — this class never establishes, elevates or impersonates
 * an identity. Under the scheduler (saiku#1809) the owner-identity {@code JobRunner} has already put
 * the job owner's context in place, so a snapshot carries exactly the rows that owner could see
 * interactively: no more (RLS) and no fewer.
 *
 * <p><b>Bounded.</b> Three caps, all fail-closed:
 *
 * <ul>
 *   <li>a <b>panel cap</b> on the reference ({@link SnapshotLimits#maxPanels()}),</li>
 *   <li>a <b>wall-clock timeout</b> ({@link SnapshotLimits#timeoutMillis()}) enforced by running the
 *       whole read+encode on a worker, so a pathological dashboard is abandoned rather than wedging
 *       the scheduler thread,</li>
 *   <li>an <b>output byte cap</b> ({@link SnapshotLimits#maxOutputBytes()}) checked before the bytes
 *       are handed back.</li>
 * </ul>
 *
 * <p>A timeout cancels the worker with interruption and returns a {@link SnapshotRenderException};
 * the scheduler records a sanitized FAILED run and keeps ticking. Values are formatted with grouping
 * and up to 4 fraction digits, matching the digest's own formatting.
 */
public final class SnapshotRenderService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRenderService.class);

    private final SnapshotReferenceSigner signer;
    private final MeasureValueReader valueReader;
    private final PdfSnapshotRenderer pdfRenderer;
    private final PngSnapshotRenderer pngRenderer;
    private final SnapshotLimits limits;
    private final ExecutorService executor;
    private final java.time.Clock clock;

    public SnapshotRenderService(
            SnapshotReferenceSigner signer,
            MeasureValueReader valueReader,
            SnapshotLimits limits,
            ExecutorService executor) {
        this(
                signer,
                valueReader,
                new PdfSnapshotRenderer(),
                new PngSnapshotRenderer(),
                limits,
                executor,
                java.time.Clock.systemUTC());
    }

    /** Full ctor with injectable renderers and clock (tests). */
    public SnapshotRenderService(
            SnapshotReferenceSigner signer,
            MeasureValueReader valueReader,
            PdfSnapshotRenderer pdfRenderer,
            PngSnapshotRenderer pngRenderer,
            SnapshotLimits limits,
            ExecutorService executor,
            java.time.Clock clock) {
        if (signer == null || valueReader == null || limits == null || executor == null) {
            throw new IllegalArgumentException("signer, valueReader, limits and executor are required");
        }
        this.signer = signer;
        this.valueReader = valueReader;
        this.pdfRenderer = pdfRenderer == null ? new PdfSnapshotRenderer() : pdfRenderer;
        this.pngRenderer = pngRenderer == null ? new PngSnapshotRenderer() : pngRenderer;
        this.limits = limits;
        this.executor = executor;
        this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    }

    /** The default bounds, sized for a scheduler worker. */
    public static SnapshotLimits defaultLimits() {
        return new SnapshotLimits(64, 30_000L, 8 * 1024 * 1024);
    }

    /**
     * Render the dashboard named by {@code token} as {@code format}, on behalf of {@code
     * authenticatedOwner}.
     *
     * @throws SnapshotReferenceException when the token is unsigned/tampered/expired/off-origin, or
     *     names a different owner
     * @throws SnapshotRenderException on timeout, cap breach, or a read/encode failure
     */
    public SnapshotArtifact render(String token, String authenticatedOwner, SnapshotFormat format) {
        if (format == null) {
            throw new SnapshotRenderException("snapshot format is required");
        }
        if (authenticatedOwner == null || authenticatedOwner.isBlank()) {
            // Fail closed: an anonymous render has no row-level security scope to inherit, so
            // there is nothing safe to snapshot.
            throw new SnapshotReferenceException("snapshot render requires an authenticated owner");
        }
        // (1) Verify BEFORE anything is read.
        SnapshotReference reference = signer.verify(token, clock.millis());
        if (!authenticatedOwner.equals(reference.owner())) {
            // The owner is inside the signed bytes, so a mismatch means the caller is presenting
            // somebody else's reference. Message is fixed; it does not echo either name.
            throw new SnapshotReferenceException("snapshot reference does not belong to the authenticated owner");
        }
        if (reference.panels().size() > limits.maxPanels()) {
            throw new SnapshotRenderException("snapshot reference exceeds the panel limit");
        }

        // (2) Read + encode on a bounded worker.
        final String owner = authenticatedOwner;
        Callable<byte[]> work = () -> {
            List<SnapshotRenderer.Snapshot.Row> rows =
                    new ArrayList<>(reference.panels().size());
            for (SnapshotReference.SnapshotPanel panel : reference.panels()) {
                double value = valueReader.readMeasure(cubeRef(panel.cube()), panel.measure(), toFilters(panel));
                rows.add(new SnapshotRenderer.Snapshot.Row(panel.label(), format(value)));
            }
            String title = (reference.title() == null || reference.title().isBlank())
                    ? stemOf(reference.dashboardPath())
                    : reference.title();
            SnapshotRenderer renderer = format == SnapshotFormat.PNG ? pngRenderer : pdfRenderer;
            return renderer.render(new SnapshotRenderer.Snapshot(title, rows));
        };

        byte[] bytes;
        Future<byte[]> future;
        try {
            future = executor.submit(work);
        } catch (RejectedExecutionException e) {
            // The bounded pool is saturated: refuse rather than run unbounded on the caller's thread.
            throw new SnapshotRenderException("snapshot renderer is at capacity");
        }
        try {
            bytes = future.get(limits.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Snapshot render for owner {} timed out after {}ms", owner, limits.timeoutMillis());
            throw new SnapshotRenderException("snapshot render timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SnapshotRenderException("snapshot render was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SnapshotRenderException sre) {
                throw sre;
            }
            if (cause instanceof SnapshotReferenceException sre) {
                throw sre;
            }
            // Never propagate the cause's message verbatim: it may embed a connection string or an
            // OLAP error containing the query. The scheduler records a sanitized line.
            log.warn("Snapshot render for owner {} failed", owner, cause);
            throw new SnapshotRenderException("snapshot render failed");
        }

        if (bytes == null || bytes.length == 0) {
            throw new SnapshotRenderException("snapshot renderer produced no output");
        }
        if (bytes.length > limits.maxOutputBytes()) {
            throw new SnapshotRenderException("snapshot output exceeded the size limit");
        }
        return new SnapshotArtifact(format, bytes, format.mediaType(), fileNameFor(reference, format));
    }

    /** A rendered artifact: bytes plus everything a mail attachment or webhook payload needs. */
    public record SnapshotArtifact(SnapshotFormat format, byte[] bytes, String mediaType, String fileName) {
        public int size() {
            return bytes == null ? 0 : bytes.length;
        }
    }

    // ---- helpers ----

    /**
     * Ctor that owns its render pool — the production shape, mirroring how {@code JobScheduler}
     * builds its own bounded pools. The pool is small and fixed: a render is a short CPU-bound job,
     * and an unbounded pool would only move the exhaustion to the heap. {@link #shutdown()} is bound
     * to the Spring context close.
     */
    public SnapshotRenderService(
            SnapshotReferenceSigner signer, MeasureValueReader valueReader, SnapshotLimits limits) {
        this(signer, valueReader, limits == null ? defaultLimits() : limits, newPool(limits));
    }

    /**
     * A fixed, bounded, daemon-threaded pool sized off the panel cap: enough workers that one slow
     * render cannot starve the next job, with a short queue so saturation is reported promptly (the
     * service turns rejection into a clean failure rather than queueing without limit).
     */
    private static ExecutorService newPool(SnapshotLimits limits) {
        SnapshotLimits effective = limits == null ? defaultLimits() : limits;
        int workers = Math.max(2, Math.min(4, effective.maxPanels() / 8));
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "saiku-snapshot-render");
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                workers,
                workers,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                tf,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Stop the render pool, interrupting in-flight renders. Bound to the Spring context close. */
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Formats a scalar with grouping + up to 4 fraction digits, matching the digest's formatting. */
    private static final ThreadLocal<java.text.DecimalFormat> VALUE_FORMAT =
            ThreadLocal.withInitial(() -> new java.text.DecimalFormat("#,##0.####"));

    private static String format(double value) {
        return VALUE_FORMAT.get().format(value);
    }

    private static String stemOf(String path) {
        if (path == null || path.isBlank()) {
            return "Dashboard snapshot";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + " snapshot";
    }

    private static String fileNameFor(SnapshotReference reference, SnapshotFormat format) {
        String stem = stemOf(reference.dashboardPath())
                .replaceAll("[^A-Za-z0-9 _.-]", "_")
                .trim();
        if (stem.isEmpty()) {
            stem = "dashboard-snapshot";
        }
        if (stem.length() > 80) {
            stem = stem.substring(0, 80);
        }
        return stem + "." + format.extension();
    }

    private static org.saiku.service.olap.ai.AiCubeRef cubeRef(String cube) {
        String[] parts = cube.split("/", -1);
        return new org.saiku.service.olap.ai.AiCubeRef(parts[0], parts[1], parts[2], parts[3]);
    }

    /**
     * The saved filter state as the {@code "in"} slicers the query path understands. A signed
     * reference only ever carries explicit member selections, so this needs no operator translation.
     */
    private static List<AiFilterSelection> toFilters(SnapshotReference.SnapshotPanel panel) {
        List<AiFilterSelection> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : panel.filters().entrySet()) {
            AiFilterSelection f = new AiFilterSelection();
            f.setDimension(e.getKey());
            f.setOp("in");
            f.setMembers(new ArrayList<>(e.getValue()));
            out.add(f);
        }
        return out;
    }
}
