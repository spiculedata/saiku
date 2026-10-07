/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.List;

/**
 * One vendor semantic format Saiku can import. Implemented by a converter that turns the
 * uploaded artefacts into an Ossie {@code semantic_model} tree.
 *
 * <p>Design intent (saiku#1730): the format set is an open list, not a switch statement, so a
 * new spoke can be added — either here while it stabilises, or by registering an
 * {@code apache/ossie} converter implementation upstream and having the registry resolve it
 * first. Nothing in the import flow (REST, validation report, UI) knows about any specific
 * vendor format: they all take {@code id} and hand back the same {@link VendorConversionResult}.
 */
public interface VendorModelConverter {

    /** Stable wire id, e.g. {@code lookml}. Used as the {@code format} form value. */
    String id();

    /** Human label for the format picker, e.g. {@code LookML (Looker)}. */
    String displayName();

    /** One line describing what the converter expects, shown under the picker. */
    String description();

    /** Accepted upload extensions (lower-case, dot-prefixed). Advisory — the UI filters on it. */
    List<String> fileExtensions();

    /**
     * Convert the uploaded artefacts into an Ossie document.
     *
     * @param files one or more uploaded artefacts; never empty, never containing a blank file
     * @param requestName model name requested by the user, or {@code null}/blank to derive one
     * @return the built document plus per-element diagnostics
     * @throws VendorModelConversionException when the payload can't be parsed at all (the
     *     registry turns this into a 400 with the message; per-element problems are diagnostics,
     *     never exceptions)
     */
    VendorConversionResult convert(List<VendorModelFile> files, String requestName)
            throws VendorModelConversionException;
}
