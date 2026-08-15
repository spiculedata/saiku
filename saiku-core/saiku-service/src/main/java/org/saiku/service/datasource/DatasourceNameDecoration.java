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
