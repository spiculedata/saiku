/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.share;

import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.RepositoryFileListener;
import org.saiku.web.embed.EmbedPublicRegistry;
import org.saiku.web.embed.EmbedTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * saiku#2536 — the repository save hook behind the share/embed content
 * binding. User-facing saves, removes and moves pass through
 * {@code DatasourceService}; this listener observes each one and
 * keeps the minted bindings honest:
 *
 * <ul>
 *   <li><b>Writer is the minter/grantor</b> — re-arm the record's content hash
 *       to the freshly written content. A share link to an object only the
 *       minter can write keeps working while the minter iterates on it.</li>
 *   <li><b>Writer is anyone else</b> (a collaborator holding {@code WRITE},
 *       a version-history restore by another account, an authoring-token save)
 *       — poison the record. The guest read is then refused with
 *       {@code 409 CONTENT_CHANGED} until the link is re-minted, even if the
 *       exact bytes are later restored.</li>
 * </ul>
 *
 * <p>Unbound legacy records (minted before #2536) are also handled at save
 * time: the minter's first save after the upgrade binds them; a non-minter's
 * first save poisons them — so a legacy record can never serve a
 * collaborator's content under the minter's roles.
 *
 * <p>Wired in {@code saiku-beans.xml} onto {@code datasourceServiceBean}'s
 * {@code repositoryFileListeners} list; failures are logged and never fail the
 * save.
 */
public class SharedContentBindingListener implements RepositoryFileListener {

    private static final Logger log = LoggerFactory.getLogger(SharedContentBindingListener.class);

    /** File suffix → embed {@code resourceKind} for the content-bearing kinds. */
    private static String embedKindFor(String path) {
        if (path.endsWith(".saiku")) {
            return "query";
        }
        if (path.endsWith(".saikudash")) {
            return "dashboard";
        }
        if (path.endsWith(".saikuapp")) {
            return "app";
        }
        return null;
    }

    private ShareTokenStore shareTokenStore;
    private EmbedTokenStore embedTokenStore;
    private EmbedPublicRegistry embedPublicRegistry;

    public void setShareTokenStore(ShareTokenStore s) {
        this.shareTokenStore = s;
    }

    public void setEmbedTokenStore(EmbedTokenStore s) {
        this.embedTokenStore = s;
    }

    public void setEmbedPublicRegistry(EmbedPublicRegistry r) {
        this.embedPublicRegistry = r;
    }

    @Override
    public void onFileSaved(String path, String content, String writer, DatasourceService datasourceService) {
        if (path == null || content == null) {
            // Folder creates carry no content — nothing bound to re-arm.
            return;
        }
        String sha = ContentBinding.sha256Hex(content);
        if (shareTokenStore != null) {
            shareTokenStore.onContentSaved(
                    path,
                    sha,
                    writer,
                    content,
                    (reference, token) -> datasourceService == null
                            ? null
                            : datasourceService.getFileData(reference, token.createdBy, token.ownerRolesSnapshot));
        }
        if (embedTokenStore != null) {
            embedTokenStore.onContentSaved(
                    path,
                    sha,
                    writer,
                    content,
                    (reference, token) -> datasourceService == null
                            ? null
                            : datasourceService.getFileData(reference, token.createdBy, token.ownerRolesSnapshot));
        }
        String kind = embedKindFor(path);
        if (kind != null && embedPublicRegistry != null) {
            embedPublicRegistry.onContentSaved(
                    kind,
                    path,
                    sha,
                    writer,
                    content,
                    (reference, grant) -> datasourceService == null
                            ? null
                            : datasourceService.getFileData(reference, grant.grantedBy, grant.ownerRolesSnapshot));
        }
        log.debug("Share/embed content binding observed save of {} by {} (sha={})", path, writer, sha);
    }

    /** Convenient direct hook for isolated tests. Production saves pass the datasource service. */
    public void onFileSaved(String path, String content, String writer) {
        onFileSaved(path, content, writer, null);
    }

    @Override
    public void onFileChangedWithoutSave(String path, String writer, DatasourceService datasourceService) {
        if (path == null) {
            return;
        }
        if (shareTokenStore != null) {
            shareTokenStore.onContentChangedWithoutSave(path, writer);
        }
        if (embedTokenStore != null) {
            embedTokenStore.onContentChangedWithoutSave(path, writer);
        }
        if (embedPublicRegistry != null) {
            embedPublicRegistry.onContentChangedWithoutSave(path, writer);
        }
    }
}
