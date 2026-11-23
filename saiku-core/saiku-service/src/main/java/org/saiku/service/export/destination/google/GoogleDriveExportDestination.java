/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDeliveryResult;
import org.saiku.service.export.destination.ExportDestination;
import org.saiku.service.export.destination.ExportDestinationConfig;
import org.saiku.service.export.destination.ExportDestinationConfigField;

/**
 * The first-party {@link ExportDestination}: Google Drive (saiku#1987).
 *
 * <p>Exists to <b>prove the contract</b>. Everything connector-specific — the admin config, the
 * service-account flow, the Drive REST call — lives behind {@link DriveUploadClient} and
 * {@link DriveAccessTokenSupplier}, so a second connector (S3, OneDrive) is a new class against the
 * same SPI rather than a second code path through the exporters.
 *
 * <h2>Operator setup</h2>
 *
 * <ol>
 *   <li>In Google Cloud, create a service account, download its JSON key, and grant it Drive access
 *       to the target folder by sharing that folder with the account's {@code client_email}.</li>
 *   <li>Store the key <b>outside</b> {@code saiku-home} and put its path in the destination config's
 *       {@code serviceAccountKeyFile} setting. Saiku reads it at delivery time; it never copies key
 *       material into its own store.</li>
 *   <li>Set {@code folderId} to the target Drive folder's id.</li>
 * </ol>
 *
 * <p>Scope is {@code drive.file} only, and uploads are pinned to {@code folderId} — see
 * {@link DriveAccessTokenSupplier.ServiceAccountTokenSupplier} for the full threat model.
 */
public final class GoogleDriveExportDestination implements ExportDestination {

    public static final String ID = "GOOGLE_DRIVE";

    /** Config keys. Kept as constants because they are the on-disk contract. */
    public static final String SETTING_KEY_FILE = "serviceAccountKeyFile";

    public static final String SETTING_FOLDER_ID = "folderId";
    public static final String SETTING_CLIENT_EMAIL = "clientEmail";

    /**
     * Drive folder ids are 28–33 chars of {@code [A-Za-z0-9_-]}, but we validate loosely on purpose:
     * an over-tight regex would reject a future id shape and turn a working config into a hard error
     * on upgrade. The only real hazards are an empty value and a value carrying a quote/bracket that
     * would break the metadata JSON.
     */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{4,128}$");

    /**
     * Builds the token supplier for a freshly-loaded key. A seam so a test can hand in a canned
     * supplier and never touch the Google token endpoint.
     */
    @FunctionalInterface
    public interface TokenSupplierFactory {
        DriveAccessTokenSupplier create(DriveAccessTokenSupplier.ServiceAccountKey key);
    }

    private final DriveUploadClient uploadClient;
    private final TokenSupplierFactory tokenSupplierFactory;

    /** Production: a real Drive client and a real JWT-bearer token supplier. */
    public GoogleDriveExportDestination() {
        this(new HttpDriveUploadClient(), DriveAccessTokenSupplier.ServiceAccountTokenSupplier::new);
    }

    /** Visible for tests: a fake upload client, still minting tokens for real. */
    public GoogleDriveExportDestination(DriveUploadClient uploadClient) {
        this(uploadClient, DriveAccessTokenSupplier.ServiceAccountTokenSupplier::new);
    }

    /** Visible for tests: a fake upload client AND a canned token supplier — no network at all. */
    public GoogleDriveExportDestination(DriveUploadClient uploadClient, TokenSupplierFactory tokenSupplierFactory) {
        if (uploadClient == null || tokenSupplierFactory == null) {
            throw new IllegalArgumentException("uploadClient and tokenSupplierFactory are required");
        }
        this.uploadClient = uploadClient;
        this.tokenSupplierFactory = tokenSupplierFactory;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Google Drive";
    }

    @Override
    public List<ExportDestinationConfigField> configFields() {
        // Note there is deliberately NO secret field: this connector stores a *path* to the operator's
        // key file, never the key material itself, so there is no credential for Saiku to hold. The
        // OAuth scope is fixed at drive.file (see the class javadoc) — it is a property of the flow,
        // not an operator setting, so it is not offered as a configurable field.
        return List.of(
                ExportDestinationConfigField.required(SETTING_KEY_FILE, "Service account key file"),
                ExportDestinationConfigField.required(SETTING_FOLDER_ID, "Drive folder id"),
                ExportDestinationConfigField.optional(
                        SETTING_CLIENT_EMAIL,
                        "Service account email (for the operator's reference)",
                        "Optional. The email is read from the key file; set it here only to record which account a key"
                                + " belongs to."));
    }

    /**
     * Offline validation only: the key path must be syntactically sane and the folder id must be
     * well-formed. It deliberately does <b>not</b> read the key file or call Google, so an admin gets
     * an instant answer when saving a form and a genuine connectivity error at delivery time instead.
     */
    @Override
    public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
        if (config == null || config.isEmpty()) {
            throw ExportDeliveryException.misconfigured("no configuration saved for " + ID);
        }
        String keyFile = config.require(SETTING_KEY_FILE);
        Path path;
        try {
            path = Path.of(keyFile);
        } catch (InvalidPathException e) {
            throw ExportDeliveryException.misconfigured("'" + SETTING_KEY_FILE + "' is not a valid filesystem path");
        }
        if (!path.isAbsolute()) {
            // A relative path would resolve against whatever the JVM's cwd happens to be — which in
            // a WAR is the container's install dir, not the operator's home. Silent misdelivery is
            // worse than a refusal.
            throw ExportDeliveryException.misconfigured("'" + SETTING_KEY_FILE + "' must be an absolute path");
        }
        String folderId = config.require(SETTING_FOLDER_ID);
        if (!SAFE_ID.matcher(folderId).matches()) {
            throw ExportDeliveryException.misconfigured("'" + SETTING_FOLDER_ID
                    + "' is not a valid Drive folder id (expected letters, digits, '-' or '_')");
        }
    }

    @Override
    public ExportDeliveryResult deliver(ExportArtifact artifact, ExportDestinationConfig config)
            throws ExportDeliveryException {
        validateConfig(config);
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        String folderId = config.require(SETTING_FOLDER_ID);
        Path keyFile = Path.of(config.require(SETTING_KEY_FILE));
        if (!Files.isReadable(keyFile)) {
            // The PATH is operator-facing and safe to surface; the file's contents are not.
            throw ExportDeliveryException.misconfigured("service-account key file is not readable at " + keyFile);
        }
        DriveAccessTokenSupplier.ServiceAccountKey key = DriveAccessTokenSupplier.ServiceAccountKey.fromFile(keyFile);
        String token = tokenSupplierFactory.create(key).accessToken();
        String fileId =
                uploadClient.upload(token, folderId, artifact.filename(), artifact.contentType(), artifact.content());
        return ExportDeliveryResult.of(
                ID,
                fileId,
                "uploaded " + artifact.filename() + " (" + artifact.size() + " bytes) to Google Drive folder "
                        + folderId);
    }
}
