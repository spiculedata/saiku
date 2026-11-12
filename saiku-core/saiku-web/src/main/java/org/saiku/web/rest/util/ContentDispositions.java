/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.rest.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds a safe {@code Content-Disposition: attachment} value from a caller-supplied export name
 * (saiku#2348). The name is untrusted: quotes, semicolons, path separators and control characters
 * would otherwise let it reshape the header the browser parses.
 *
 * <p>Everything outside letters, digits and {@code . _ - space} is dropped, whitespace is
 * collapsed, leading dots are trimmed and the length is capped. The result carries both forms from
 * RFC 6266 / 5987: an ASCII {@code filename="..."} and a percent-encoded {@code filename*}.
 */
public final class ContentDispositions {

    static final int MAX_NAME_LENGTH = 120;
    private static final String DEFAULT_NAME = "download";

    private ContentDispositions() {}

    /** Attachment header for {@code name} plus {@code extension} (given without the dot). */
    public static String attachment(String name, String extension) {
        String base = sanitise(name);
        String ext = sanitise(extension);
        if (base.isEmpty()) {
            base = DEFAULT_NAME;
        }
        return header(ext.isEmpty() ? base : base + "." + ext);
    }

    /** Attachment header for a complete file name that already carries its extension. */
    public static String attachment(String filename) {
        return header(sanitise(filename));
    }

    private static String header(String sanitised) {
        String name = sanitised.isEmpty() ? DEFAULT_NAME : sanitised;
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "");
        if (ascii.isBlank()) {
            ascii = DEFAULT_NAME;
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + percentEncode(name);
    }

    private static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[^\\p{L}\\p{N}._ -]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("^\\.+", "");
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (unreserved) {
                out.append(c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
