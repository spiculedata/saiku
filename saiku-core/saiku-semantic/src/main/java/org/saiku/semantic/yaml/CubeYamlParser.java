package org.saiku.semantic.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.saiku.semantic.ir.CubeDefinition;
import org.saiku.semantic.ir.Dimension;
import org.saiku.semantic.ir.Level;
import org.saiku.semantic.ir.Measure;

/**
 * Parses a Saiku cube YAML file into a {@link CubeDefinition} IR. Throws
 * {@link SemanticValidationException} with line-accurate messages when a
 * required field is missing or a cross-reference fails.
 */
public final class CubeYamlParser {

    private final ObjectMapper mapper;

    public CubeYamlParser() {
        this.mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    public CubeDefinition parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return validate(mapper.readValue(in, CubeDefinition.class));
        }
    }

    public CubeDefinition parse(Reader reader) throws IOException {
        return validate(mapper.readValue(reader, CubeDefinition.class));
    }

    public CubeDefinition parse(String yaml) throws IOException {
        return validate(mapper.readValue(yaml, CubeDefinition.class));
    }

    private CubeDefinition validate(CubeDefinition cube) {
        require(cube.name(), "name");
        Objects.requireNonNull(cube.fact(), "fact block is required");
        require(cube.fact().table(), "fact.table");

        if (cube.measures().isEmpty()) {
            throw new SemanticValidationException("cube '" + cube.name() + "' must declare at least one measure");
        }
        for (Measure m : cube.measures()) {
            require(m.name(), "measure.name");
            require(m.column(), "measure.column");
        }
        for (Dimension d : cube.dimensions()) {
            require(d.name(), "dimension.name");
            List<Level> levels = d.levels();
            if (levels.isEmpty()) {
                throw new SemanticValidationException("dimension '" + d.name() + "' must declare at least one level");
            }
            for (Level l : levels) {
                require(l.name(), "level.name");
                require(l.column(), "level.column");
            }
        }
        return cube;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SemanticValidationException(field + " is required");
        }
    }

    public static final class SemanticValidationException extends RuntimeException {
        public SemanticValidationException(String message) {
            super(message);
        }
    }
}
