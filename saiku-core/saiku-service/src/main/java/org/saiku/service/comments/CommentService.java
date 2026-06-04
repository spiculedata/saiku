/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.comments;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.saiku.service.datasource.DatasourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tile dashboard comments (issue #942), persisted as one JSONL file per
 * dashboard under an internal {@code comments/} area.
 *
 * <p>Mechanics only — read / append / soft-delete + @-mention parsing. The
 * REST layer ({@code CommentResource}) owns identity + authorisation: it calls
 * {@link #canReadDashboard} to gate every operation on the caller's ability to
 * READ the dashboard (reusing the #940 ACL), and checks author/admin before
 * {@link #softDelete}. Storage uses the internal-file API (no ACL of its own),
 * so access is governed solely by that dashboard check — not by where the
 * comments file happens to sit.
 *
 * <p>Writes are full-file read-modify-write (no append primitive exists); this
 * is single-node-safe but not concurrency-guarded — see the PR note.
 */
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9._-]+)");

    private DatasourceService datasourceService;

    public void setDatasourceService(DatasourceService s) {
        this.datasourceService = s;
    }

    /** True if {@code user} can READ {@code dashboardPath} — the gate for every
     *  comment operation. Uses a successful dashboard read as the canRead proxy
     *  (getResourceACL can't be used: it returns null unless canGrant). */
    public boolean canReadDashboard(String dashboardPath, String user, List<String> roles) {
        if (dashboardPath == null || !dashboardPath.endsWith(".saikudash")) {
            return false;
        }
        try {
            String d = datasourceService.getFileData(dashboardPath, user, roles);
            return d != null && !d.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Live (non-deleted) comments for one tile, in insertion order. */
    public List<Comment> list(String dashboardPath, String tileId) {
        List<Comment> out = new ArrayList<>();
        for (Comment c : readAll(dashboardPath)) {
            if (!c.deleted && tileId != null && tileId.equals(c.tileId)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Append a comment authored by {@code author}; @-mentions parsed from body. */
    public Comment add(String dashboardPath, String tileId, String author, String body) {
        Comment c = new Comment();
        c.id = UUID.randomUUID().toString();
        c.tileId = tileId;
        c.author = author;
        c.body = body;
        c.mentions = parseMentions(body);
        c.createdAt = System.currentTimeMillis();
        c.deleted = false;

        List<Comment> all = readAll(dashboardPath);
        all.add(c);
        writeAll(dashboardPath, all);
        return c;
    }

    /** Find a comment by id (incl. soft-deleted), or null. */
    public Comment findById(String dashboardPath, String commentId) {
        for (Comment c : readAll(dashboardPath)) {
            if (c.id != null && c.id.equals(commentId)) {
                return c;
            }
        }
        return null;
    }

    /** Soft-delete a comment. Returns false if not found. */
    public boolean softDelete(String dashboardPath, String commentId) {
        List<Comment> all = readAll(dashboardPath);
        boolean found = false;
        for (Comment c : all) {
            if (c.id != null && c.id.equals(commentId) && !c.deleted) {
                c.deleted = true;
                found = true;
            }
        }
        if (found) {
            writeAll(dashboardPath, all);
        }
        return found;
    }

    /* --------------------------- internals --------------------------- */

    static List<String> parseMentions(String body) {
        Set<String> out = new LinkedHashSet<>();
        if (body != null) {
            Matcher m = MENTION.matcher(body);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return new ArrayList<>(out);
    }

    /** Internal storage path for a dashboard's comments — a flat, sanitised
     *  name under {@code comments/}. The repository layer additionally guards
     *  this with resolveWithinDatadir, so a crafted dashboard path can't escape. */
    static String commentsPath(String dashboardPath) {
        String flat = dashboardPath == null ? "null" : dashboardPath.replaceAll("[^A-Za-z0-9._-]", "_");
        // Separators are already gone (so no traversal); also collapse any ".."
        // run as belt-and-braces. resolveWithinDatadir guards the final path too.
        flat = flat.replace("..", "_");
        return "comments/" + flat + ".jsonl";
    }

    private List<Comment> readAll(String dashboardPath) {
        List<Comment> out = new ArrayList<>();
        String raw = datasourceService.getInternalFileData(commentsPath(dashboardPath));
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                out.add(MAPPER.readValue(line, Comment.class));
            } catch (Exception e) {
                log.warn("Skipping unparseable comment line in {}", commentsPath(dashboardPath), e);
            }
        }
        return out;
    }

    private void writeAll(String dashboardPath, List<Comment> comments) {
        StringBuilder sb = new StringBuilder();
        for (Comment c : comments) {
            try {
                sb.append(MAPPER.writeValueAsString(c)).append('\n');
            } catch (Exception e) {
                log.error("Failed to serialise comment {}", c.id, e);
            }
        }
        datasourceService.saveInternalFile(commentsPath(dashboardPath), sb.toString(), null);
    }
}
