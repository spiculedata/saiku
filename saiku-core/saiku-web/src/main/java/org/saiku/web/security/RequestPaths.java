/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The single answer to "which path is this request for?" for every hand-written security
 * filter (CSRF matcher, demo gate, embed/share/SCIM/OAuth pre-auth filters, SPA fallback).
 *
 * <p>The servlet container routes, and Spring Security's ant matchers match, on the
 * <em>decoded</em> path ({@code /%72est/x} is {@code /rest/x}). {@code getRequestURI()} is the
 * raw, percent-encoded wire form, so a filter that tests it with {@code startsWith("/rest/")}
 * disagrees with the layers it sits beside (CWE-647, #2342). This helper returns the form those
 * layers see: the context path removed, {@code ;parameters} stripped, percent-escapes decoded
 * once, {@code .} segments dropped and {@code //} collapsed.
 *
 * <p>It <strong>fails closed</strong>: a path that cannot be put in canonical form (a {@code ..}
 * segment, an encoded or literal slash/backslash inside a segment, NUL or another control
 * character, a malformed or non-UTF-8 escape) yields {@code null}. Callers must treat
 * {@code null} as "applies to me": demand CSRF, apply the gate, mint no guest.
 */
public final class RequestPaths {

    private static final int HEX_RADIX = 16;
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int DELETE_CHAR = 0x7f;

    private RequestPaths() {}

    /**
     * @return the decoded, normalised path within the application (always starting with
     *     {@code /}), or {@code null} when the request path cannot be normalised.
     */
    public static String pathWithinApplication(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) {
            return null;
        }
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return normalise(uri);
    }

    static String normalise(String rawPath) {
        List<String> kept = new ArrayList<>();
        for (String rawSegment : rawPath.split("/", -1)) {
            int semicolon = rawSegment.indexOf(';');
            String segment = semicolon < 0 ? rawSegment : rawSegment.substring(0, semicolon);
            String decoded = percentDecode(segment);
            if (decoded == null || hasForbiddenCharacter(decoded) || "..".equals(decoded)) {
                return null;
            }
            if (!decoded.isEmpty() && !".".equals(decoded)) {
                kept.add(decoded);
            }
        }
        if (kept.isEmpty()) {
            return "/";
        }
        String joined = "/" + String.join("/", kept);
        return rawPath.endsWith("/") ? joined + "/" : joined;
    }

    private static boolean hasForbiddenCharacter(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '/' || c == '\\' || c < FIRST_PRINTABLE || c == DELETE_CHAR) {
                return true;
            }
        }
        return false;
    }

    /** Strict UTF-8 percent-decoding; {@code null} for a malformed escape or invalid UTF-8. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length];
        int n = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '%') {
                out[n++] = bytes[i];
                continue;
            }
            if (i + 2 >= bytes.length) {
                return null;
            }
            int hi = Character.digit(bytes[i + 1], HEX_RADIX);
            int lo = Character.digit(bytes[i + 2], HEX_RADIX);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[n++] = (byte) ((hi << 4) | lo);
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out, 0, n))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
