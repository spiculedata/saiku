/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Comparator;

/**
 * One per-element note from a vendor-model conversion or from the post-conversion validation
 * pass — the lines that make up the "validation report" the import UI renders.
 *
 * <p>Deliberately flat and serialisable: the UI groups by {@link #getSeverity()}, shows
 * {@link #getElement()} as the anchor (e.g. {@code orders.total_amount}) and {@link #getMessage()}
 * as the prose. A stable {@link #getCode()} lets a future version group or suppress a class of
 * notes without the client string-matching the prose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversionDiagnostic implements Comparable<ConversionDiagnostic> {

    /** Severity, ordered most-severe first when the report is sorted. */
    public enum Severity {
        /** Something was dropped or simplified. The model still imports. */
        WARNING,
        /** The model (or one of its elements) will not work as converted. */
        ERROR,
        /** Informational: a supported-but-noted decision, or an element deliberately skipped. */
        INFO
    }

    /** Sort order: errors first, then warnings, then info. */
    private static final Comparator<Severity> SEVERITY_ORDER = Comparator.comparingInt(s -> switch (s) {
        case ERROR -> 0;
        case WARNING -> 1;
        case INFO -> 2;
    });

    private final Severity severity;
    private final String code;
    private final String element;
    private final String message;

    public ConversionDiagnostic(Severity severity, String code, String element, String message) {
        this.severity = severity;
        this.code = code;
        this.element = element;
        this.message = message;
    }

    public static ConversionDiagnostic error(String code, String element, String message) {
        return new ConversionDiagnostic(Severity.ERROR, code, element, message);
    }

    public static ConversionDiagnostic warning(String code, String element, String message) {
        return new ConversionDiagnostic(Severity.WARNING, code, element, message);
    }

    public static ConversionDiagnostic info(String code, String element, String message) {
        return new ConversionDiagnostic(Severity.INFO, code, element, message);
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getElement() {
        return element;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public int compareTo(ConversionDiagnostic other) {
        int bySeverity = SEVERITY_ORDER.compare(this.severity, other.severity);
        if (bySeverity != 0) return bySeverity;
        int byElement = nullSafe(this.element).compareTo(nullSafe(other.element));
        if (byElement != 0) return byElement;
        return nullSafe(this.code).compareTo(nullSafe(other.code));
    }

    @Override
    public String toString() {
        return severity + " " + code + " [" + element + "] " + message;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
