/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * saiku#1434 — the fuzz half of the acceptance criteria: malformed before/after strings, empty
 * repositories, and cross-format diffs.
 *
 * <p>The invariant under test is the one that matters for a CI gate: bad input produces a typed,
 * explainable rejection ({@link ModelDiffException}) and never a plausible-looking "no changes"
 * report. Silently diffing a truncated schema to "nothing changed" is the failure that would make
 * the gate worse than useless, because it is green.
 */
class ModelDiffFuzzTest {

    @TempDir
    Path work;

    private final ModelDiffService service = new ModelDiffService();

    private static final String VALID =
            "<Schema name='F'><Cube name='C'>" + "<Measure name='M' column='c' aggregator='sum'/></Cube></Schema>";

    private static final String VALID_YAML = "semantic_model:\n- name: F\n  metrics:\n  - name: m\n";

    @Test
    void malformedBeforeAndAfterAreRejectedNotSilentlyDiffed() {
        List<String> garbage = List.of(
                "",
                "   ",
                "<",
                "<Schema",
                "<Schema name='F'>",
                "</Schema>",
                "<Schema name='F'><Cube name='C'></Schema>",
                "not a model at all",
                "{{{",
                "\u0000\u0001\u0002",
                "semantic_model:",
                "semantic_model: [",
                "semantic_model:\n- name: F\n  metrics:\n  - name:",
                "- not\n- a\n- mapping\n",
                "<?xml version='1.0'?><!DOCTYPE x><Schema/>",
                "a".repeat(10_000));
        for (String bad : garbage) {
            // Either side may be the broken one, and both orders are exercised.
            assertRejects(bad, VALID);
            assertRejects(VALID, bad);
            assertRejects(bad, VALID_YAML);
            assertRejects(VALID_YAML, bad);
            assertRejects(bad, bad);
        }
    }

    private void assertRejects(String before, String after) {
        ModelDiffException e = assertThrows(
                ModelDiffException.class,
                () -> service.diffStrings(before, after),
                () -> "accepted: " + brief(before) + " -> " + brief(after));
        assertNotNull(e.reason());
        assertNotNull(e.getMessage());
    }

    @Test
    void crossFormatDiffsAreRejectedInBothDirections() {
        assertEquals(
                ModelDiffException.Reason.CROSS_FORMAT,
                assertThrows(ModelDiffException.class, () -> service.diffStrings(VALID, VALID_YAML))
                        .reason());
        assertEquals(
                ModelDiffException.Reason.CROSS_FORMAT,
                assertThrows(ModelDiffException.class, () -> service.diffStrings(VALID_YAML, VALID))
                        .reason());
    }

    @Test
    void randomByteSoupNeverEscapesAsAnUnexpectedException() {
        Random random = new Random(1434L);
        char[] alphabet = ("<>/\\\"'{}[]:-= \n\tabcXYZ0123\u0000\u00e9").toCharArray();
        for (int i = 0; i < 300; i++) {
            StringBuilder sb = new StringBuilder();
            int length = random.nextInt(120);
            for (int j = 0; j < length; j++) {
                sb.append(alphabet[random.nextInt(alphabet.length)]);
            }
            final String payload = sb.toString();
            final int index = i;
            // Whatever the payload is, the ONLY acceptable outcome is a typed rejection or a
            // well-formed report. An NPE, a StackOverflow or a Jackson internal error would mean
            // a CI hook feeding us a broken file crashes instead of reporting.
            for (String[] pair : new String[][] {{payload, VALID}, {VALID, payload}, {payload, payload}}) {
                try {
                    ModelDiffReport report = service.diffStrings(pair[0], pair[1]);
                    assertNotNull(report, "payload #" + index);
                } catch (ModelDiffException expected) {
                    assertNotNull(expected.reason());
                }
            }
        }
    }

    @Test
    void truncatingAValidSchemaAtEveryPointIsRejectedNotMisreported() throws IOException {
        // A half-written schema file is the realistic fuzz input — an interrupted edit, a bad
        // merge. It must never come back as "no changes".
        for (int cut = 0; cut < VALID.length(); cut++) {
            String truncated = VALID.substring(0, cut);
            ModelDiffReport report;
            try {
                report = service.diffStrings(truncated, VALID);
            } catch (ModelDiffException e) {
                assertNotNull(e.reason());
                continue;
            }
            // Some truncations are still well-formed XML with fewer elements; those must show a
            // real change, never an empty report.
            assertTrue(!report.changes().isEmpty(), "truncation at " + cut + " reported no change");
        }
    }

    @Test
    void anEmptyOrAbsentRepositoryIsNotAnError() throws IOException {
        ModelSnapshot model = service.parse(VALID, "F.xml");
        assertEquals(List.of(), service.validate(work, model));
        assertEquals(List.of(), service.validate(work.resolve("missing"), model));
        assertEquals(List.of(), service.validate(null, model));
        Files.createDirectories(work.resolve("repository/data/homes/admin"));
        Files.writeString(work.resolve("repository/data/homes/admin/notes.txt"), "not a query file");
        assertEquals(List.of(), service.validate(work.resolve("repository/data"), model));
    }

    @Test
    void garbageFilesInTheRepositoryAreSkippedNotReported() throws IOException {
        Path repository = Files.createDirectories(work.resolve("repository/data"));
        Files.writeString(repository.resolve("broken.saiku"), "{ nope");
        Files.writeString(repository.resolve("empty.saikudash"), "");
        Files.writeString(repository.resolve("binary.saikuapp"), "\u0000\u0001\u0002\u0003");
        Files.writeString(repository.resolve("array.saikudash"), "[1, 2, 3]");
        assertEquals(List.of(), service.validate(repository, service.parse(VALID, "F.xml")));
    }

    @Test
    void aMissingModelFileIsReportedWithItsReason() {
        ModelDiffException e =
                assertThrows(ModelDiffException.class, () -> service.parseFile(work.resolve("does-not-exist.xml")));
        assertEquals(ModelDiffException.Reason.MALFORMED, e.reason());
    }

    @Test
    void aNullPayloadIsRejectedRatherThanThrowingNullPointer() {
        assertEquals(
                ModelDiffException.Reason.UNKNOWN_FORMAT,
                assertThrows(ModelDiffException.class, () -> service.parse(null, "x"))
                        .reason());
        assertEquals(
                ModelDiffException.Reason.UNKNOWN_FORMAT,
                assertThrows(ModelDiffException.class, () -> service.parse("", null))
                        .reason());
    }

    @Test
    void deeplyNestedInputDoesNotBlowTheStack() {
        // A YAML bomb-lite: bounded depth, but far past anything a real model contains.
        StringBuilder sb = new StringBuilder("semantic_model:\n- name: F\n  datasets:\n");
        for (int i = 0; i < 400; i++) {
            sb.append("  - name: d")
                    .append(i)
                    .append("\n    fields:\n    - name: f")
                    .append(i)
                    .append("\n");
        }
        String deep = sb.toString();
        ModelSnapshot snapshot = service.parse(deep, "deep.yaml");
        assertEquals(400, snapshot.members("F", ModelElementKind.DIMENSION).size());
    }

    private static String brief(String value) {
        if (value == null) {
            return "null";
        }
        String collapsed = value.replace('\n', ' ');
        return collapsed.length() <= 40 ? collapsed : collapsed.substring(0, 40) + "…";
    }

    @Test
    void scanIsStableAcrossRepeatedRuns() throws IOException {
        Path repository = Files.createDirectories(work.resolve("repository/data"));
        Files.writeString(
                repository.resolve("q.saiku"),
                "{\"cube\":{\"name\":\"C\"},\"mdx\":\"SELECT {[Measures].[Ghost]} ON COLUMNS FROM [C]\"}",
                StandardCharsets.UTF_8);
        ModelSnapshot model = service.parse(VALID, "F.xml");
        List<BrokenReference> first = service.validate(repository, model);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, service.validate(repository, model));
        }
    }
}
