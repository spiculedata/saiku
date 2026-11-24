/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.datasource;

/**
 * A callback fired after a successful repository file save (saiku#2536). Every
 * REST save path funnels through {@link DatasourceService#saveFile}, so a
 * listener registered here observes every user-facing write to a repository
 * object — including its writer's username and the exact content written.
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
     */
    void onFileSaved(String path, String content, String writer);
}
