/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalogue of {@link EvalSuite}s discovered under a scan root (saiku#1424).
 *
 * <p>Same discovery model as {@link org.saiku.service.olap.ai.ask.AgentSkillRegistry} and {@link
 * org.saiku.service.olap.ai.ask.AgentSpaceRegistry}: mtime-based signature check on every read,
 * atomic snapshot swap on refresh, structured parse errors surfaced via {@link #errors()} so an
 * operator can fix a broken YAML without reading server logs.
 *
 * <p>Scans {@code *.yaml} and {@code *.yml} under the root. Suite name (from the file's YAML)
 * is the unique key — two files declaring the same {@code name} produce a {@link
 * SuiteError} of code {@code DUPLICATE_NAME} on the second one.
 */
public final class EvalSuiteRegistry {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteRegistry.class);

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public EvalSuiteRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** String-arg convenience so Spring XML wiring stays terse. */
    public EvalSuiteRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Ordered by suite name. */
    public List<EvalSuite> list() {
        return refresh().suites;
    }

    /** Look up by suite name — the {@code name:} field from the YAML, not the filename. */
    public Optional<EvalSuite> get(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(refresh().byName.get(name));
    }

    /** Structured parse errors from the last scan, keyed by relative file path. */
    public List<SuiteError> errors() {
        return refresh().errors;
    }

    /** Force a rescan (bypasses the signature check). */
    public void forceRefresh() {
        snapshot.set(scan());
    }

    private Snapshot refresh() {
        Snapshot current = snapshot.get();
        long sig = signature();
        if (current.signature == sig) return current;
        Snapshot next = scan();
        snapshot.set(next);
        return next;
    }

    private long signature() {
        if (!Files.isDirectory(root)) return -1L;
        long count = 0;
        long maxMtime = 0;
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!isYamlExtension(p)) continue;
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                count++;
                maxMtime = Math.max(maxMtime, attrs.lastModifiedTime().toMillis());
                totalSize += attrs.size();
            }
        } catch (IOException e) {
            log.debug("eval-suite signature scan failed under {}", root, e);
            return -1L;
        }
        long sig = 1125899906842597L;
        sig = sig * 31 + count;
        sig = sig * 31 + maxMtime;
        sig = sig * 31 + totalSize;
        return sig;
    }

    private Snapshot scan() {
        if (!Files.isDirectory(root)) {
            log.debug("eval-suite root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<EvalSuite> suites = new ArrayList<>();
        List<SuiteError> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!isYamlExtension(p)) continue;
                String relPath = root.relativize(p).toString();
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    suites.add(EvalYamlReader.read(content, relPath));
                } catch (EvalYamlReader.EvalParseException e) {
                    log.warn("eval suite {} rejected: {} ({})", relPath, e.getMessage(), e.code());
                    errors.add(new SuiteError(relPath, e.code(), e.getMessage()));
                } catch (IOException e) {
                    log.warn("eval suite {} could not be read", relPath, e);
                    errors.add(new SuiteError(relPath, "IO_ERROR", e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.warn("eval-suite scan under {} failed", root, e);
        }
        suites.sort(Comparator.comparing(EvalSuite::name));

        Map<String, EvalSuite> byName = new HashMap<>();
        List<EvalSuite> deduped = new ArrayList<>(suites.size());
        for (EvalSuite s : suites) {
            EvalSuite prior = byName.put(s.name(), s);
            if (prior != null) {
                errors.add(new SuiteError(
                        "(duplicate)",
                        "DUPLICATE_NAME",
                        "another file already declared suite name '" + s.name() + "'"));
            } else {
                deduped.add(s);
            }
        }
        long sig = signature();
        return new Snapshot(
                sig,
                Collections.unmodifiableList(deduped),
                Collections.unmodifiableMap(new LinkedHashMap<>(byName)),
                Collections.unmodifiableList(errors));
    }

    private static boolean isYamlExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /** Structured error entry for files that failed to parse. */
    public record SuiteError(String path, String code, String message) {}

    private record Snapshot(
            long signature, List<EvalSuite> suites, Map<String, EvalSuite> byName, List<SuiteError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), List.of());
        }
    }
}
