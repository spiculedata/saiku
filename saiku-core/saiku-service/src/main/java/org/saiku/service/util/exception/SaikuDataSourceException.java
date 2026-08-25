/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.exception;

public class SaikuDataSourceException extends SaikuServiceException {

    /**
     * @see java.lang.Exception#Exception()
     */
    SaikuDataSourceException() {
        super();
    }

    /**
     * @see java.lang.Exception#Exception(String))
     */
    public SaikuDataSourceException(String message) {
        super(message);
    }

    /**
     * @see java.lang.Exception#Exception(Throwable)
     */
    SaikuDataSourceException(Throwable cause) {
        super(cause);
    }

    /**
     * @see java.lang.Exception#Exception(String, Throwable)
     */
    SaikuDataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
