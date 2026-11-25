/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

/**
 * saiku#2527 — a typed rejection for an export request the raster bounds cannot
 * honour. {@code exportChart} maps it onto the carried HTTP status, so oversized
 * requests get a client-facing {@code 4xx} (or {@code 503} when the bounded
 * transcode pool is saturated) instead of the generic 500 the old
 * {@code catch (Exception)} produced.
 */
public class SvgExportException extends RuntimeException {

    private final int httpStatus;

    public SvgExportException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
