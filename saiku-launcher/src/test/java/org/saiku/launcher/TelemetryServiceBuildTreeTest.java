/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Test;

/**
 * saiku#1866 — a developer testing a local build must not be counted as an install.
 *
 * <p>The pre-existing guard only skipped when the version was absent or literally {@code "dev"},
 * which happens for IDE and unit runs where there is no manifest to read. A fat-JAR built by
 * {@code mvn package} carries the real pom version in its {@code Implementation-Version}, so
 * {@code java -jar saiku-launcher/target/saiku-<v>.jar} — the exact command used to test any change
 * — reported as a genuine install. Worse, the install id is persisted under saiku-home, so one dev
 * machine kept pinging under a stable id and looked like a long-lived customer.
 */
public class TelemetryServiceBuildTreeTest {

    @Test
    public void aJarInsideAMavenTargetDirectoryIsABuildTree() {
        assertTrue(TelemetryService.isBuildTreePath(Path.of("/home/dev/saiku/saiku-launcher/target/saiku-4.7.1.jar")));
    }

    /** Unit and IDE runs load classes from target/classes rather than a jar. */
    @Test
    public void loosClassesUnderTargetAreAlsoABuildTree() {
        assertTrue(TelemetryService.isBuildTreePath(Path.of("/home/dev/saiku/saiku-launcher/target/classes")));
    }

    @Test
    public void aDeployedInstallIsNotABuildTree() {
        assertFalse(TelemetryService.isBuildTreePath(Path.of("/opt/saiku/saiku-4.7.1.jar")));
        assertFalse(TelemetryService.isBuildTreePath(Path.of("/usr/local/lib/saiku/saiku.jar")));
    }

    /** The Docker image's layout — the single most common real install. */
    @Test
    public void theDockerImageLayoutIsNotABuildTree() {
        assertFalse(TelemetryService.isBuildTreePath(Path.of("/opt/saiku/lib/saiku-4.7.1.jar")));
    }

    /**
     * Only a whole path SEGMENT counts. A customer whose install lives under something like
     * {@code /srv/targeting/} must still be counted — matching on a substring would silently drop
     * them from the numbers.
     */
    @Test
    public void aDirectoryMerelyContainingTheWordTargetIsNotABuildTree() {
        assertFalse(TelemetryService.isBuildTreePath(Path.of("/srv/targeting/saiku/saiku.jar")));
        assertFalse(TelemetryService.isBuildTreePath(Path.of("/opt/target-practice/saiku.jar")));
    }

    @Test
    public void aNullCodeSourceIsNotTreatedAsABuildTree() {
        assertFalse(TelemetryService.isBuildTreePath(null));
    }

    /** The real call must not throw, whatever the runtime layout. */
    @Test
    public void resolvingTheRunningCodeSourceNeverThrows() {
        TelemetryService.isRunningFromBuildTree();
    }

    /**
     * And under surefire — which runs from {@code target/test-classes} — it must answer true. This
     * doubles as proof the detection works on a real classloader, not just on synthetic paths.
     */
    @Test
    public void theTestRunItselfIsDetectedAsABuildTree() {
        assertTrue(
                "surefire runs out of target/, so this must be recognised as a build tree",
                TelemetryService.isRunningFromBuildTree());
    }
}
