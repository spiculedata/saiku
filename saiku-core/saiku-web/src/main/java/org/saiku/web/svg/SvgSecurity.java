/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 */
package org.saiku.web.svg;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * saiku#1165 hardening for user-supplied SVG fed to Batik transcoders.
 *
 * <p>The chart/PDF export endpoints accept an SVG string from any authenticated
 * user and hand it to Batik. By default Batik resolves external references and
 * DOCTYPE entities, so a crafted SVG can mount SSRF
 * ({@code <image href="http://169.254.169.254/...">}), local file read
 * ({@code href="file:///etc/passwd"}) or XXE. Saiku's chart SVGs are always
 * self-contained (inline geometry + optional inline {@code data:} images), so we
 * reject anything that would make Batik reach off-document: a DOCTYPE
 * declaration, or any {@code href}/{@code xlink:href}/{@code url(...)} target
 * that is not a {@code data:} URI or a same-document {@code #fragment}. This is
 * version-independent (no reliance on Batik-internal security hooks) and pairs
 * with {@code KEY_EXECUTE_ONLOAD=false} on the transcoders.
 */
public final class SvgSecurity {

    private SvgSecurity() {}

    /** Captures the target of href / xlink:href (double- or single-quoted) and CSS url(...). */
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:xlink:href|href)\\s*=\\s*\"([^\"]*)\"" + "|(?:xlink:href|href)\\s*=\\s*'([^']*)'"
                    + "|url\\(\\s*['\"]?([^'\")]*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * @throws IllegalArgumentException if the SVG contains a DOCTYPE or any
     *     external (non-{@code data:}, non-{@code #fragment}) reference.
     */
    public static void requireSafe(String svg) {
        if (svg == null) {
            return;
        }
        if (svg.toLowerCase(Locale.ROOT).contains("<!doctype")) {
            throw new IllegalArgumentException("SVG DOCTYPE declarations are not allowed");
        }
        Matcher m = REFERENCE.matcher(svg);
        while (m.find()) {
            String ref = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (ref == null) {
                continue;
            }
            ref = ref.trim();
            if (ref.isEmpty() || ref.startsWith("#") || ref.regionMatches(true, 0, "data:", 0, 5)) {
                continue; // same-document fragment or inline data: URI — safe
            }
            throw new IllegalArgumentException("External references are not allowed in exported SVG: " + ref);
        }
    }
}
