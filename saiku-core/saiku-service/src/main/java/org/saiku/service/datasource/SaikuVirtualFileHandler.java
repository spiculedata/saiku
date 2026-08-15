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

    @Override
    public InputStream readVirtualFile(String url) throws IOException {
        Function<String, String> reader = repositoryReader;
        if (reader != null && MondrianCatalogResolver.isRepositoryReference(url)) {
            String xml = MondrianCatalogResolver.resolve(url, reader);
            if (xml != null) {
                return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            }
            // Fall through to the delegate rather than failing outright: a bare path with no
            // scheme is also a legal relative file reference, and the delegate's error message
            // for a genuinely missing file is more useful than one invented here.
            if (url != null && url.trim().startsWith(MondrianCatalogResolver.SCHEME)) {
                throw new FileNotFoundException("No schema in the Saiku repository for catalog " + url + " (tried "
                        + MondrianCatalogResolver.candidatePaths(url) + ")");
            }
        }
        return delegate.readVirtualFile(url);
    }
}
