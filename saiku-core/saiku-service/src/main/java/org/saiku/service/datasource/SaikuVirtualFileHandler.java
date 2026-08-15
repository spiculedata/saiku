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

    @Override
    public InputStream readVirtualFile(String url) throws IOException {
        Function<String, String> reader = repositoryReader;
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
