/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import java.util.ArrayList;
import java.util.List;

/**
 * One uploaded artefact: the file name the browser sent (used for diagnostics and for
 * deriving a model name when the user didn't supply one) plus its decoded text content.
 *
 * <p>Multi-file by design — a LookML "project export" is a directory of {@code *.view} /
 * {@code *.explore} files, and pasting one file at a time would be unusable.
 */
public class VendorModelFile {

    private final String fileName;
    private final String content;

    public VendorModelFile(String fileName, String content) {
        this.fileName = fileName == null ? "" : fileName;
        this.content = content == null ? "" : content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }

    /** True when the file carries no importable bytes (empty or whitespace only). */
    public boolean isBlank() {
        return content.isBlank();
    }

    /** Multi-file name for diagnostics when more than one artefact was uploaded. */
    public String describe(List<VendorModelFile> all) {
        if (all != null && all.size() > 1) {
            return fileName + " (+" + (all.size() - 1) + " more)";
        }
        return fileName;
    }

    public static List<VendorModelFile> single(String fileName, String content) {
        List<VendorModelFile> files = new ArrayList<>();
        files.add(new VendorModelFile(fileName, content));
        return files;
    }
}
