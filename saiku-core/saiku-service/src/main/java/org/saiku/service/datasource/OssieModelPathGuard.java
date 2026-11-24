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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confines an OSSIE datasource's {@code ossieYaml} model path to directories Saiku is supposed
 * to serve models from (saiku#2534).
 *
 * <p>An Ossie datasource names its semantic-model YAML with a raw host path. Nothing constrained
 * it, so {@code ossieYaml=/etc/hostname} turned two ordinary admin features into a file
 * disclosure primitive:
 *
 * <ul>
 *   <li>{@code GET /saiku/admin/ossie/schema/{datasource}} read the path and returned its bytes
 *       in the HTTP response — arbitrary file read, exfiltrated directly to the caller.
 *   <li>{@code PUT} on the same URL wrote the request body to that path once it parsed as Ossie
 *       YAML — arbitrary file write.
 * </ul>
 *
 * <p>Being admin-only is not the mitigation it looks like — see {@link SchemaFileAccessGuard} for
 * the same argument on the Mondrian {@code Catalog=} path (saiku#1845). {@code /ossie/import/save}
 * already slugs its output into {@code <saiku-home>/semantic-models}; this class is the
 * equivalent containment for the datasource's own pointer to a model.
 *
 * <h2>What is allowed</h2>
 *
 * The path must resolve inside one of:
 *
 * <ul>
 *   <li>{@code ${saiku.home}/semantic-models} — the import-save root ({@code /ossie/import/save}).
 *   <li>{@code ${saiku.home}/data} — the launcher-seeded demo models ({@code flights.ossie.yaml},
 *       {@code tpcds.ossie.yaml}).
 *   <li>anything an operator adds via {@code -D}{@value #EXTRA_ROOTS_PROPERTY}{@code =/a{sep}/b}
 *       (platform path separator), for sites that keep models on a mounted volume.
 * </ul>
 *
 * <p>Unlike {@link SchemaFileAccessGuard#fromEnvironment}, which stays permissive when it cannot
 * anchor a root (a plain-WAR deployment would lose every cube), this guard <b>fails closed</b>:
 * an Ossie model path with no configured root is refused, because every model the server itself
 * writes or seeds lives under {@code saiku.home}, and a deployment that keeps them elsewhere says
 * so with {@value #EXTRA_ROOTS_PROPERTY}. Paths are compared after resolution to the real
 * filesystem target (the same nearest-existing-ancestor rule {@link SchemaFileAccessGuard}
 * uses), so traversal and symlinks that leave a root are judged on the target.
 */
public final class OssieModelPathGuard {

    private static final Logger log = LoggerFactory.getLogger(OssieModelPathGuard.class);

    /** Extra model directories (platform path separator) an operator may serve Ossie models from. */
    public static final String EXTRA_ROOTS_PROPERTY = "saiku.ossie.model.allowedRoots";

    private OssieModelPathGuard() {}

    /**
     * Throw {@link IllegalArgumentException} unless {@code candidate} names a model inside the
     * permitted roots. Thrown from datasource save (via {@code DataSourceMapper}) and from the
     * {@code /admin/ossie/schema} endpoints, whose callers already map the exception type to a
     * typed 400.
     *
     * <p>The message deliberately names the rejected path but NOT the allowed roots — the caller
     * is frequently an HTTP response, and echoing the server's directory layout to whoever just
     * probed it is the same class of leak this guard exists to close. The roots are logged by the
     * refusal warning for the operator instead.
     */
    public static void requireWithinRoots(Path candidate) {
        List<Path> roots = environmentRoots();
        if (isWithinRoots(candidate, roots)) {
            return;
        }
        log.warn(
                "Refused an Ossie model path outside the permitted roots {}: {} (set {} to allow another root)",
                roots,
                candidate,
                EXTRA_ROOTS_PROPERTY);
        throw new IllegalArgumentException(
                "Refusing an Ossie model path outside the permitted Saiku model directories: " + candidate);
    }

    /** Roots this guard permits, as resolved from the current environment. */
    static List<Path> environmentRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        String home = System.getProperty("saiku.home");
        if (home != null && !home.isBlank()) {
            Path h = Paths.get(home.trim());
            roots.add(h.resolve("semantic-models"));
            roots.add(h.resolve("data"));
        }
        String extra = System.getProperty(EXTRA_ROOTS_PROPERTY);
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    roots.add(Paths.get(part.trim()));
                }
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Containment test on the real (symlink-resolved) path. Delegates the path comparison to
     * {@link SchemaFileAccessGuard} but does NOT inherit its "empty roots allow everything"
     * fallback — here, no configured root refuses everything.
     */
    static boolean isWithinRoots(Path candidate, List<Path> roots) {
        if (candidate == null) {
            return false;
        }
        if (roots.isEmpty()) {
            return false;
        }
        return new SchemaFileAccessGuard(roots).isAllowed(candidate);
    }
}
