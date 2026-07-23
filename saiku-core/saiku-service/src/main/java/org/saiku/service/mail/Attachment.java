package org.saiku.service.mail;

import java.util.Objects;

/** A file attached to the message. */
public record Attachment(String filename, String contentType, byte[] data) {
    public Attachment {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(data, "data");
    }
}
