/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The façade the CLI and the REST endpoint both call (saiku#1434): take two models, hand back a
 * {@link ModelDiffReport} with a broken-reference scan when a repository is supplied.
 *
 * <p>Every entry point funnels through here so the CLI, the REST API and any CI hook produce
 * byte-identical output for identical input — the acceptance criterion in saiku#1434 is a
 * specific Markdown shape, and three independent renderers would drift.
 *
 * <p>Format detection, parsing and cross-format rejection are all this class's job. The diff
 * engine below it sees snapshots only, and stays format-agnostic.
 */
public final class ModelDiffService {

    private final ModelDiffEngine engine = new ModelDiffEngine();
    private final BrokenReferenceScanner scanner = new BrokenReferenceScanner();

    /** Parse a payload, detecting its format from content first and file name second. */
    public ModelSnapshot parse(String content, String sourceName) {
        ModelFormat format = ModelFormat.detect(content, sourceName);
        return parserFor(format).parse(content, sourceName);
    }

    public ModelSnapshot parseFile(Path file) {
        try {
            return parse(Files.readString(file), file.getFileName().toString());
        } catch (IOException e) {
            throw new ModelDiffException(
                    ModelDiffException.Reason.MALFORMED, "could not read '" + file + "': " + e.getMessage(), e);
        }
    }

    private static ModelParser parserFor(ModelFormat format) {
        return format == ModelFormat.MONDRIAN_XML ? new MondrianXmlModelParser() : new OssieYamlModelParser();
    }

    /** Diff two raw payloads. No reference scan — there is no repository to walk. */
    public ModelDiffReport diffStrings(String before, String after) {
        return diff(parse(before, "before"), parse(after, "after"), null);
    }

    /** Diff two model files, then scan {@code repositoryRoot} for references the after model breaks. */
    public ModelDiffReport diffFiles(Path before, Path after, Path repositoryRoot) {
        return diff(parseFile(before), parseFile(after), repositoryRoot);
    }

    /**
     * Diff two snapshots and — when {@code repositoryRoot} is a directory — list every saved
     * query, dashboard or app that no longer resolves.
     */
    public ModelDiffReport diff(ModelSnapshot before, ModelSnapshot after, Path repositoryRoot) {
        List<ModelChange> changes = engine.diff(before, after);
        List<BrokenReference> broken = repositoryRoot == null ? List.of() : scanner.scan(repositoryRoot, after);
        return new ModelDiffReport(
                before.format(), before.name(), after.name(), changes, broken, countFiles(repositoryRoot));
    }

    /** Just the scan, for "validate the current model against the current repository" (the CI hook). */
    public List<BrokenReference> validate(Path repositoryRoot, ModelSnapshot model) {
        return scanner.scan(repositoryRoot, model);
    }

    private static int countFiles(Path repositoryRoot) {
        if (repositoryRoot == null || !java.nio.file.Files.isDirectory(repositoryRoot)) {
            return 0;
        }
        try (var stream = Files.walk(repositoryRoot)) {
            return (int) stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                    .filter(name -> {
                        for (String extension : BrokenReferenceScanner.SCANNED_EXTENSIONS) {
                            if (name.endsWith(extension)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }
}
