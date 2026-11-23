/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks a Saiku repository and reports every cube / measure / level reference that the
 * <em>after</em> model no longer resolves (saiku#1434).
 *
 * <p>Scanned artefacts: saved queries ({@code *.saiku}), dashboards ({@code *.saikudash}) and
 * App Builder apps ({@code *.saikuapp}) — the three file types that bind to a cube. Both binding
 * styles are understood:
 *
 * <ul>
 *   <li><strong>MDX</strong> — a saved query's {@code mdx} property, or a string value that looks
 *       like an MDX statement. Bracketed members are extracted with a deliberately narrow regex
 *       rather than a real MDX parse: this runs over a whole repository on every PR, and a
 *       member-name regex is what a reviewer can reason about. It over-collects (comments,
 *       string literals) rather than under-collects, which is the safe direction for a safety
 *       net.
 *   <li><strong>Structured</strong> — the query2 body a dashboard tile carries ({@code measures:
 *       [{name}]} , {@code rows: [{dimension, level}]}, {@code kpi: {measure}}) and the
 *       {@code cube.cubeName} of each tile.
 * </ul>
 *
 * <h2>False positives are the failure mode that matters</h2>
 *
 * The acceptance criterion in saiku#1434 is a &lt; 5% false-positive rate against intentional
 * renames, so the scanner is deliberately quiet:
 *
 * <ul>
 *   <li>Matching is case-insensitive, because Mondrian resolves member names that way.
 *   <li>When a file's cube is not in the model at all, the cube is reported <em>once</em> and the
 *       file's member references are then <strong>not</strong> checked. A repository routinely
 *       holds several schemas, and flagging every member of a cube belonging to a different
 *       datasource as broken would be a wall of noise, not a finding.
 *   <li>A level reference whose dimension is missing reports the dimension, not the level, for the
 *       same reason.
 *   <li>A file that is not valid JSON is skipped, not reported. Broken file syntax is a different
 *       validator's job.
 * </ul>
 */
public final class BrokenReferenceScanner {

    private static final Logger LOG = LoggerFactory.getLogger(BrokenReferenceScanner.class);

    /** File types that bind to a cube and can therefore hold a stale reference. */
    static final Set<String> SCANNED_EXTENSIONS = Set.of(".saiku", ".saikudash", ".saikuapp");

    /** Files larger than this are skipped — a dashboard this big is a binary blob, not content. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final Pattern MDX_MEASURE = Pattern.compile("\\[Measures\\]\\.\\[([^\\]]+)]");
    private static final Pattern MDX_CUBE = Pattern.compile("\\bFROM\\s+\\[([^\\]]+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MDX_DIMENSION_LEVEL =
            Pattern.compile("\\[([A-Za-z_][^\\].]*)]\\s*\\.\\s*\\[([^\\]]+)]");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Scan {@code repositoryRoot} for references that {@code after} cannot resolve.
     *
     * @param repositoryRoot the directory to walk (typically {@code <saiku-home>/repository/data});
     * a missing directory yields an empty list rather than an error — a
     *                       repository with no saved content simply has nothing to break
     * @return references sorted by file then location, de-duplicated
     */
    public List<BrokenReference> scan(Path repositoryRoot, ModelSnapshot after) {
        if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
            LOG.debug("Broken-reference scan: {} is not a directory; nothing to scan", repositoryRoot);
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(repositoryRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isScannable(file) && attrs.size() <= MAX_FILE_BYTES) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // An unreadable file (permissions, a broken symlink) is not a broken
                    // reference. Skip it and keep walking.
                    LOG.debug("Broken-reference scan: skipping unreadable {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk repository at " + repositoryRoot, e);
        }

        Set<BrokenReference> found = new LinkedHashSet<>();
        for (Path file : files) {
            found.addAll(scanFile(file, repositoryRoot, after));
        }
        List<BrokenReference> out = new ArrayList<>(found);
        out.sort(Comparator.comparing(BrokenReference::file, Comparator.nullsLast(String::compareTo))
                .thenComparing(BrokenReference::location, Comparator.nullsLast(String::compareTo))
                .thenComparing(BrokenReference::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return List.copyOf(out);
    }

    private static boolean isScannable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : SCANNED_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private List<BrokenReference> scanFile(Path file, Path root, ModelSnapshot after) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        JsonNode root2;
        try {
            root2 = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // Not JSON (or truncated): a different validator's problem, and reporting it here
            // would be a guaranteed false positive.
            LOG.debug("Broken-reference scan: {} is not readable JSON — skipped", file);
            return List.of();
        }
        if (root2 == null || !root2.isObject()) {
            return List.of();
        }
        List<BrokenReference> out = new ArrayList<>();
        walk(root2, relative, null, after, out, null, Set.of());
        return out;
    }

    /**
     * Recursive walk over the JSON tree.
     *
     * <p>Two things are threaded down the tree:
     *
     * <ul>
     *   <li><b>inheritedCube</b> — the cube resolved from the nearest enclosing object, so a
     *       nested {@code query.body.measures: [{name}]} knows what to resolve against. A tile
     *       declares its cube once, several levels above the members it binds.
     *   <li><b>suppressedCubes</b> — cubes already reported as missing from this file. Once a
     *       cube is known-gone, every reference into it is skipped: the model has no opinion about
     *       that cube's members, so reporting them would be inventing findings. Suppression is
     *       per-cube rather than per-subtree, so a dashboard that binds tiles against two cubes
     *       still gets its healthy cube checked.
     * </ul>
     */
    private void walk(
            JsonNode node,
            String file,
            String location,
            ModelSnapshot model,
            List<BrokenReference> out,
            String inheritedCube,
            Set<String> suppressedCubes) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child, file, location, model, out, inheritedCube, suppressedCubes);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        // A tile id is the only anchor a human recognises inside a dashboard, so it beats a JSON
        // pointer once one exists.
        String here = location;
        String tileId = text(node, "id");
        if (tileId != null && !tileId.isBlank() && !tileId.equals(location)) {
            here = tileId;
        }
        String childLocation = here;

        String declaredCube = resolveCubeContext(node);
        String cube = declaredCube != null ? declaredCube : inheritedCube;
        Set<String> suppressed = suppressedCubes;
        if (cube != null) {
            if (suppressedCubes.contains(cube.toLowerCase(Locale.ROOT))) {
                cube = null;
            } else if (!model.hasCube(cube)) {
                out.add(new BrokenReference(
                        file, childLocation, ModelElementKind.CUBE, cube, null, cube, BrokenReference.UNKNOWN_CUBE));
                suppressed = new LinkedHashSet<>(suppressedCubes);
                suppressed.add(cube.toLowerCase(Locale.ROOT));
                cube = null;
            }
        }
        if (cube != null) {
            // Structured bindings: kpi tiles, query2 bodies, filters, sorts.
            JsonNode kpi = node.get("kpi");
            if (kpi != null && kpi.isObject()) {
                checkMeasure(text(kpi, "measure"), file, childLocation, cube, model, out);
            }
            for (JsonNode name : node.path("measures")) {
                checkMeasure(
                        name.isObject() ? text(name, "name") : name.asText(null),
                        file,
                        childLocation,
                        cube,
                        model,
                        out);
            }
            for (String axis : List.of("rows", "columns", "filters", "sorts", "breaks")) {
                for (JsonNode entry : node.path(axis)) {
                    String dimension = text(entry, "dimension");
                    if (dimension == null) {
                        continue;
                    }
                    String level = text(entry, "level");
                    if (level == null || level.isBlank()) {
                        // A bare dimension reference — e.g. the Time Members shorthand.
                        checkDimension(dimension, file, childLocation, cube, model, out);
                    } else {
                        checkLevel(dimension, level, file, childLocation, cube, model, out);
                    }
                    String member = text(entry, "member");
                    if (member != null && member.contains("[")) {
                        checkMdx(member, file, childLocation, cube, model, out);
                    }
                }
            }
        }

        // A saved query's MDX. Its cube comes from the file's own cube binding when there is one
        // and from the statement's FROM clause otherwise, so a query file that carries only MDX
        // is still checked against the right cube.
        String mdx = text(node, "mdx");
        if (mdx != null && looksLikeMdx(mdx)) {
            String mdxCube = cube != null ? cube : cubeFromMdx(mdx);
            if (mdxCube != null && !suppressed.contains(mdxCube.toLowerCase(Locale.ROOT))) {
                checkMdx(mdx, file, childLocation, mdxCube, model, out);
            }
        }

        for (Map.Entry<String, JsonNode> child : node.properties()) {
            walk(child.getValue(), file, childLocation, model, out, cube, suppressed);
        }
    }

    private static String resolveCubeContext(JsonNode node) {
        JsonNode cube = node.get("cube");
        if (cube != null && cube.isObject()) {
            String name = text(cube, "cubeName");
            if (name != null) {
                return name;
            }
            String unique = text(cube, "uniqueName");
            if (unique != null) {
                // "[unknown_foodmart].[FoodMart].[FoodMart].[Sales]" — the cube is the last part.
                int close = unique.lastIndexOf(']');
                String tail = close > 0 ? unique.substring(0, close) : unique;
                int open = tail.lastIndexOf('[');
                if (open >= 0 && open + 1 < tail.length()) {
                    return tail.substring(open + 1);
                }
            }
            name = text(cube, "name");
            if (name != null) {
                return name;
            }
        }
        return text(node, "cubeName");
    }

    private void checkMdx(
            String mdx, String file, String location, String cube, ModelSnapshot model, List<BrokenReference> out) {
        Matcher measures = MDX_MEASURE.matcher(mdx);
        while (measures.find()) {
            checkMeasure(measures.group(1), file, location, cube, model, out);
        }
        Matcher dimensionLevels = MDX_DIMENSION_LEVEL.matcher(mdx);
        while (dimensionLevels.find()) {
            String dimension = dimensionLevels.group(1).trim();
            if ("Measures".equalsIgnoreCase(dimension)) {
                continue; // already handled by MDX_MEASURE
            }
            checkLevel(dimension, dimensionLevels.group(2), file, location, cube, model, out);
        }
    }

    private void checkMeasure(
            String measure, String file, String location, String cube, ModelSnapshot model, List<BrokenReference> out) {
        if (measure == null || measure.isBlank() || cube == null) {
            return;
        }
        if (!model.hasMeasure(cube, measure)) {
            out.add(new BrokenReference(
                    file, location, ModelElementKind.MEASURE, cube, null, measure, BrokenReference.MISSING_MEASURE));
        }
    }

    private void checkDimension(
            String dimension,
            String file,
            String location,
            String cube,
            ModelSnapshot model,
            List<BrokenReference> out) {
        if (dimension == null || dimension.isBlank() || cube == null) {
            return;
        }
        if (!model.hasDimension(cube, dimension)) {
            out.add(new BrokenReference(
                    file,
                    location,
                    ModelElementKind.DIMENSION,
                    cube,
                    null,
                    dimension,
                    BrokenReference.MISSING_DIMENSION));
        }
    }

    private void checkLevel(
            String dimension,
            String level,
            String file,
            String location,
            String cube,
            ModelSnapshot model,
            List<BrokenReference> out) {
        if (dimension == null || level == null || dimension.isBlank() || level.isBlank() || cube == null) {
            return;
        }
        if (!model.hasDimension(cube, dimension)) {
            // Report the missing dimension once instead of every level under it.
            checkDimension(dimension, file, location, cube, model, out);
            return;
        }
        if (!model.hasLevel(cube, dimension, level)) {
            out.add(new BrokenReference(
                    file, location, ModelElementKind.LEVEL, cube, dimension, level, BrokenReference.MISSING_LEVEL));
        }
    }

    private static String cubeFromMdx(String mdx) {
        Matcher matcher = MDX_CUBE.matcher(mdx);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static boolean looksLikeMdx(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.contains("SELECT ") && upper.contains(" FROM ");
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String asText = value.asText();
        return asText == null || asText.isBlank() ? null : asText.trim();
    }
}
