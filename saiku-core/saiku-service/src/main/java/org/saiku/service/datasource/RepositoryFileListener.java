/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

/**
 * Callbacks fired after successful repository file mutations (saiku#2536).
 * A listener registered here observes user-facing saves, removes and moves.
 *
 * <p>The share/embed content-binding hook uses this to re-bind a minted
 * content hash when the minter themself saves (the link keeps serving their
 * edits) and to flag a record as changed-by-other when anyone else does (the
 * link is then refused — never executed under the minter's roles).
 *
 * <p>Listener failures MUST never fail the save: {@code DatasourceService}
 * wraps each callback and only logs.
 */
public interface RepositoryFileListener {

    /**
     * Called after {@code path} was saved with {@code content} by authenticated
     * user {@code writer}.
     *
     * @param path repository path as handed to {@code saveFile}
     * @param content the exact string persisted (may be null for folder creates)
     * @param writer the authenticated username the save ran as
     * @param datasourceService repository access for resolving files referenced by the saved content
     */
    void onFileSaved(String path, String content, String writer, DatasourceService datasourceService);

    /** Called after a successful remove or move changes a path without saving its bytes. */
    default void onFileChangedWithoutSave(String path, String writer, DatasourceService datasourceService) {}
}
