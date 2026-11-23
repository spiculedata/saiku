/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import bi.saiku.ossie.model.OssieDocument;
import java.util.ArrayList;
import java.util.List;

/**
 * What a converter produced: a ready-to-serialise {@link OssieDocument} plus the per-element
 * notes describing everything that was degraded, simplified or dropped on the way.
 *
 * <p>The document is returned rather than YAML text so the import service can validate the
 * object it is about to write and serialise exactly what it validated — a converter can't
 * hand back YAML that disagrees with the tree the report describes.
 */
public class VendorConversionResult {

    private final OssieDocument document;
    private final String modelName;
    private final List<ConversionDiagnostic> diagnostics = new ArrayList<>();

    public VendorConversionResult(OssieDocument document, String modelName) {
        this.document = document;
        this.modelName = modelName;
    }

    public OssieDocument getDocument() {
        return document;
    }

    public String getModelName() {
        return modelName;
    }

    public List<ConversionDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public VendorConversionResult add(ConversionDiagnostic d) {
        diagnostics.add(d);
        return this;
    }

    public VendorConversionResult addAll(List<ConversionDiagnostic> ds) {
        diagnostics.addAll(ds);
        return this;
    }
}
