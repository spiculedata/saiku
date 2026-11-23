/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import java.util.Map;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * The Drive REST call the connector needs: "put these bytes, with this name, into this folder"
 * (saiku#1987). A two-method seam so {@link GoogleDriveExportDestination} holds no HTTP code and its
 * own behaviour (config validation, file-name handling, error sanitisation) is unit-testable with a
 * fake client and no network.
 */
public interface DriveUploadClient {

    /**
     * Upload {@code content} as {@code filename} ({@code contentType}) into {@code folderId}.
     *
     * @return the new Drive file id
     * @throws ExportDeliveryException with a sanitized message on any failure
     */
    String upload(String accessToken, String folderId, String filename, String contentType, byte[] content)
            throws ExportDeliveryException;

    /** Readable JSON over an existing file — used by the admin "test delivery" / verification path. */
    Map<String, String> fileMetadata(String accessToken, String fileId) throws ExportDeliveryException;
}
