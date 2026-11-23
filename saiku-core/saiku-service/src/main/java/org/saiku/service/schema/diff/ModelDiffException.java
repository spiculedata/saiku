/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

/**
 * Raised for every rejected model-diff request (saiku#1434).
 *
 * <p>The {@link Reason} is a stable machine-readable code — the REST layer maps it onto a 400
 * body, the CLI maps it onto stderr + a non-zero exit code, and the fuzz tests assert on it. A
 * diff over input we cannot parse is an error, never an empty diff: silently reporting "no
 * changes" for a truncated schema is exactly the failure mode the validator exists to prevent.
 */
public class ModelDiffException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The payload matches neither Mondrian XML nor Ossie YAML. */
        UNKNOWN_FORMAT,
        /** The payload claims a format but does not parse as it. */
        MALFORMED,
        /** Both sides parse, but not to the same format. Cross-format diffing is rejected. */
        CROSS_FORMAT
    }

    private final Reason reason;

    public ModelDiffException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModelDiffException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
