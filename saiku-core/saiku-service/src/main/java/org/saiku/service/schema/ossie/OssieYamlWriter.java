/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import org.saiku.service.schema.ossie.model.OssieDocument;

/**
 * Serialise an {@link OssieDocument} tree as Ossie-flavoured YAML.
 *
 * <p>Config choices (all deliberate):
 *
 * <ul>
 *   <li>{@code MINIMIZE_QUOTES} — omit YAML quotes on scalars that don't need them, so downstream
 *       diffs stay compact.
 *   <li>{@code SPLIT_LINES} disabled — the writer keeps long expression strings on one line rather
 *       than folding at 80 cols, so a metric like {@code SUM(orders.amount) / COUNT(...)} stays
 *       machine-parseable and readable.
 *   <li>No leading {@code ---} document marker — Ossie examples don't use one, and Jackson's YAML
 *       module is opt-in on emitting it.
 *   <li>{@code custom_extensions[].data} is a JSON-encoded string per the Ossie spec, NOT an
 *       inlined object. Callers pre-serialise their payload with Jackson before setting {@code
 *       data}; the writer doesn't try to be clever.
 * </ul>
 */
public final class OssieYamlWriter {

    private final ObjectMapper yaml;

    public OssieYamlWriter() {
        YAMLFactory factory = new YAMLFactory()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .disable(YAMLGenerator.Feature.SPLIT_LINES)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        this.yaml = new ObjectMapper(factory);
        this.yaml.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String writeAsString(OssieDocument doc) throws IOException {
        return yaml.writeValueAsString(doc);
    }

    public void write(OssieDocument doc, OutputStream out) throws IOException {
        yaml.writeValue(out, doc);
    }

    public void write(OssieDocument doc, Writer out) throws IOException {
        yaml.writeValue(out, doc);
    }
}
