package org.saiku.service.mail;

/** Thrown when a {@link MailSender} cannot deliver a message. */
public class MailException extends RuntimeException {
    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
