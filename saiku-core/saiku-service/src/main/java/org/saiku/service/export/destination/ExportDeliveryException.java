/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

/**
 * A delivery failure with a <b>sanitized</b> message (saiku#1987).
 *
 * <p>Mirrors how the alert and digest paths report failure: the scheduler records the message,
 * increments the failure count and applies backoff; the admin "test delivery" button shows it to an
 * admin. Because that message can end up in a log file, a run-history file and a browser, it must
 * never contain a token, a private key, a refresh token, an {@code Authorization} header, or a
 * verbatim remote response body.
 *
 * <p>Use {@link #remote(String)} to attribute a failure to the remote without pasting its response
 * in, and {@link #misconfigured(String)} for a config problem. Both are named so the intent is
 * obvious at the call site.
 */
public class ExportDeliveryException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExportDeliveryException(String message) {
        super(message);
    }

    public ExportDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The remote rejected or could not accept the delivery. {@code detail} must already be
     * sanitized — prefer an HTTP status, a Drive error <i>reason</i>, or your own sentence over a
     * response body.
     */
    public static ExportDeliveryException remote(String detail) {
        return new ExportDeliveryException("destination rejected the delivery: " + detail);
    }

    /** The destination's admin config is missing or unusable. Names the field, never its value. */
    public static ExportDeliveryException misconfigured(String detail) {
        return new ExportDeliveryException("export destination is not configured: " + detail);
    }

    /** The destination is not wired in this deployment (no bean, or the service-account key absent). */
    public static ExportDeliveryException unavailable(String detail) {
        return new ExportDeliveryException("export destination unavailable: " + detail);
    }
}
