/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

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
 * Catalogue of {@link AgentSpace}s discovered under a scan root.
 *
 * <p>Same rescan model as {@link AgentSkillRegistry}: mtime signature check on every read, atomic
 * swap. Spaces are typically a small handful — 3-8 personas per instance — so the scan cost is a
 * rounding error compared to the ask itself.
 *
 * <p>Broken spaces don't take down the catalogue: a {@link AgentSpaceParser.ParseException}
 * discards that one file and leaves a structured entry on {@link #errors()} that the admin UI
 * surfaces so operators fix bad JSON without reading server logs.
 */
public final class AgentSpaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentSpaceRegistry.class);

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public AgentSpaceRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public AgentSpaceRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Ordered by id. */
    public List<AgentSpace> list() {
        return refresh().spaces;
    }

    /** Look up by id — case-sensitive kebab-case slug. */
    public Optional<AgentSpace> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(refresh().byId.get(id));
    }

    /** Structured parse errors from the last scan, keyed by relative file path. */
    public List<SpaceError> errors() {
        return refresh().errors;
    }

    /** Force a rescan (bypasses the signature check). */
    public void forceRefresh() {
        snapshot.set(scan());
    }

    private Snapshot refresh() {
        Snapshot current = snapshot.get();
        long sig = signature();
        if (current.signature == sig) {
            return current;
        }
        Snapshot next = scan();
        snapshot.set(next);
        return next;
    }

    private long signature() {
        if (!Files.isDirectory(root)) {
            return -1L;
        }
        long count = 0;
        long maxMtime = 0;
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".json")) continue;
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                count++;
                maxMtime = Math.max(maxMtime, attrs.lastModifiedTime().toMillis());
                totalSize += attrs.size();
            }
        } catch (IOException e) {
            log.debug("space signature scan failed under {}", root, e);
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
            log.debug("space root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<AgentSpace> spaces = new ArrayList<>();
        List<SpaceError> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".json")) continue;
                String relPath = root.relativize(p).toString();
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    spaces.add(AgentSpaceParser.parse(relPath, content));
                } catch (AgentSpaceParser.ParseException e) {
                    log.warn("space {} rejected: {} ({})", relPath, e.getMessage(), e.code());
                    errors.add(new SpaceError(relPath, e.code(), e.getMessage()));
                } catch (IOException e) {
                    log.warn("space {} could not be read", relPath, e);
                    errors.add(new SpaceError(relPath, "IO_ERROR", e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.warn("space scan under {} failed", root, e);
        }
        spaces.sort(Comparator.comparing(AgentSpace::id));

        Map<String, AgentSpace> byId = new HashMap<>();
        List<AgentSpace> deduped = new ArrayList<>(spaces.size());
        for (AgentSpace s : spaces) {
            AgentSpace prior = byId.put(s.id(), s);
            if (prior != null) {
                errors.add(new SpaceError(
                        s.sourcePath(),
                        "DUPLICATE_ID",
                        "another file already declared space id '" + s.id() + "': " + prior.sourcePath()));
            } else {
                deduped.add(s);
            }
        }
        long sig = signature();
        return new Snapshot(
                sig,
                Collections.unmodifiableList(deduped),
                Collections.unmodifiableMap(new LinkedHashMap<>(byId)),
                Collections.unmodifiableList(errors));
    }

    public record SpaceError(String path, String code, String message) {}

    private record Snapshot(
            long signature, List<AgentSpace> spaces, Map<String, AgentSpace> byId, List<SpaceError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), List.of());
        }
    }
}
