/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

/**
 * @author Tomasz Nurkiewicz
 * @since 1/15/13, 10:29 AM
 */
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.batik.transcoder.*;
import org.apache.batik.transcoder.image.JPEGTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.image.TIFFTranscoder;
import org.apache.commons.io.IOUtils;
import org.apache.fop.svg.PDFTranscoder;

public abstract class Converter {

    public abstract void convert(InputStream in, OutputStream out, Integer size)
            throws IOException, TranscoderException;

    private final String contentType;
    private final String extension;

    Converter(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String getContentType() {
        return contentType;
    }

    public String getExtension() {
        return extension;
    }

    public static Converter byType(String type) {
        if (type.equals("SVG")) {
            return new SvgConverter();
        }
        if (type.equals("PNG")) {
            return new PngConverter();
        }
        if (type.equals("TIFF")) {
            return new TiffConverter();
        }
        if (type.equals("JPG")) {
            return new JpgConverter();
        }
        if (type.equals("PDF")) {
            return new PdfConverter();
        }
        return null;
    }
}

class SvgConverter extends Converter {

    public SvgConverter() {
        super("image/svg+xml", "svg");
    }

    public void convert(InputStream in, OutputStream out, Integer size) throws IOException {
        IOUtils.copy(in, out);
    }
}

abstract class BatikConverter extends Converter {

    BatikConverter(String extension) {
        super("image/" + extension, extension);
    }

    BatikConverter(String contentType, String extension) {
        super(contentType, extension);
    }

    /** saiku#1165: cap the in-memory SVG so a giant payload can't OOM the server. */
    private static final int MAX_SVG_BYTES = 20 * 1024 * 1024;

    /** saiku#2527: the output raster is bounded per side, not only by SVG bytes. */
    private static final float MAX_WIDTH = rasterBound("saiku.export.chart.maxWidth", 2000);

    private static final float MAX_HEIGHT = rasterBound("saiku.export.chart.maxHeight", 2000);

    /**
     * saiku#2527: the transcode runs on a small fixed pool with a wall-clock
     * limit, the same shape as the snapshot renderer's bounded pool, so a
     * pathological (but byte-legal) SVG stops occupying the request thread.
     * Batik may continue running in the pool after a timeout until it returns.
     */
    private static final int TRANSCODE_TIMEOUT_MS = timeoutMillis();

    private static final ExecutorService TRANSCODE_POOL = newPool();

    public void convert(InputStream in, OutputStream out, Integer size) throws TranscoderException, IOException {
        // saiku#1165: read the SVG, reject external references / DOCTYPE (SSRF /
        // local-file-read / XXE via Batik), then transcode from the validated copy.
        byte[] svg = in.readNBytes(MAX_SVG_BYTES + 1);
        if (svg.length > MAX_SVG_BYTES) {
            throw new TranscoderException("SVG exceeds the " + MAX_SVG_BYTES + "-byte export limit");
        }
        String document = new String(svg, StandardCharsets.UTF_8);
        SvgSecurity.requireSafe(document);

        // saiku#2527: resolve the raster size before anything is allocated, so an
        // oversized request is refused with a typed 4xx instead of an OOM.
        float[] target = SvgGeometry.target(SvgGeometry.parse(document), size, MAX_WIDTH, MAX_HEIGHT);

        Transcoder t = createTranscoder();
        // Defense-in-depth: never run on-load scripts during transcode.
        t.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
        // Always set BOTH hints: with only KEY_WIDTH (the old behaviour) the height
        // followed the aspect ratio past any bound, and with neither hint Batik
        // took the SVG's own width/height verbatim.
        t.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, target[0]);
        t.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, target[1]);

        final TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svg));
        final TranscoderOutput output = new TranscoderOutput(out);
        runBounded(() -> {
            t.transcode(input, output);
            return null;
        });
    }

    private static void runBounded(java.util.concurrent.Callable<Void> work) throws TranscoderException {
        runBounded(work, TRANSCODE_TIMEOUT_MS, TRANSCODE_POOL);
    }

    /**
     * Bounded transcode (saiku#2527, review of PR #2559): the deadline covers
     * queueing and execution. If it expires before a worker starts the task,
     * server-side saturation answers {@code 503}; if the task started, an overrun
     * answers {@code 400}. The overload lets tests exercise both mappings without
     * competing with the shared pool.
     */
    static void runBounded(java.util.concurrent.Callable<Void> work, long timeoutMs, ExecutorService pool)
            throws TranscoderException {
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
        Future<Void> future;
        try {
            future = pool.submit(() -> {
                started.set(true);
                return work.call();
            });
        } catch (RejectedExecutionException e) {
            throw new SvgExportException(503, "chart export is at capacity; try again shortly");
        }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            if (!started.get()) {
                throw new SvgExportException(503, "chart export is at capacity; try again shortly");
            }
            throw new SvgExportException(400, "chart export exceeded the " + timeoutMs + "ms time limit");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TranscoderException("chart export was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TranscoderException te) {
                throw te;
            }
            if (cause instanceof SvgExportException se) {
                throw se;
            }
            throw new TranscoderException(
                    String.valueOf(cause),
                    cause instanceof Exception ? (Exception) cause : new IllegalStateException(cause));
        }
    }

    /** One raster bound from a system property, defensively clamped to a sane range. */
    private static float rasterBound(String property, int defaultPx) {
        int v = Integer.getInteger(property, defaultPx);
        return Math.max(32, Math.min(v, 10000));
    }

    private static int timeoutMillis() {
        int v = Integer.getInteger("saiku.export.chart.timeoutMillis", 30_000);
        return Math.max(1_000, Math.min(v, 120_000));
    }

    private static ExecutorService newPool() {
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "saiku-chart-export");
            t.setDaemon(true);
            return t;
        };
        // Small fixed pool, short queue: saturation is refused (503) rather than
        // silently queueing an unbounded number of pathological transcodes.
        return new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), tf);
    }

    protected abstract Transcoder createTranscoder();
}

class PngConverter extends BatikConverter {

    public PngConverter() {
        super("png");
    }

    protected Transcoder createTranscoder() {
        return new PNGTranscoder();
    }
}

class JpgConverter extends BatikConverter {

    public JpgConverter() {
        super("image/jpeg", "jpg");
    }

    protected Transcoder createTranscoder() {
        Transcoder t = new JPEGTranscoder();
        t.addTranscodingHint(JPEGTranscoder.KEY_QUALITY, 0.95f);
        return t;
    }
}

class TiffConverter extends BatikConverter {

    public TiffConverter() {
        super("tiff");
    }

    protected Transcoder createTranscoder() {
        return new TIFFTranscoder();
    }
}

class PdfConverter extends BatikConverter {

    public PdfConverter() {
        super("application/pdf", "pdf");
    }

    protected Transcoder createTranscoder() {
        return new PDFTranscoder();
    }
}
