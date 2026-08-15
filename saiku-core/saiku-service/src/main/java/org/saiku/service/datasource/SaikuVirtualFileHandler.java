/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.datasource;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import mondrian.spi.VirtualFileHandler;
import mondrian.spi.impl.ApacheVfs2VirtualFileHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teaches Mondrian how to read a schema out of the Saiku repository.
 *
 * <p>saiku#1844: every Mondrian data source created through the admin UI is written with
 * {@code Catalog=mondrian://<schema>} ({@code DataSourceMapper}), but no VFS provider for that
 * scheme has ever been registered in the OSS build. Mondrian's stock handler rejected it with
 * {@code Virtual file is not readable: mondrian://…}, so the connection failed and the cube never
 * appeared — for ANY such data source, including every schema written by the Cube Designer's Save
 * button. Seeded data sources escaped only because they use {@code Catalog=file:}.
 *
 * <p>Mondrian instantiates this via the {@code mondrian.spi.virtualFileHandlerClass} property, using
 * a no-arg constructor, so the repository is injected through {@link #setRepositoryReader} at
 * startup rather than through the constructor. Until that call lands — and for every reference this
 * class doesn't own — reads are delegated to Mondrian's stock {@link ApacheVfs2VirtualFileHandler},
 * so behaviour for {@code file:} / {@code http:} / {@code jar:} catalogs is exactly as before.
 *
 * <p>Reads go through a {@link Function} rather than the filesystem so this holds for repository
 * backends with no filesystem at all — Saiku Cloud overrides {@code createRepositoryManager()} to
 * route paths through Postgres.
 */
public class SaikuVirtualFileHandler implements VirtualFileHandler {

    private static final Logger log = LoggerFactory.getLogger(SaikuVirtualFileHandler.class);

    /** Mondrian's property naming the handler implementation to instantiate. */
    public static final String HANDLER_PROPERTY = "mondrian.spi.virtualFileHandlerClass";

    /**
     * Reads a repository path, returning null when absent. Static because Mondrian owns this
     * object's lifecycle and constructs it reflectively with no arguments. Volatile so the
     * connection threads see the value published by the startup thread.
     */
    private static volatile Function<String, String> repositoryReader;

    /**
     * Confines {@code file:} catalogs to the Saiku data directories (saiku#1845). Null until
     * startup wiring runs, which leaves Mondrian's stock behaviour in place.
     */
    private static volatile SchemaFileAccessGuard fileGuard;

    /**
     * Unsaved schemas registered for a one-shot query preview (saiku#1872), keyed by the catalog
     * path they are served under.
     *
     * <p>The Cube Designer needs to run a schema the user has not saved yet. Writing it to disk
     * would leave litter behind on a crash and would have to satisfy the file guard; serving it
     * from memory through the handler Mondrian already consults costs nothing and disappears the
     * moment the preview finishes.
     *
     * <p>Consulted BEFORE the repository, so a preview id can never shadow or be shadowed by a real
     * schema — the ids are random and the path prefix is reserved.
     */
    private static final java.util.Map<String, String> PREVIEWS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Reserved path prefix for preview schemas. Not a real repository location. */
    public static final String PREVIEW_PREFIX = "/__preview__/";

    /**
     * Serve {@code xml} under a fresh preview catalog path.
     *
     * @return the catalog reference to hand Mondrian; pass it to {@link #unregisterPreview} when done
     */
    public static String registerPreview(String xml) {
        String path = PREVIEW_PREFIX + java.util.UUID.randomUUID() + ".xml";
        PREVIEWS.put(path, xml);
        return path;
    }

    /** Stop serving a preview. Safe to call twice; must be called in a finally. */
    public static void unregisterPreview(String path) {
        if (path != null) {
            PREVIEWS.remove(path);
        }
    }

    /** Test seam: how many previews are currently held. Should return to 0 after every request. */
    static int previewCount() {
        return PREVIEWS.size();
    }

    private final VirtualFileHandler delegate = new ApacheVfs2VirtualFileHandler();

    /**
     * Install this handler as Mondrian's file handler and point it at the repository.
     *
     * <p>Idempotent, and safe to call before Mondrian has loaded: the property is read lazily the
     * first time Mondrian resolves a catalog. Does not override an explicit operator choice — if
     * {@code mondrian.spi.virtualFileHandlerClass} is already set to something else, that wins and
     * only the reader is installed.
     */
    public static void install(Function<String, String> reader) {
        repositoryReader = reader;
        String existing = System.getProperty(HANDLER_PROPERTY);
        if (existing != null && !existing.isBlank() && !existing.equals(SaikuVirtualFileHandler.class.getName())) {
            log.warn(
                    "{} is already set to {}; leaving it alone. Repository-hosted Mondrian schemas"
                            + " (Catalog=mondrian://…) will not resolve.",
                    HANDLER_PROPERTY,
                    existing);
            return;
        }
        System.setProperty(HANDLER_PROPERTY, SaikuVirtualFileHandler.class.getName());
    }

    /** Point the handler at a repository reader without touching the Mondrian property. */
    public static void setRepositoryReader(Function<String, String> reader) {
        repositoryReader = reader;
    }

    /** Confine {@code file:} catalogs to the given roots (saiku#1845). Null disables containment. */
    public static void setFileGuard(SchemaFileAccessGuard guard) {
        fileGuard = guard;
    }

    /**
     * The registered preview XML for a catalog reference, or null.
     *
     * <p>Mondrian may hand back the reference with or without its {@code mondrian://} scheme
     * depending on the path, so both spellings are tried — a preview that failed to resolve would
     * silently fall through to a repository lookup and produce a confusing "no schema" error.
     */
    private static String previewFor(String url) {
        if (url == null || PREVIEWS.isEmpty()) {
            return null;
        }
        String trimmed = url.trim();
        String direct = PREVIEWS.get(trimmed);
        if (direct != null) {
            return direct;
        }
        for (String candidate : MondrianCatalogResolver.candidatePaths(trimmed)) {
            String hit = PREVIEWS.get(candidate);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Override
    public InputStream readVirtualFile(String url) throws IOException {
        Function<String, String> reader = repositoryReader;
        // saiku#1872: an in-flight preview schema wins over the repository. Checked first so a
        // preview never depends on repository state, and so the reserved prefix cannot be
        // satisfied by a real file that happens to share the path.
        String preview = previewFor(url);
        if (preview != null) {
            return new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8));
        }
        if (MondrianCatalogResolver.isRepositoryReference(url)) {
            if (reader != null) {
                String xml = MondrianCatalogResolver.resolve(url, reader);
                if (xml != null) {
                    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
                }
            }
            // saiku#1845: a repository reference that didn't resolve must NOT fall through to the
            // filesystem. A bare path like "../../etc/shadow" satisfies isRepositoryReference()
            // (no scheme), and handing it to the VFS delegate would resolve it against the
            // process working directory — turning a failed repository lookup into a file read.
            throw new FileNotFoundException("No schema in the Saiku repository for catalog " + url + " (tried "
                    + MondrianCatalogResolver.candidatePaths(url) + ")");
        }
        SchemaFileAccessGuard guard = fileGuard;
        if (guard != null && url != null && url.trim().startsWith("file:")) {
            guard.assertReadable(java.nio.file.Path.of(stripFileScheme(url.trim())));
        }
        return delegate.readVirtualFile(url);
    }

    /**
     * Strip the {@code file:} scheme (and any {@code //authority}) off a file URL, leaving the path.
     *
     * <p>saiku#1661: on Windows a file URL for a local path is {@code file:/C:/...}, which leaves a
     * leading slash before the drive letter that {@link java.nio.file.Path#of} rejects. Drop it so
     * {@code /C:/x} becomes {@code C:/x}; genuine POSIX absolute paths are untouched.
     */
    static String stripFileScheme(String fileUrl) {
        String path = fileUrl.substring("file:".length());
        if (path.startsWith("//")) {
            int slash = path.indexOf('/', 2);
            path = slash >= 0 ? path.substring(slash) : path.substring(2);
        }
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        return path;
    }
}
