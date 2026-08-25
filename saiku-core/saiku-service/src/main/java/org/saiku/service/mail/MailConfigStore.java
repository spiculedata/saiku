/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.saiku.datasources.connection.encrypt.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writable, file-backed SMTP settings for the admin mail-setup wizard (saiku#943 Phase 0, P0-A).
 *
 * <p>Persists {@code ${saiku.home}/mail-config.json} as a typed {@link MailConfigFile}. The SMTP
 * password is <b>encrypted at rest</b> with the in-tree per-install AES-256-GCM key ({@link
 * CryptoUtil#encrypt}) and is <b>write-only</b>: it is never returned to any reader.
 *
 * <ul>
 *   <li>{@link #save} takes a plaintext password, encrypts it, and writes the file. An empty/blank
 *       incoming password means "keep the existing stored password" — it is NOT cleared (so the
 *       wizard can re-save other fields without re-entering the secret). Passing an explicit sentinel
 *       is out of scope for P0-A; a future "clear password" affordance can add one.
 *   <li>{@link #readView} returns a {@link MailConfigView} with the password OMITTED (only a {@code
 *       passwordSet} flag) — the read path for the wizard GET / health.
 *   <li>{@link #toMailConfig} decrypts the password and returns a usable {@link MailConfig} for the
 *       sender. This is the ONLY method that ever materialises the plaintext, and it hands it
 *       straight to the transport layer — it is never serialised, logged, or returned to a client.
 * </ul>
 *
 * <p>This class does NOT decide precedence against env/system-property config — that belongs to the
 * resolve/merge step wired in a later slice (env still wins). P0-A is purely the writable layer.
 *
 * <p>All IO is best-effort and never throws a checked exception up to callers: a missing file reads
 * as "unconfigured" ({@link Optional#empty()}); an unreadable/corrupt file logs and reads as empty
 * rather than wedging the app.
 */
public class MailConfigStore {

    private static final Logger log = LoggerFactory.getLogger(MailConfigStore.class);
    private static final String FILE_NAME = "mail-config.json";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    /** @param homeDir the resolved {@code ${saiku.home}} directory; the config file lives directly under it. */
    public MailConfigStore(Path homeDir) {
        this.file = homeDir.resolve(FILE_NAME);
    }

    /** True when a mail-config file exists on disk (regardless of whether it is fully configured). */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /** The raw stored record, or empty when no file exists / it can't be parsed. Password stays encrypted. */
    public Optional<MailConfigFile> read() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), MailConfigFile.class));
        } catch (IOException e) {
            // Never leak file contents (may hold the ciphertext); log only the failure.
            log.warn("Could not read {} — treating mail config as unset ({})", FILE_NAME, e.getMessage());
            return Optional.empty();
        }
    }

    /** Read-safe projection for callers that display settings: every field EXCEPT the password. */
    public Optional<MailConfigView> readView() {
        return read().map(f -> new MailConfigView(
                f.getHost(),
                f.getPort(),
                f.getUsername(),
                notBlank(f.getPassword()),
                f.getFrom(),
                f.isStartTls(),
                f.isSsl(),
                f.getSelfTo(),
                false));
    }

    /**
     * Persist the settings. {@code plaintextPassword} is encrypted at rest; a blank/null value keeps
     * any existing stored (encrypted) password rather than clearing it.
     *
     * @return a redacted view of what was written (never carries the password).
     */
    public MailConfigView save(
            String host,
            int port,
            String username,
            String plaintextPassword,
            String from,
            boolean startTls,
            boolean ssl,
            String selfTo) {
        MailConfigFile existing = read().orElse(null);

        MailConfigFile f = new MailConfigFile();
        f.setHost(trimToNull(host));
        f.setPort(port);
        f.setUsername(trimToNull(username));
        f.setFrom(trimToNull(from));
        f.setStartTls(startTls);
        f.setSsl(ssl);
        f.setSelfTo(trimToNull(selfTo));

        if (notBlank(plaintextPassword)) {
            // Encrypt-at-rest: only ciphertext (v2:-prefixed) ever touches disk.
            f.setPassword(CryptoUtil.encrypt(plaintextPassword));
        } else if (existing != null && notBlank(existing.getPassword())) {
            // Blank incoming password → keep the existing encrypted secret unchanged.
            f.setPassword(existing.getPassword());
        } else {
            f.setPassword(null);
        }

        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), f);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + FILE_NAME, e);
        }

        return new MailConfigView(
                f.getHost(),
                f.getPort(),
                f.getUsername(),
                notBlank(f.getPassword()),
                f.getFrom(),
                f.isStartTls(),
                f.isSsl(),
                f.getSelfTo(),
                false);
    }

    /**
     * Materialise a usable {@link MailConfig} from the file, decrypting the password. Empty when no
     * file exists. This is the ONLY path that produces the plaintext password — it is handed to the
     * transport and never serialised or returned to a client.
     */
    public Optional<MailConfig> toMailConfig() {
        return read().map(f -> new MailConfig(
                f.getHost(),
                f.getPort(),
                f.getUsername(),
                decryptOrNull(f.getPassword()),
                f.getFrom(),
                f.isStartTls(),
                f.isSsl(),
                f.getSelfTo()));
    }

    private static String decryptOrNull(String stored) {
        if (!notBlank(stored)) {
            return stored;
        }
        try {
            return CryptoUtil.decrypt(stored);
        } catch (RuntimeException e) {
            // Corrupt/undecryptable ciphertext must not surface as plaintext or crash the sender;
            // treat as no password so SMTP auth fails cleanly rather than leaking anything.
            log.warn("Stored SMTP password could not be decrypted — treating as unset.");
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
