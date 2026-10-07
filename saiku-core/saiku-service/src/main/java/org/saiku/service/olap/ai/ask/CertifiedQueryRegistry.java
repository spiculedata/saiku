/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.olap.ai.ask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalogue of {@link CertifiedQuery}s discovered under a scan root (saiku#1430).
 *
 * <p>Same rescan model as {@link AgentSpaceRegistry}: a cheap mtime signature check on every read,
 * then an atomic swap. A certified catalogue is small — typically a handful of board-level numbers
 * per instance — so the scan cost is a rounding error next to the query it guards.
 *
 * <p>Broken entries don't take down the catalogue: a {@link CertifiedQueryParser.ParseException}
 * discards that one file and leaves a structured entry on {@link #errors()} for the operator. The
 * failure mode is loud-but-contained — one mistyped MDX file must never mean the CFO's certified
 * revenue query silently stops being routed to.
 *
 * <p>Read-only by design, unlike {@link AgentSpaceRegistry}: certified entries are admin-authored
 * files in the server's own home directory, not user content, so there is no write path to guard
 * (and no id-as-filename traversal to defend against).
 */
public final class CertifiedQueryRegistry {

    private static final Logger log = LoggerFactory.getLogger(CertifiedQueryRegistry.class);

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public CertifiedQueryRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public CertifiedQueryRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Ordered by id. */
    public List<CertifiedQuery> list() {
        return refresh().queries;
    }

    /** Look up by id — case-sensitive kebab-case slug. */
    public Optional<CertifiedQuery> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(refresh().byId.get(id));
    }

    /** Structured parse errors from the last scan, keyed by relative file path. */
    public List<CertifiedError> errors() {
        return refresh().errors;
    }

    /** Force a rescan (bypasses the signature check). */
    public void forceRefresh() {
        snapshot.set(scan());
    }

    /**
     * The certified answer for {@code question}, or empty when nothing certifies it.
     *
     * <p>Routing is a pure, deterministic function of the question and the authored
     * {@code matchIntent} phrasings — see {@link CertifiedQuery#score(String)}. Ties break on the
     * specificity of the matching phrasing and then on catalogue order (id ascending), so the
     * outcome is stable across restarts, which is what makes "the same ask always gets the same
     * answer" auditable.
     *
     * <p>When {@code askedCube} is non-blank, only entries certified against that cube are
     * considered: running a query approved for one cube against another answers a different
     * question than the one that was approved.
     */
    public Optional<CertifiedQuery> match(String question, String askedCube) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        CertifiedQuery best = null;
        double bestScore = 0d;
        int bestSpecificity = -1;
        for (CertifiedQuery q : refresh().queries) {
            if (!q.servesCube(askedCube)) {
                continue;
            }
            double s = q.score(question);
            if (s <= 0d) {
                continue;
            }
            int specificity = q.specificity(question);
            if (s > bestScore || (s == bestScore && specificity > bestSpecificity)) {
                bestScore = s;
                bestSpecificity = specificity;
                best = q;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Convenience overload for callers with no cube in play. */
    public Optional<CertifiedQuery> match(String question) {
        return match(question, null);
    }

    private Snapshot refresh() {
        Snapshot current = snapshot.get();
        long sig = signature();
        if (current.signature == sig) {
            return current;
        }
        Snapshot next = scan();
        snapshot.set(next);
        return next;
    }

    private long signature() {
        if (!Files.isDirectory(root)) {
            return -1L;
        }
        long count = 0;
        long maxMtime = 0;
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".json")) continue;
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                count++;
                maxMtime = Math.max(maxMtime, attrs.lastModifiedTime().toMillis());
                totalSize += attrs.size();
            }
        } catch (IOException e) {
            log.debug("certified-query signature scan failed under {}", root, e);
            return -1L;
        }
        long sig = 1125899906842597L;
        sig = sig * 31 + count;
        sig = sig * 31 + maxMtime;
        sig = sig * 31 + totalSize;
        return sig;
    }

    private Snapshot scan() {
        if (!Files.isDirectory(root)) {
            log.debug("certified-query root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<CertifiedQuery> queries = new ArrayList<>();
        List<CertifiedError> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".json")) continue;
                String relPath = root.relativize(p).toString();
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    queries.add(CertifiedQueryParser.parse(relPath, content));
                } catch (CertifiedQueryParser.ParseException e) {
                    log.warn("certified query {} rejected: {} ({})", relPath, e.getMessage(), e.code());
                    errors.add(new CertifiedError(relPath, e.code(), e.getMessage()));
                } catch (IOException e) {
                    log.warn("certified query {} could not be read", relPath, e);
                    errors.add(new CertifiedError(relPath, "IO_ERROR", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException e) {
            log.warn("certified-query scan under {} failed", root, e);
        }
        queries.sort(Comparator.comparing(CertifiedQuery::id));

        // First file (by id-stable scan order) wins a duplicate; the loser is reported rather than
        // silently dropped — two files claiming "monthly-net-revenue" is a governance problem the
        // operator has to see, not a race they can lose at random.
        Map<String, CertifiedQuery> byId = new HashMap<>();
        List<CertifiedQuery> deduped = new ArrayList<>(queries.size());
        for (CertifiedQuery q : queries) {
            CertifiedQuery prior = byId.put(q.id(), q);
            if (prior != null) {
                errors.add(new CertifiedError(
                        q.sourcePath(),
                        "DUPLICATE_ID",
                        "another file already declared certified query id '" + q.id() + "': " + prior.sourcePath()));
            } else {
                deduped.add(q);
            }
        }
        long sig = signature();
        return new Snapshot(
                sig,
                Collections.unmodifiableList(deduped),
                Collections.unmodifiableMap(new LinkedHashMap<>(byId)),
                Collections.unmodifiableList(errors));
    }

    public record CertifiedError(String path, String code, String message) {}

    private record Snapshot(
            long signature,
            List<CertifiedQuery> queries,
            Map<String, CertifiedQuery> byId,
            List<CertifiedError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), List.of());
        }
    }
}
