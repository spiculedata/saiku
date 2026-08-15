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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Resolves a Mondrian {@code Catalog=} reference that points into the Saiku repository.
 *
 * <p>saiku#1844: a data source created through the admin UI is written with
 * {@code Catalog=mondrian://<schema>} (see {@code DataSourceMapper}). That scheme is a
 * Pentaho/Jackrabbit-era convention — no VFS provider for it has ever been registered in the OSS
 * build, so Mondrian's file handler rejected it with a bare
 * {@code Virtual file is not readable: mondrian://...} and the connection failed. The Cube Designer
 * meanwhile resolved the very same references correctly, because it carried its own private copy of
 * the lookup rule. This class is that rule, lifted out so the read path and the connect path can't
 * drift apart again.
 *
 * <p>The rule itself is small and deliberately forgiving, because two conventions are in the wild:
 *
 * <ul>
 *   <li>{@code mondrian://datasources/Foo.xml} — a full repository path behind the scheme.
 *   <li>{@code mondrian://Foo} — just a name; schemas are written by
 *       {@code RepositoryDatasourceManager.addSchema} at {@code /datasources/<name>.xml}, so that
 *       conventional path is tried second.
 * </ul>
 *
 * <p>Lookups go through a caller-supplied fetcher rather than touching the filesystem, so this stays
 * correct for repository backends that have no filesystem at all — Saiku Cloud overrides
 * {@code createRepositoryManager()} to route paths through Postgres.
 */
public final class MondrianCatalogResolver {

    /** The scheme Saiku writes into {@code Catalog=} for repository-hosted schemas. */
    public static final String SCHEME = "mondrian://";

    private MondrianCatalogResolver() {}

    /**
     * True when {@code catalog} names a schema held in the Saiku repository rather than one Mondrian
     * can fetch itself. Anything carrying a scheme Mondrian already understands ({@code file:},
     * {@code http:}, {@code res:}, …) is left alone.
     */
    public static boolean isRepositoryReference(String catalog) {
        if (catalog == null) {
            return false;
        }
        String c = catalog.trim();
        if (c.isEmpty()) {
            return false;
        }
        if (c.startsWith(SCHEME)) {
            return true;
        }
        // A bare repository path — "/datasources/Foo.xml" or "Foo". Anything with a scheme
        // ("file:", "res:", "http:", "jar:") belongs to Mondrian's own handler. Guard against
        // Windows drive letters ("C:/x"), which look like a one-character scheme.
        int colon = c.indexOf(':');
        return colon <= 1;
    }

    /**
     * Repository paths to try, in order, for a catalog reference. Never empty when
     * {@link #isRepositoryReference} is true.
     */
    public static List<String> candidatePaths(String catalog) {
        List<String> out = new ArrayList<>(2);
        if (catalog == null) {
            return out;
        }
        String name = catalog.trim();
        if (name.startsWith(SCHEME)) {
            name = name.substring(SCHEME.length());
        }
        // Strip any slashes the scheme left behind: "mondrian:///datasources/x.xml" is the shape
        // DataSourceMapper produces when the admin's schema field already begins with "/".
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return out;
        }
        // Verbatim first — this is what a full repository path resolves as.
        out.add("/" + name);
        // Then the conventional location addSchema writes to, for a bare "mondrian://Name".
        String conventional = "/datasources/" + name + ".xml";
        if (!conventional.equals("/" + name)) {
            out.add(conventional);
        }
        return out;
    }

    /**
     * Fetch the schema XML for a catalog reference, or null when no candidate path resolves.
     *
     * @param catalog the raw {@code Catalog=} value
     * @param fetcher reads a repository path, returning null when absent. Typically
     *     {@code IDatasourceManager::getInternalFileData}.
     */
    public static String resolve(String catalog, Function<String, String> fetcher) {
        for (String path : candidatePaths(catalog)) {
            String data = fetcher.apply(path);
            if (data != null && !data.isBlank()) {
                return data;
            }
        }
        return null;
    }
}
