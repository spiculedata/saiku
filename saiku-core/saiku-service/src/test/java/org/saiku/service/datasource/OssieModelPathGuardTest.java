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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#2534 — an OSSIE datasource's {@code ossieYaml} property is a host path Saiku reads and
 * serves back verbatim; it must resolve inside the model directories, not anywhere the JVM can
 * reach. Same threat as {@link SchemaFileAccessGuardTest} (saiku#1845) on a sink it does not
 * cover.
 */
public class OssieModelPathGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String previousHome;
    private String previousExtraRoots;

    @Before
    public void rememberSystemProperties() {
        previousHome = System.getProperty("saiku.home");
        previousExtraRoots = System.getProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY);
    }

    @After
    public void restoreSystemProperties() {
        if (previousHome == null) {
            System.clearProperty("saiku.home");
        } else {
            System.setProperty("saiku.home", previousHome);
        }
        if (previousExtraRoots == null) {
            System.clearProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY);
        } else {
            System.setProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY, previousExtraRoots);
        }
    }

    private static Path write(File dir, String name, String body) throws IOException {
        Path p = dir.toPath().resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    public void allowsTheSemanticModelsRootAndTheSeededDataRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        System.setProperty("saiku.home", home.getAbsolutePath());

        Path imported = write(home, "semantic-models/orders.ossie.yaml", "version: 0.1.0\n");
        Path seeded = write(home, "data/flights.ossie.yaml", "version: 0.1.0\n");

        OssieModelPathGuard.requireWithinRoots(imported); // must not throw
        OssieModelPathGuard.requireWithinRoots(seeded); // must not throw
        assertTrue(OssieModelPathGuard.isWithinRoots(seeded, OssieModelPathGuard.environmentRoots()));
    }

    @Test
    public void refusesAPathOutsideEveryRoot() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File elsewhere = tmp.newFolder("elsewhere");
        System.setProperty("saiku.home", home.getAbsolutePath());
        Path canary = write(elsewhere, "canary.txt", "SECRET");

        try {
            OssieModelPathGuard.requireWithinRoots(canary);
            fail("expected an outside path to be refused");
        } catch (IllegalArgumentException expected) {
            // The message must not enumerate the server's allowed directories — it frequently
            // ends up in an HTTP response body.
            assertFalse(expected.getMessage().contains(home.getAbsolutePath()));
        }
    }

    @Test
    public void refusesTraversalAndSymlinkEscapesOnTheRealPath() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File elsewhere = tmp.newFolder("elsewhere");
        System.setProperty("saiku.home", home.getAbsolutePath());
        Path secret = write(elsewhere, "secret.yaml", "SECRET");

        // Traversal out of the root — judged on the normalised path.
        Path traversal = home.toPath().resolve("semantic-models").resolve("../").resolve("../elsewhere/secret.yaml");
        assertFalse(OssieModelPathGuard.isWithinRoots(traversal, OssieModelPathGuard.environmentRoots()));

        // A symlink inside the root whose target is outside — judged on the target.
        Files.createDirectories(home.toPath().resolve("semantic-models"));
        Path link = home.toPath().resolve("semantic-models").resolve("escape.ossie.yaml");
        Files.createSymbolicLink(link, secret.toAbsolutePath());
        assertFalse(OssieModelPathGuard.isWithinRoots(link, OssieModelPathGuard.environmentRoots()));
        try {
            OssieModelPathGuard.requireWithinRoots(link);
            fail("expected the symlink escape to be refused");
        } catch (IllegalArgumentException expected) {
            // covered above
        }
    }

    @Test
    public void operatorExtraRootsAreHonoured() throws Exception {
        File home = tmp.newFolder("saiku-home");
        File mounted = tmp.newFolder("mounted-models");
        System.setProperty("saiku.home", home.getAbsolutePath());
        System.setProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY, mounted.getAbsolutePath());

        Path model = write(mounted, "warehouse.ossie.yaml", "version: 0.1.0\n");
        OssieModelPathGuard.requireWithinRoots(model); // must not throw

        // Multiple extra roots, platform path-separator separated.
        File second = tmp.newFolder("second-models");
        System.setProperty(
                OssieModelPathGuard.EXTRA_ROOTS_PROPERTY,
                mounted.getAbsolutePath() + java.io.File.pathSeparator + second.getAbsolutePath());
        Path other = write(second, "other.ossie.yaml", "version: 0.1.0\n");
        OssieModelPathGuard.requireWithinRoots(other); // must not throw
    }

    @Test
    public void failsClosedWhenNoRootCanBeResolved() throws Exception {
        // Neither saiku.home nor extra roots configured: refuse everything rather than fall
        // back to permissive (the schema guard's WAR availability trade-off does not apply to
        // a pointer the server itself would write).
        System.clearProperty("saiku.home");
        System.clearProperty(OssieModelPathGuard.EXTRA_ROOTS_PROPERTY);

        Path anywhere = write(tmp.newFolder("anywhere"), "model.ossie.yaml", "version: 0.1.0\n");
        assertTrue(OssieModelPathGuard.environmentRoots().isEmpty());
        try {
            OssieModelPathGuard.requireWithinRoots(anywhere);
            fail("expected an unconfigured environment to refuse every path");
        } catch (IllegalArgumentException expected) {
            // covered
        }
    }

    @Test
    public void missingTargetUnderARootIsJudgedOnItsNearestExistingAncestor() throws Exception {
        File home = tmp.newFolder("saiku-home");
        System.setProperty("saiku.home", home.getAbsolutePath());

        // A model that does not exist yet (datasource registered before the first save) must
        // still be allowed — the write into semantic-models is the point of the schema PUT.
        Path notYet = home.toPath().resolve("semantic-models").resolve("planned.ossie.yaml");
        OssieModelPathGuard.requireWithinRoots(notYet); // must not throw
    }
}
