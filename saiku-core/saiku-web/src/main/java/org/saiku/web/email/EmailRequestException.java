/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

/** Thrown when an email-self request is malformed or unsafe. Maps to HTTP 400. */
public class EmailRequestException extends RuntimeException {
    public EmailRequestException(String message) {
        super(message);
    }
}
