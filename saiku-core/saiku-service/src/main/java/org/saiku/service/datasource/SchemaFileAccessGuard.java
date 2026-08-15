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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confines Mondrian schema reads to directories Saiku is supposed to serve from.
 *
 * <p>saiku#1845: a data source's {@code Catalog=} is an arbitrary reference that Saiku hands to a
 * file reader. Nothing constrained it, so {@code Catalog=file:/etc/shadow} turned two ordinary
 * features into a file-disclosure primitive:
 *
 * <ul>
 *   <li>{@code GET /admin/cube-designer/schema/{id}} read the path and returned its bytes in the
 *       HTTP response — arbitrary file read, exfiltrated directly to the caller.
 *   <li>Mondrian itself read it while loading the schema, so contents could leak through error
 *       messages and schema-parse diagnostics.
 * </ul>
 *
 * <p>Being admin-only is not the mitigation it looks like: a Saiku admin is trusted to configure
 * cubes, not to read the host filesystem as the server user. This turns "can administer BI" into
 * "can read any file the JVM can", which is a privilege boundary worth keeping.
 *
 * <p>Repository-hosted schemas were never affected — those go through
 * {@code FilesystemRepositoryManager.resolveWithinDatadir}, which already rejects traversal. This
 * class is the equivalent containment for the {@code file:} path.
 *
 * <h2>What is allowed</h2>
 *
 * Reads must resolve inside one of:
 *
 * <ul>
 *   <li>{@code ${saiku.home}} — covers both the repository and the seeded {@code data/} schemas
 *       ({@code FoodMart4.xml}, {@code Bank.xml}, …), which live beside it rather than inside it.
 *   <li>the repository data directory, when it is configured outside saiku home.
 *   <li>anything an operator adds via {@code -Dsaiku.schema.allowedRoots=/a{sep}/b} (platform path
 *       separator), for sites that keep schemas on a mounted volume.
 * </ul>
 *
 * <p>Paths are compared after {@link Path#toRealPath}, so a symlink pointing out of an allowed root
 * is rejected on its target rather than its name.
 */
public final class SchemaFileAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(SchemaFileAccessGuard.class);

    /** Operator escape hatch for schemas kept outside saiku home. */
    public static final String EXTRA_ROOTS_PROPERTY = "saiku.schema.allowedRoots";

    private final List<Path> allowedRoots;

    SchemaFileAccessGuard(Collection<Path> roots) {
        List<Path> canonical = new ArrayList<>();
        for (Path r : roots) {
            if (r == null) {
                continue;
            }
            Path c = canonicalise(r);
            if (c != null && !canonical.contains(c)) {
                canonical.add(c);
            }
        }
        this.allowedRoots = List.copyOf(canonical);
    }

    /**
     * Build a guard from {@code saiku.home}, the repository data directory, and any operator-supplied
     * extra roots.
     *
     * @param repositoryDataDir the repository's data directory; may be null when unknown
     */
    public static SchemaFileAccessGuard fromEnvironment(String repositoryDataDir) {
        Set<Path> roots = new LinkedHashSet<>();
        String home = System.getProperty("saiku.home");
        if (home != null && !home.isBlank()) {
            roots.add(Paths.get(home));
        }
        if (repositoryDataDir != null && !repositoryDataDir.isBlank()) {
            roots.add(Paths.get(repositoryDataDir));
        }
        String extra = System.getProperty(EXTRA_ROOTS_PROPERTY);
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    roots.add(Paths.get(part.trim()));
                }
            }
        }
        SchemaFileAccessGuard guard = new SchemaFileAccessGuard(roots);
        if (guard.allowedRoots.isEmpty()) {
            // Nothing resolvable to anchor against. Refusing every file: read would break a
            // deployment whose saiku.home isn't a system property (a plain WAR in Tomcat), so
            // warn loudly and stay permissive rather than take the server's cubes down.
            log.warn(
                    "No readable schema root could be determined (saiku.home unset and no {}). "
                            + "file: schema catalogs will NOT be confined. Set {} to restore containment.",
                    EXTRA_ROOTS_PROPERTY,
                    EXTRA_ROOTS_PROPERTY);
        } else {
            log.info("Mondrian schema reads confined to: {}", guard.allowedRoots);
        }
        return guard;
    }

    /** Roots this guard permits, canonicalised. Empty means "unconfigured — allow everything". */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * True when {@code candidate} may be read.
     *
     * <p>An unconfigured guard (no resolvable roots) allows everything — see
     * {@link #fromEnvironment}.
     */
    public boolean isAllowed(Path candidate) {
        if (allowedRoots.isEmpty()) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        Path c = canonicalise(candidate);
        if (c == null) {
            return false;
        }
        for (Path root : allowedRoots) {
            if (c.equals(root) || c.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throw unless {@code candidate} may be read.
     *
     * <p>The message deliberately names the rejected path but NOT the allowed roots — the caller is
     * frequently an HTTP response, and echoing the server's directory layout to whoever just probed
     * it is the same class of leak this guard exists to close. The roots are logged at startup for
     * the operator instead.
     */
    public void assertReadable(Path candidate) throws IOException {
        if (!isAllowed(candidate)) {
            log.warn("Refused schema read outside the permitted roots {}: {}", allowedRoots, candidate);
            throw new IOException("Refusing to read a schema outside the Saiku data directories: " + candidate);
        }
    }

    /**
     * Resolve to a real path for comparison. Falls back to lexical normalisation when the file does
     * not exist — a missing file still needs a containment verdict, and {@code ../} must not slip
     * through just because the target is absent.
     */
    private static Path canonicalise(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            try {
                return p.toAbsolutePath().normalize();
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }
}
