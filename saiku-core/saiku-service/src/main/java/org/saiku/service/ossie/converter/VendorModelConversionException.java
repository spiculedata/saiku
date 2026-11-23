/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

/** The payload wasn't parseable as the requested format — a 400, not a validation finding. */
public class VendorModelConversionException extends Exception {

    private static final long serialVersionUID = 1L;

    public VendorModelConversionException(String message) {
        super(message);
    }

    public VendorModelConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
