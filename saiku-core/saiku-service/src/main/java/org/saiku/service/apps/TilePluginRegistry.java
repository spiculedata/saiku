/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.apps;

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
 * Catalogue of {@link TilePluginManifest}s discovered under a scan root.
 *
 * <p>Each plugin is a directory {@code <root>/<id>/} holding {@code plugin.json} (the manifest) and
 * {@code plugin.html} (the self-contained srcdoc HTML). Mirrors {@link
 * org.saiku.service.olap.ai.ask.AgentSkillRegistry}: rescans lazily on an aggregated directory
 * signature (file count + max mtime + total size), swaps the snapshot atomically, and never throws
 * on a broken bundle — a bad plugin drops out and leaves a structured entry on {@link #errors()}
 * that the REST layer surfaces so the rest of the catalogue still serves.
 *
 * <p>Security posture (App Builder Phase 2): the plugin HTML is a <strong>trusted, admin-installed
 * source</strong>. Operators drop bundles into {@code saiku-home/tile-plugins/}; dashboard authors
 * never supply markup, they only reference a plugin by its slug {@code id}. The {@code id} (taken
 * from the manifest) is validated to {@code [a-z0-9-]+} so it is safe as a URL segment, and {@link
 * #html(String)} resolves the html only through a directory path captured at scan time — no
 * user-supplied string is ever concatenated into a filesystem path. See {@link #html(String)}.
 */
public final class TilePluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(TilePluginRegistry.class);

    private static final String MANIFEST_FILE = "plugin.json";
    private static final String HTML_FILE = "plugin.html";

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public TilePluginRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * String-arg convenience so Spring XML wiring stays terse ({@code <constructor-arg
     * value="..."/>}). Resolves the path with {@link Path#of(String, String...)}.
     */
    public TilePluginRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Valid manifests discovered on the last scan, ordered by id. */
    public List<TilePluginManifest> list() {
        return refresh().manifests;
    }

    /** Look up a manifest by id. Case-sensitive — ids are already lowercase slugs. */
    public Optional<TilePluginManifest> get(String id) {
        if (!TilePluginParser.isValidId(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(refresh().byId.get(id));
    }

    /**
     * Return the {@code plugin.html} srcdoc for {@code id}, or {@code null} if there is no such
     * registered plugin (unknown id, missing html, or an id that fails the slug rule).
     *
     * <p>Traversal defence is layered: (1) the id must pass {@link TilePluginParser#isValidId} —
     * {@code [a-z0-9-]+} forbids {@code .}, {@code /}, {@code \} and {@code ..}; (2) the id is looked
     * up in the last scan's map to a concrete bundle directory that was itself enumerated from {@code
     * root} via {@link Files#list} (never built from the request), so an unregistered but slug-shaped
     * id returns {@code null} rather than reaching for an arbitrary file; (3) the resolved html path
     * is re-checked to sit inside {@code root} before it is read. No user-supplied string is ever
     * concatenated into a path.
     */
    public String html(String id) {
        if (!TilePluginParser.isValidId(id)) {
            return null;
        }
        Snapshot snap = refresh();
        Path dir = snap.dirById.get(id);
        if (dir == null) {
            return null;
        }
        Path base = root.normalize();
        Path htmlFile = dir.resolve(HTML_FILE).normalize();
        // Defence in depth: the captured directory (from Files.list(root)) must resolve inside root.
        if (!htmlFile.startsWith(base) || !Files.isRegularFile(htmlFile)) {
            return null;
        }
        try {
            return Files.readString(htmlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("tile plugin {} html could not be read", id, e);
            return null;
        }
    }

    /** Structured error entries for bundles that failed on the last scan, keyed by plugin id/dir. */
    public List<PluginError> errors() {
        return refresh().errors;
    }

    /** Force a rescan even if the directory signature is unchanged. */
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
     * Cheap directory signature over the {@code plugin.json} / {@code plugin.html} files: file count
     * + max mtime + total size. Enough to catch an add / edit / remove without hashing. Returns
     * {@code -1} when the root is missing so a missing-then-created directory still triggers one scan.
     */
    private long signature() {
        if (!Files.isDirectory(root)) {
            return -1L;
        }
        long count = 0;
        long maxMtime = 0;
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(root, 2)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String fname = p.getFileName().toString();
                if (!MANIFEST_FILE.equals(fname) && !HTML_FILE.equals(fname)) continue;
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                count++;
                maxMtime = Math.max(maxMtime, attrs.lastModifiedTime().toMillis());
                totalSize += attrs.size();
            }
        } catch (IOException e) {
            log.debug("tile plugin signature scan failed under {}", root, e);
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
            log.debug("tile plugin root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<Parsed> parsed = new ArrayList<>();
        List<PluginError> errors = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(dir)) continue;
                scanOne(dir, parsed, errors);
            }
        } catch (IOException e) {
            log.warn("tile plugin scan under {} failed", root, e);
        }
        parsed.sort(Comparator.comparing(p -> p.manifest.id()));

        // Deduplicate on the manifest id; a duplicate is a structural error surfaced as PluginError.
        Map<String, TilePluginManifest> byId = new LinkedHashMap<>();
        Map<String, Path> dirById = new HashMap<>();
        List<TilePluginManifest> manifests = new ArrayList<>(parsed.size());
        for (Parsed p : parsed) {
            String id = p.manifest.id();
            if (byId.containsKey(id)) {
                errors.add(new PluginError(
                        p.manifest.sourcePath(),
                        "DUPLICATE_ID",
                        "another bundle already declared plugin id '" + id + "': "
                                + byId.get(id).sourcePath()));
                continue;
            }
            byId.put(id, p.manifest);
            dirById.put(id, p.dir);
            manifests.add(p.manifest);
        }
        long sig = signature();
        return new Snapshot(
                sig,
                Collections.unmodifiableList(manifests),
                Collections.unmodifiableMap(byId),
                Collections.unmodifiableMap(dirById),
                Collections.unmodifiableList(errors));
    }

    /** Parse a single {@code <dir>/} bundle, appending either a parsed manifest or an error. */
    private void scanOne(Path dir, List<Parsed> parsed, List<PluginError> errors) {
        // Display path for errors: the directory name, which is the admin-facing id namespace.
        String dirName = dir.getFileName().toString();
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            errors.add(new PluginError(dirName, "UNPARSEABLE_MANIFEST", "missing " + MANIFEST_FILE));
            return;
        }
        TilePluginManifest manifest;
        try {
            String content = Files.readString(manifestFile, StandardCharsets.UTF_8);
            manifest = TilePluginParser.parse(dirName, content);
        } catch (TilePluginParser.ParseException e) {
            log.warn("tile plugin {} rejected: {} ({})", dirName, e.getMessage(), e.code());
            errors.add(new PluginError(dirName, e.code(), e.getMessage()));
            return;
        } catch (IOException e) {
            log.warn("tile plugin {} manifest could not be read", dirName, e);
            errors.add(new PluginError(dirName, "UNPARSEABLE_MANIFEST", e.getMessage()));
            return;
        }
        if (!Files.isRegularFile(dir.resolve(HTML_FILE))) {
            errors.add(new PluginError(dirName, "MISSING_HTML", "missing " + HTML_FILE));
            return;
        }
        parsed.add(new Parsed(manifest, dir));
    }

    /** A parsed bundle: its manifest plus the concrete directory it was found in (safe, from scan). */
    private record Parsed(TilePluginManifest manifest, Path dir) {}

    /** A single failed plugin bundle — surfaced via {@code /tile-plugins?errors=true}. */
    public record PluginError(String path, String code, String message) {}

    private record Snapshot(
            long signature,
            List<TilePluginManifest> manifests,
            Map<String, TilePluginManifest> byId,
            Map<String, Path> dirById,
            List<PluginError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), Map.of(), List.of());
        }
    }
}
