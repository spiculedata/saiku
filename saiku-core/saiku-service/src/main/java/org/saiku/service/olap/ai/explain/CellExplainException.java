/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.explain;

/**
 * A caller-fixable failure of {@code POST /saiku/api/ai/explain}, carrying the HTTP status the
 * resource should answer with and a stable machine code so the panel can say something more useful
 * than "failed".
 */
public class CellExplainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** The session has no query under that name. */
        UNKNOWN_QUERY(404, "UNKNOWN_QUERY"),
        /** The query exists but has not been executed, so there is no cell to explain. */
        NOT_EXECUTED(409, "NOT_EXECUTED"),
        /** Malformed body, or a coordinate outside the cellset. */
        VALIDATION_ERROR(400, "VALIDATION_ERROR"),
        /** The cell exists but the explain pipeline itself blew up. */
        EXPLAIN_FAILED(500, "EXPLAIN_FAILED");

        private final int status;
        private final String code;

        Code(int status, String code) {
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final Code code;

    public CellExplainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public CellExplainException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
