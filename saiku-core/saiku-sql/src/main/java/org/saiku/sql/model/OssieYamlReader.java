/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.sql.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.saiku.service.schema.ossie.model.OssieDocument;

/**
 * Load an {@link OssieDocument} from Ossie-flavoured YAML.
 *
 * <p>Symmetric with {@code OssieYamlWriter} in saiku-service — the pair is the read side of the
 * same round-trip. Kept in the saiku-sql module because that's where read-side Ossie needs live
 * (the Calcite adapter needs the tree; the exporter only produces it). Once we have a use-case
 * for reading Ossie from other layers we can promote this back to saiku-service alongside the
 * writer.
 *
 * <p>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is disabled so Ossie documents that
 * carry vendor extensions or future spec keys we don't yet model won't blow up the loader — the
 * spec is draft and additive fields are the whole point of {@code custom_extensions}.
 */
public final class OssieYamlReader {

    private final ObjectMapper yaml;

    public OssieYamlReader() {
        this.yaml = new ObjectMapper(new YAMLFactory());
        this.yaml.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public OssieDocument read(Path yamlPath) throws IOException {
        try (InputStream in = Files.newInputStream(yamlPath)) {
            return yaml.readValue(in, OssieDocument.class);
        }
    }

    public OssieDocument read(InputStream stream) throws IOException {
        return yaml.readValue(stream, OssieDocument.class);
    }

    public OssieDocument read(Reader reader) throws IOException {
        return yaml.readValue(reader, OssieDocument.class);
    }

    public OssieDocument readString(String yamlText) throws IOException {
        return yaml.readValue(yamlText, OssieDocument.class);
    }
}
