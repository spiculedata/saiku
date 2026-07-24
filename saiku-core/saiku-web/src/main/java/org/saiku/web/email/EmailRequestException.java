package org.saiku.web.email;

/** Thrown when an email-self request is malformed or unsafe. Maps to HTTP 400. */
public class EmailRequestException extends RuntimeException {
    public EmailRequestException(String message) {
        super(message);
    }
}
