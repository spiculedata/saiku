/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.generate.quickstart;

/**
 * Thrown for a CSV upload the quickstart pipeline cannot make sense of — an empty file, a missing
 * header, a ragged row, or a table-name collision. The message is written to be shown to the
 * end user verbatim (see saiku#1117's "bad CSV surfaces a usable error" test-plan item), so it
 * never carries a stack trace or internal class names.
 */
public class CsvIngestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CsvIngestException(String message) {
        super(message);
    }

    public CsvIngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
