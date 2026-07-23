package org.saiku.service.mail;

import java.util.Objects;

/** An image embedded in the HTML body and referenced by {@code cid:<contentId>}. */
public record InlineImage(String contentId, String contentType, byte[] data) {
    public InlineImage {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(data, "data");
    }
}
