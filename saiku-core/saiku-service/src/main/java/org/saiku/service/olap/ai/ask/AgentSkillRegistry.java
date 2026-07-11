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
 * Catalogue of {@link AgentSkill}s discovered under a scan root.
 *
 * <p>Rescans lazily: {@link #list()} and {@link #get(String)} check the directory's aggregated
 * signature (file count + max mtime + total size) and reparse only when it changes. That's enough
 * to catch adds / edits / removes without paying for a file-system watcher or a background thread.
 *
 * <p>Broken skills don't take down the catalogue: a {@link AgentSkillParser.ParseException}
 * discards that one file and leaves a structured entry on {@link #errors()} that the REST layer
 * surfaces so operators can fix it. Everything else still shows up.
 *
 * <p>Thread-safe: the snapshot is swapped atomically. Concurrent readers see a consistent view even
 * mid-refresh.
 */
public final class AgentSkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillRegistry.class);

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public AgentSkillRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * String-arg convenience so Spring XML wiring stays terse ({@code <constructor-arg
     * value="..."/>}). Resolves the path with {@link Path#of(String, String...)}.
     */
    public AgentSkillRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Skill files discovered on the last scan, ordered by name. */
    public List<AgentSkill> list() {
        return refresh().skills;
    }

    /** Look up a skill by name. Case-sensitive — names are already kebab-case. */
    public Optional<AgentSkill> get(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(refresh().byName.get(name));
    }

    /**
     * Structured error entries for files that failed to parse on the last scan. Keyed by relative
     * file path.
     */
    public List<SkillError> errors() {
        return refresh().errors;
    }

    /**
     * Force a rescan even if the directory signature is unchanged. Wired into {@code
     * /admin/refresh} so operators can pick up frontmatter edits without waiting for the mtime
     * check.
     */
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

    /**
     * Cheap directory signature: file count + max mtime + total size. Sufficient to detect an add /
     * edit / delete without hashing every file. Returns {@code -1} when the root is missing so a
     * missing-then-created directory still triggers one scan.
     */
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
                String fname = p.getFileName().toString();
                if (!fname.endsWith(".md")) continue;
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                count++;
                maxMtime = Math.max(maxMtime, attrs.lastModifiedTime().toMillis());
                totalSize += attrs.size();
            }
        } catch (IOException e) {
            log.debug("skill signature scan failed under {}", root, e);
            return -1L;
        }
        // Fold into a single long. Collisions are theoretically possible but practically
        // irrelevant for the "did something change" question; forceRefresh() is the escape hatch.
        long sig = 1125899906842597L;
        sig = sig * 31 + count;
        sig = sig * 31 + maxMtime;
        sig = sig * 31 + totalSize;
        return sig;
    }

    private Snapshot scan() {
        if (!Files.isDirectory(root)) {
            log.debug("skill root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<AgentSkill> skills = new ArrayList<>();
        List<SkillError> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String fname = p.getFileName().toString();
                if (!fname.endsWith(".md")) continue;
                String relPath = root.relativize(p).toString();
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    AgentSkill skill = AgentSkillParser.parse(relPath, content);
                    skills.add(skill);
                } catch (AgentSkillParser.ParseException e) {
                    log.warn("skill {} rejected: {} ({})", relPath, e.getMessage(), e.code());
                    errors.add(new SkillError(relPath, e.code(), e.getMessage()));
                } catch (IOException e) {
                    log.warn("skill {} could not be read", relPath, e);
                    errors.add(new SkillError(relPath, "IO_ERROR", e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.warn("skill scan under {} failed", root, e);
        }
        skills.sort(Comparator.comparing(AgentSkill::name));

        Map<String, AgentSkill> byName = new HashMap<>();
        // Deduplicate on name; a duplicate is a structural error surfaced as SkillError.
        List<AgentSkill> deduped = new ArrayList<>(skills.size());
        for (AgentSkill s : skills) {
            AgentSkill prior = byName.put(s.name(), s);
            if (prior != null) {
                errors.add(new SkillError(
                        s.sourcePath(),
                        "DUPLICATE_NAME",
                        "another skill file already declared name '" + s.name() + "': " + prior.sourcePath()));
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

    /** A single failed skill file — surfaced via {@code /ai/skills?errors=true}. */
    public record SkillError(String path, String code, String message) {}

    private record Snapshot(
            long signature, List<AgentSkill> skills, Map<String, AgentSkill> byName, List<SkillError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), List.of());
        }
    }
}
