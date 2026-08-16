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

/**
 * The workspace decoration Saiku puts on a datasource name when it reads it back off disk, and how
 * to undo it before writing.
 *
 * <p>saiku#1864: {@code FilesystemRepositoryManager.getAllDataSources} renames every datasource it
 * loads to {@code <workspace>_<storedName>} — the workspace being the directory the {@code .sds}
 * file sits under. Nothing undid that on the way back down, so the read and write halves of the
 * admin datasource API disagreed: a client that fetched a datasource, changed one field and PUT it
 * back sent {@code unknown_foo}, which was saved <em>literally</em> as {@code unknown_foo.sds} and
 * then loaded as {@code unknown_unknown_foo}. The result was not a rename but a DUPLICATE — the
 * original {@code foo.sds} stayed put, so the same datasource id appeared twice with different
 * names and different schemas, and queries against the original name silently kept serving the old
 * catalog.
 *
 * <p>Every read-modify-write of a datasource hit this: the Cube Designer's save-and-attach, and the
 * Admin › Datasources edit form. It went unnoticed largely because that edit modal was itself broken
 * (saiku#1856) and could not be opened.
 *
 * <p>Stripping on save makes the round trip stable, and it also makes the displayed name honest for
 * the odd case where someone genuinely names a datasource {@code unknown_bar}: it is stored as
 * {@code bar.sds}, redecorated to {@code unknown_bar} on load, and comes back as they typed it.
 */
public final class DatasourceNameDecoration {

    private DatasourceNameDecoration() {}

    /**
     * True when two connection names refer to the same datasource, tolerating the workspace
     * decoration on either side.
     *
     * <p>saiku#1871: dropping the {@code unknown_} prefix is only safe if content that stored the
     * OLD spelling still resolves. The lookup alias in saiku#1869 covers name→datasource lookups,
     * but not the places that match a stored connection name against a DISCOVERED one — notably
     * {@code OlapAiCubeMetadataService.matchesRef}, which every AI-query cube reference goes
     * through, and therefore every app tile and embed built on one. Without this, a saved app
     * referencing {@code unknown_foodmart} 400s the moment the prefix stops being emitted.
     *
     * <p>The rule is deliberately narrow: equal, or equal once a single {@code <something>_}
     * prefix is removed from one side. It cannot match {@code foo} against {@code bar}, and the
     * cube, catalog and schema still have to match exactly, so a false positive needs a real
     * datasource whose name is another's with a prefix glued on. With workspaces off — the only
     * mode where the decoration was ever applied — there is a single workspace, so that collision
     * is not reachable.
     */
    public static boolean sameConnection(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        return isPrefixedForm(a, b) || isPrefixedForm(b, a);
    }

    /**
     * True when {@code decorated} is {@code bare} carrying exactly one {@code <segment>_} prefix.
     *
     * <p>The prefix itself must contain no underscore, so exactly one workspace segment is
     * tolerated: {@code unknown_foodmart} matches {@code foodmart}, but the doubly-decorated
     * {@code unknown_unknown_foodmart} does not. Note the constraint is on the PREFIX only — a
     * datasource legitimately named {@code sales_2024} still matches {@code unknown_sales_2024}.
     */
    private static boolean isPrefixedForm(String decorated, String bare) {
        if (bare.isEmpty() || decorated.length() <= bare.length() + 1) {
            return false;
        }
        int split = decorated.length() - bare.length() - 1;
        if (decorated.charAt(split) != '_') {
            return false;
        }
        if (decorated.lastIndexOf('_', split - 1) >= 0) {
            return false; // more than one prefix segment — not a workspace decoration
        }
        return decorated.regionMatches(true, split + 1, bare, 0, bare.length());
    }

    /**
     * Strip the load-time {@code <workspace>_} prefix from a datasource name, so what gets written
     * matches what was originally stored.
     *
     * <p>Idempotent-safe by construction: only ONE prefix is removed, and only when the remainder is
     * non-empty — {@code "unknown_"} on its own is a real (if odd) name, not a decoration with
     * nothing behind it.
     *
     * @param name the datasource name as the API surfaced it, possibly decorated
     * @param workspace the workspace the datasource belongs to (never null in practice; a blank one
     *     means there is no decoration to remove)
     * @return the name to store on disk
     */
    /**
     * Add the workspace decoration, i.e. the name a stored datasource is surfaced under after a
     * load. The exact inverse of {@link #undecorate}.
     */
    public static String decorate(String storedName, String workspace) {
        if (storedName == null || workspace == null || workspace.isEmpty()) {
            return storedName;
        }
        String prefix = workspace + "_";
        return storedName.startsWith(prefix) ? storedName : prefix + storedName;
    }

    public static String undecorate(String name, String workspace) {
        if (name == null || workspace == null || workspace.isEmpty()) {
            return name;
        }
        String prefix = workspace + "_";
        if (!name.startsWith(prefix)) {
            return name;
        }
        String stripped = name.substring(prefix.length());
        return stripped.isEmpty() ? name : stripped;
    }
}
