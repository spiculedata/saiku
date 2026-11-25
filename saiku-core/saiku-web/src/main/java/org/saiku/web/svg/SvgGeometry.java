/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.svg;

/**
 * saiku#2527 — the intrinsic raster size of an SVG root element, parsed before
 * the transcoder runs so an oversized request can be refused before any raster
 * is allocated, and the target raster size a chart export should produce.
 *
 * <p>Only the first {@code <svg ...>} tag is read: the community watermark is
 * injected after the root, so its own dimensions must never be mistaken for the
 * document's. Attribute lengths are resolved with the common CSS absolute units
 * (px, pt, pc, mm, cm, in, em, ex); percentages and non-numeric values are
 * unresolvable, and a {@code viewBox} is the fallback when the root has no
 * usable {@code width}/{@code height}.
 */
public final class SvgGeometry {

    /** The document's requested raster size, in pixels. */
    public record Intrinsic(float width, float height) {}

    private SvgGeometry() {}

    /**
     * Parses the root element's {@code width}/{@code height} or {@code viewBox}.
     *
     * @return the intrinsic size, or {@code null} when it cannot be resolved
     *     (no root element, percentage or missing dimensions and no viewBox).
     */
    public static Intrinsic parse(String svg) {
        if (svg == null) {
            return null;
        }
        String root = rootTag(svg);
        if (root == null) {
            return null;
        }
        Float width = length(attribute(root, "width"));
        Float height = length(attribute(root, "height"));
        if (width != null && height != null && width > 0 && height > 0) {
            return new Intrinsic(width, height);
        }
        float[] viewBox = viewBoxDimensions(attribute(root, "viewBox"));
        if (viewBox != null && viewBox[0] > 0 && viewBox[1] > 0) {
            return new Intrinsic(viewBox[0], viewBox[1]);
        }
        return null;
    }

    /**
     * Computes the raster size an export should produce, always within the
     * configured budget.
     *
     * <ul>
     *   <li>With a {@code size} hint the intrinsic size is scaled uniformly so
     *       its longest side hits the (clamped) hint, then shrunk to fit the
     *       budget — the old behaviour scaled only the width and let the height
     *       follow the aspect ratio past any bound (saiku#2527).</li>
     *   <li>Without a {@code size} hint the intrinsic size is honoured as is,
     *       unless it exceeds the budget: then the request is refused with a
     *       {@link SvgExportException} carrying a {@code 400}.</li>
     *   <li>With unresolvable dimensions the fallback keeps the output bounded:
     *       the (clamped) size hint, or a default viewport when none is given.</li>
     * </ul>
     *
     * @return a {@code [width, height]} pair in pixels, both at least 1
     * @throws SvgExportException when an unsized request exceeds the budget
     */
    public static float[] target(Intrinsic intrinsic, Integer size, float maxWidth, float maxHeight) {
        Float hint = size == null ? null : Float.valueOf(clamp(size, MIN_SIZE, Math.min(maxWidth, maxHeight)));

        if (intrinsic == null) {
            // Unresolvable dimensions (percentages, missing): Batik would fall back
            // to its own default viewport, which is not a bound we control. Pick one.
            if (hint != null) {
                return new float[] {hint, hint};
            }
            return new float[] {Math.min(DEFAULT_WIDTH, maxWidth), Math.min(DEFAULT_HEIGHT, maxHeight)};
        }

        if (hint != null) {
            float longest = Math.max(intrinsic.width(), intrinsic.height());
            float k = hint / Math.max(longest, 0.001f);
            float w = Math.max(1, Math.round(intrinsic.width() * k));
            float h = Math.max(1, Math.round(intrinsic.height() * k));
            if (w > maxWidth || h > maxHeight) {
                float k2 = Math.min(maxWidth / w, maxHeight / h);
                w = Math.max(1, Math.round(w * k2));
                h = Math.max(1, Math.round(h * k2));
            }
            return new float[] {w, h};
        }

        if (intrinsic.width() > maxWidth + 0.001f || intrinsic.height() > maxHeight + 0.001f) {
            throw new SvgExportException(
                    400,
                    String.format(
                            "Chart raster %.0fx%.0f exceeds the %.0fx%.0f export limit; "
                                    + "set a size within the limit or export a smaller chart",
                            intrinsic.width(), intrinsic.height(), maxWidth, maxHeight));
        }
        return new float[] {Math.max(1, intrinsic.width()), Math.max(1, intrinsic.height())};
    }

    /** The smallest honoured {@code size} hint (the pre-existing clamp). */
    static final float MIN_SIZE = 32f;
    /** Fallback viewport when no size hint is given and nothing is resolvable. */
    static final float DEFAULT_WIDTH = 800f;

    static final float DEFAULT_HEIGHT = 600f;

    private static String rootTag(String svg) {
        int start = svg.indexOf("<svg");
        if (start < 0) {
            return null;
        }
        int i = start;
        boolean inQuote = false;
        char quote = 0;
        while (i < svg.length()) {
            char c = svg.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '>') {
                return svg.substring(start, i + 1);
            }
            i++;
        }
        return null;
    }

    /** First occurrence of {@code name="..."} / {@code name='...'} in the tag, case-insensitive. */
    private static String attribute(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?:^|\\s)" + name + "\\s*=\\s*([\"'])(.*?)\\1", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(tag);
        return m.find() ? m.group(2) : null;
    }

    private static float[] viewBoxDimensions(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split("[\\s,]+");
        if (parts.length < 4) {
            return null;
        }
        try {
            float w = Float.parseFloat(parts[2]);
            float h = Float.parseFloat(parts[3]);
            return new float[] {w, h};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Resolves a CSS length to pixels; {@code null} when it is not an absolute length. */
    private static Float length(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*([a-z%]*)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value.trim());
        if (!m.find()) {
            return null;
        }
        float n;
        try {
            n = Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (m.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "", "px" -> n;
            case "pt" -> n * 96f / 72f;
            case "pc" -> n * 16f;
            case "mm" -> n * 96f / 25.4f;
            case "cm" -> n * 960f / 25.4f;
            case "in" -> n * 96f;
            case "em" -> n * 16f;
            case "ex" -> n * 8f;
            default -> null; // %, and anything unrecognised
        };
    }

    private static float clamp(int v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }
}
