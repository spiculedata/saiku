/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mcp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.saiku.datasources.connection.encrypt.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catalogue of admin-registered {@link McpOutboundServer}s discovered under a scan root
 * (saiku#1425).
 *
 * <p>Same rescan-on-signature model as {@code AgentSkillRegistry} / {@code AgentSpaceRegistry}:
 * mtime signature check on every read, atomic snapshot swap, broken files quarantined into {@link
 * #errors()} rather than taking down the whole catalogue.
 *
 * <p>{@link #save} encrypts {@code authHeaderValue} with {@link CryptoUtil} before it ever touches
 * disk — the same at-rest protection datasource passwords get. Plaintext only exists in memory,
 * inside the parsed {@link McpOutboundServer} record and the {@link McpOutboundClient} request it
 * feeds.
 */
public final class McpOutboundServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpOutboundServerRegistry.class);

    private final Path root;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public McpOutboundServerRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** String-arg convenience so Spring XML wiring stays terse. */
    public McpOutboundServerRegistry(String root) {
        this(Path.of(Objects.requireNonNull(root, "root")));
    }

    /** Servers discovered on the last scan, ordered by id. */
    public List<McpOutboundServer> list() {
        return refresh().servers;
    }

    /** Look up a server by id. */
    public Optional<McpOutboundServer> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(refresh().byId.get(id));
    }

    /** Structured parse errors from the last scan, keyed by relative file path. */
    public List<ServerError> errors() {
        return refresh().errors;
    }

    /** Force a rescan (bypasses the signature check). */
    public void forceRefresh() {
        snapshot.set(scan());
    }

    /* ----------------------------- write (admin CRUD) ----------------------------- */

    private static final ObjectMapper WRITE_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    // Server ids double as filenames — restrict to kebab-case so a hostile id can't traverse out of
    // the mcp-servers directory or collide with a dotfile.
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** True when {@code id} is a safe filename stem (kebab-case). */
    public static boolean isValidId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /**
     * Persist {@code server} as {@code {root}/{id}.json} and rescan so the change is live
     * immediately. {@code authHeaderValue} (if present) is encrypted before it is written —
     * {@code server} itself must carry the PLAINTEXT credential (as returned by the admin API), not
     * an already-encrypted value.
     */
    public void save(McpOutboundServer server) throws IOException {
        Objects.requireNonNull(server, "server");
        if (!isValidId(server.id())) {
            throw new IllegalArgumentException("server id must match [a-z0-9-] (got: " + server.id() + ")");
        }
        Files.createDirectories(root);
        Path file = root.resolve(server.id() + ".json").normalize();
        if (!file.startsWith(root.normalize())) {
            throw new IllegalArgumentException("server id escapes the mcp-servers directory");
        }
        Files.writeString(file, WRITE_MAPPER.writeValueAsString(toJson(server)), StandardCharsets.UTF_8);
        forceRefresh();
    }

    /** Delete {@code {root}/{id}.json} and rescan. Returns true if a file was removed. */
    public boolean delete(String id) throws IOException {
        if (!isValidId(id)) {
            return false;
        }
        Path file = root.resolve(id + ".json").normalize();
        if (!file.startsWith(root.normalize())) {
            return false;
        }
        boolean removed = Files.deleteIfExists(file);
        if (removed) {
            forceRefresh();
        }
        return removed;
    }

    /** Serialise a server to the on-disk JSON shape {@link McpOutboundServerParser} reads. */
    private static ObjectNode toJson(McpOutboundServer server) {
        ObjectNode node = WRITE_MAPPER.createObjectNode();
        node.put("id", server.id());
        node.put("name", server.name());
        node.put("url", server.url());
        if (server.hasAuthHeader()) {
            node.put("authHeaderName", server.authHeaderName());
            node.put("authHeaderValue", CryptoUtil.encrypt(server.authHeaderValue()));
        }
        ArrayNode enabled = node.putArray("enabledTools");
        server.enabledTools().forEach(enabled::add);
        return node;
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
            log.debug("mcp-server signature scan failed under {}", root, e);
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
            log.debug("mcp-servers root {} does not exist — empty catalogue", root);
            return Snapshot.empty();
        }
        List<McpOutboundServer> servers = new ArrayList<>();
        List<ServerError> errors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".json")) continue;
                String relPath = root.relativize(p).toString();
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    servers.add(McpOutboundServerParser.parse(relPath, content));
                } catch (McpOutboundServerParser.ParseException e) {
                    log.warn("mcp-server {} rejected: {} ({})", relPath, e.getMessage(), e.code());
                    errors.add(new ServerError(relPath, e.code(), e.getMessage()));
                } catch (IOException e) {
                    log.warn("mcp-server {} could not be read", relPath, e);
                    errors.add(new ServerError(relPath, "IO_ERROR", e.getMessage()));
                }
            }
        } catch (IOException e) {
            log.warn("mcp-server scan under {} failed", root, e);
        }
        servers.sort(Comparator.comparing(McpOutboundServer::id));

        Map<String, McpOutboundServer> byId = new HashMap<>();
        List<McpOutboundServer> deduped = new ArrayList<>(servers.size());
        for (McpOutboundServer s : servers) {
            McpOutboundServer prior = byId.put(s.id(), s);
            if (prior != null) {
                errors.add(new ServerError(
                        s.sourcePath(),
                        "DUPLICATE_ID",
                        "another file already declared server id '" + s.id() + "': " + prior.sourcePath()));
            } else {
                deduped.add(s);
            }
        }
        long sig = signature();
        return new Snapshot(
                sig,
                Collections.unmodifiableList(deduped),
                Collections.unmodifiableMap(new LinkedHashMap<>(byId)),
                Collections.unmodifiableList(errors));
    }

    public record ServerError(String path, String code, String message) {}

    private record Snapshot(
            long signature,
            List<McpOutboundServer> servers,
            Map<String, McpOutboundServer> byId,
            List<ServerError> errors) {
        static Snapshot empty() {
            return new Snapshot(0L, List.of(), Map.of(), List.of());
        }
    }
}
