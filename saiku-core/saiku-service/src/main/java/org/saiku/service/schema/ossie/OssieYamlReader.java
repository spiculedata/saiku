/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.ossie;

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
 * <p>Symmetric with {@code OssieYamlWriter} — the pair is the round-trip. Lives in
 * saiku-service alongside the writer + POJOs so any module can consume it (previously kept in
 * saiku-sql; promoted here when {@code OssieDiscoverService} in this module needed to read the
 * same YAML the Calcite adapter loads).
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
