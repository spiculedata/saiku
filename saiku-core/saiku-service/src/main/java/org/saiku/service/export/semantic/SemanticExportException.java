/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.semantic;

/** Thrown when an Ossie model can't be rendered into the requested BI-tool export format. */
public class SemanticExportException extends RuntimeException {

    public SemanticExportException(String message) {
        super(message);
    }

    public SemanticExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
