/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.ossie.converter;

import bi.saiku.ossie.OssieYamlReader;
import bi.saiku.ossie.OssieYamlWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Vendor-model import (saiku#1730) — the server half of "bring your semantic model to Saiku from
 * the UI".
 *
 * <p>Four steps, in this order, none of which persist anything until the caller asks:
 *
 * <ol>
 *   <li>resolve the converter for the requested format from the {@link VendorModelConverterRegistry}
 *   <li>convert the uploaded artefacts to an Ossie document, collecting per-element diagnostics
 *   <li>serialise with {@link OssieYamlWriter} and read it straight back with {@link
 *       OssieYamlReader} — a round trip that fails is an error, not a warning, because it means
 *       the YAML we're about to hand the datasource isn't loadable
 *   <li>run {@link OssieYamlValidator} over the document and return model + report
 * </ol>
 *
 * <p>{@link #save} is a separate, explicit call: the UI shows the preview, the user confirms,
 * and only then does anything touch the filesystem.
 */
public class VendorModelImportService {

    /** Guard rails: a LookML project is a few hundred KB; a 25 MB upload is a mistake or an attack. */
    public static final int MAX_FILES = 50;

    public static final int MAX_TOTAL_BYTES = 25 * 1024 * 1024;

    private static final Pattern UNSAFE_NAME = Pattern.compile("[^a-z0-9_-]+");

    private final VendorModelConverterRegistry registry;
    private final OssieYamlValidator validator = new OssieYamlValidator();
    private final java.nio.file.Path modelRoot;

    public VendorModelImportService(VendorModelConverterRegistry registry, java.nio.file.Path modelRoot) {
        this.registry = registry;
        this.modelRoot = modelRoot;
    }

    /** Convert + validate. Never writes to disk. */
    public ImportResult importModel(String formatId, List<VendorModelFile> files, String requestName)
            throws VendorModelConversionException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded — attach the vendor model export to import.");
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Too many files: " + files.size() + " (limit " + MAX_FILES
                    + "). Upload a single archive or fewer files.");
        }
        long total = 0;
        for (VendorModelFile f : files) total += f.getContent().length();
        if (total > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Upload is " + (total / (1024 * 1024)) + " MB (limit "
                    + (MAX_TOTAL_BYTES / (1024 * 1024)) + " MB).");
        }

        VendorModelConverter converter = registry.require(formatId);
        List<VendorModelFile> usable = new ArrayList<>();
        for (VendorModelFile f : files) {
            if (!f.isBlank()) usable.add(f);
        }
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        VendorConversionResult converted = converter.convert(usable, requestName);

        String yaml;
        try {
            yaml = new OssieYamlWriter().writeAsString(converted.getDocument());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise the converted Ossie model: " + e.getMessage(), e);
        }

        List<ConversionDiagnostic> diags = new ArrayList<>(converted.getDiagnostics());
        // Round trip: what we hand the datasource must load. A failure here is fatal to the
        // import, so it short-circuits before the structural report runs.
        try {
            bi.saiku.ossie.model.OssieDocument reparsed = new OssieYamlReader().readString(yaml);
            if (reparsed.getEffectiveSemanticModels().isEmpty()) {
                diags.add(ConversionDiagnostic.error(
                        "ROUND_TRIP_EMPTY", "model", "The generated Ossie YAML parses but holds no semantic model."));
            }
        } catch (Exception e) {
            diags.add(ConversionDiagnostic.error(
                    "ROUND_TRIP_FAILED", "model", "The generated Ossie YAML does not parse: " + e.getMessage()));
        }

        OssieValidationReport report = validator.validate(converted.getDocument());
        diags.addAll(report.getDiagnostics());
        OssieValidationReport combined = new OssieValidationReport(
                diags,
                report.getDatasetCount(),
                report.getFieldCount(),
                report.getMetricCount(),
                report.getRelationshipCount(),
                report.getDatasets());

        return new ImportResult(converter.id(), converted.getModelName(), yaml, combined);
    }

    public List<VendorModelConverterRegistry.FormatDescriptor> supportedFormats() {
        return registry.supported();
    }

    /**
     * Re-validate a YAML document that arrived from the client. Used by the save step so the
     * server — not the browser — decides whether the model is safe to register.
     *
     * @throws IllegalArgumentException when the YAML doesn't parse as an Ossie document
     */
    public OssieValidationReport validateYaml(String yaml) {
        bi.saiku.ossie.model.OssieDocument doc;
        try {
            doc = new OssieYamlReader().readString(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "The model YAML does not parse as an Ossie document: " + e.getMessage(), e);
        }
        return validator.validate(doc);
    }

    /**
     * Write validated YAML under the configured model root and return the absolute path.
     *
     * @param modelName the semantic-model name (used only to derive a safe file name)
     * @param yaml the exact YAML the preview showed
     * @param overwrite allow replacing an existing file of the same name
     * @return the written file's absolute path
     * @throws IllegalStateException when the target exists and {@code overwrite} is false
     */
    public String save(String modelName, String yaml, boolean overwrite) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("Nothing to save — the import produced no YAML.");
        }
        String slug = slug(modelName);
        java.nio.file.Path target =
                modelRoot.resolve(slug + ".ossie.yaml").toAbsolutePath().normalize();
        if (!target.startsWith(modelRoot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Refusing to write outside the model directory.");
        }
        try {
            java.nio.file.Files.createDirectories(modelRoot);
            if (java.nio.file.Files.exists(target) && !overwrite) {
                throw new IllegalStateException("A model file already exists at " + target
                        + " — save again with overwrite, or rename the model.");
            }
            String header = "# Imported from a vendor semantic model by Saiku (saiku#1730) at " + Instant.now() + "\n";
            java.nio.file.Files.writeString(
                    target,
                    header + yaml,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write the Ossie model to " + target + ": " + e.getMessage(), e);
        }
    }

    /**
     * Filesystem-safe stem for a model name. Everything outside {@code [a-z0-9_-]} collapses to
     * a single dash, so a name like {@code ../../etc/passwd} can never leave the model
     * directory; the caller re-checks containment anyway.
     */
    static String slug(String modelName) {
        if (modelName == null) return "model";
        String s =
                UNSAFE_NAME.matcher(modelName.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        s = s.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (s.isBlank()) return "model";
        return s;
    }

    /** Import response payload: the model, the YAML, and the combined validation report. */
    public static class ImportResult {
        private final String formatId;
        private final String modelName;
        private final String yaml;
        private final OssieValidationReport validation;

        public ImportResult(String formatId, String modelName, String yaml, OssieValidationReport validation) {
            this.formatId = formatId;
            this.modelName = modelName;
            this.yaml = yaml;
            this.validation = validation;
        }

        public String getFormatId() {
            return formatId;
        }

        public String getModelName() {
            return modelName;
        }

        public String getYaml() {
            return yaml;
        }

        public OssieValidationReport getValidation() {
            return validation;
        }
    }
}
