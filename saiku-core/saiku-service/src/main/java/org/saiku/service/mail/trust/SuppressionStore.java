/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.mail.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writable, file-backed recipient <b>suppression</b> list for the recipient-trust model (saiku#1811,
 * PR2). Mirrors {@link RecipientTrustStore} exactly (atomic write, in-memory fallback,
 * normalisation) but is the opposite polarity: where the allowlist says who <i>may</i> be mailed, the
 * suppression list says who must <b>never</b> be mailed.
 *
 * <p>Persists {@code ${saiku.home}/mail-suppression.json} as a typed {@link SuppressionFile}: a list
 * of {@link SuppressionEntry} (normalised address + coarse reason + timestamp).
 *
 * <p><b>Fail-SAFE — this store can only REDUCE who may be mailed.</b> Adding to the suppression list
 * only ever shrinks the mailable set; it can never enable a send. As of PR2 no send path consults it
 * — it is the future top-veto for a {@code RecipientGate} (saiku#1811 PR3). The anti-relay boundary
 * (recipient is only ever the server's own {@code selfTo}) is unchanged.
 *
 * <p><b>Idempotent.</b> {@link #suppress(String, String)} on an already-suppressed address is a no-op
 * (the earliest entry — its reason + timestamp — is preserved); the list never grows a duplicate.
 *
 * <p><b>Normalisation.</b> Every stored / queried address is lowercased, CRLF-stripped and
 * RFC-validated ({@link InternetAddress#validate()}); a malformed address is dropped on suppress and
 * treated as not-suppressed on query.
 *
 * <p>When {@code saiku.home} is unset the constructor path is null and the store falls back to an
 * in-memory list (never touches disk) so unit/embedded contexts still work.
 *
 * <p>All IO is best-effort: a missing file reads as an empty list; an unreadable/corrupt file logs
 * (count/message only, never an address) and reads as empty. Writes are atomic (temp file + move).
 * No individual address is ever logged.
 */
public class SuppressionStore {

    private static final Logger log = LoggerFactory.getLogger(SuppressionStore.class);
    private static final String FILE_NAME = "mail-suppression.json";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Null when {@code saiku.home} is unset — then the store is in-memory only. */
    private final Path file;

    /** In-memory fallback used when {@link #file} is null (no {@code saiku.home}). */
    private volatile SuppressionFile inMemory = new SuppressionFile();

    /**
     * @param homeDir the resolved {@code ${saiku.home}} directory, or {@code null}/blank to run
     *     in-memory only. The suppression file lives directly under it.
     */
    public SuppressionStore(Path homeDir) {
        this.file = homeDir == null ? null : homeDir.resolve(FILE_NAME);
    }

    /** True when a persisted suppression file exists on disk (always false for the in-memory fallback). */
    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    /** The raw stored record, or empty when nothing is stored / it can't be parsed. */
    public Optional<SuppressionFile> read() {
        if (file == null) {
            return Optional.of(inMemory);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), SuppressionFile.class));
        } catch (IOException e) {
            log.warn("Could not read {} — treating suppression list as empty ({})", FILE_NAME, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Suppress {@code address} with the given coarse {@code reason} (e.g. {@code "unsubscribe"}).
     * Idempotent: if the normalised address is already suppressed this is a no-op and the existing
     * entry is preserved. A null/blank/malformed address is dropped silently (no throw). Never logs
     * the address.
     *
     * @return {@code true} if a new suppression was added; {@code false} if the address was already
     *     suppressed or was invalid.
     */
    public synchronized boolean suppress(String address, String reason) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return false;
        }
        SuppressionFile f = read().orElseGet(SuppressionFile::new);
        List<SuppressionEntry> entries = f.getEntries() == null ? new ArrayList<>() : new ArrayList<>(f.getEntries());
        for (SuppressionEntry e : entries) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                return false; // already suppressed — idempotent no-op
            }
        }
        entries.add(new SuppressionEntry(normalised, safeReason(reason), System.currentTimeMillis()));
        f.setEntries(entries);
        persist(f);
        return true;
    }

    /**
     * True when {@code address} is on the suppression list (normalised match). Pure string work; no
     * network / DNS lookup. A null / blank / malformed / unlisted address returns {@code false}. This
     * is the method a future {@code RecipientGate} consults FIRST (top-veto).
     */
    public boolean isSuppressed(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return false;
        }
        SuppressionFile f = read().orElse(null);
        if (f == null || f.getEntries() == null) {
            return false;
        }
        for (SuppressionEntry e : f.getEntries()) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redacted admin view of the suppression list: masked addresses (local part reduced to its first
     * character), coarse reason, timestamp, plus the total count. Never returns raw addresses.
     */
    public SuppressionView list() {
        SuppressionFile f = read().orElseGet(SuppressionFile::new);
        List<SuppressionView.Entry> out = new ArrayList<>();
        List<SuppressionEntry> entries = f.getEntries() == null ? List.of() : f.getEntries();
        for (SuppressionEntry e : entries) {
            out.add(new SuppressionView.Entry(mask(e.getAddress()), e.getReason(), e.getTimestamp()));
        }
        return new SuppressionView(List.copyOf(out), out.size());
    }

    // ---- persistence ----

    private void persist(SuppressionFile f) {
        if (file == null) {
            this.inMemory = f;
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            // Atomic write: serialise to a temp sibling, then atomically move over the target.
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), f);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // Some filesystems don't support ATOMIC_MOVE; fall back to a plain replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + FILE_NAME, e);
        }
    }

    // ---- normalisation helpers (pure; no network) ----

    /** Lowercase + CRLF-strip + RFC-validate an address; null when blank/invalid. */
    private static String normaliseAddress(String address) {
        String s = stripCrlf(address);
        if (s == null) {
            return null;
        }
        s = s.toLowerCase();
        try {
            InternetAddress ia = new InternetAddress(s, true);
            ia.validate();
            String bare = ia.getAddress();
            return bare == null ? null : bare.toLowerCase();
        } catch (AddressException e) {
            return null;
        }
    }

    /** A coarse, whitespace-safe reason tag; defaults to {@code "unknown"} when blank. */
    private static String safeReason(String reason) {
        String s = stripCrlf(reason);
        return s == null ? "unknown" : s.toLowerCase();
    }

    /** Mask an address for the admin view: keep the first local-part char + host. */
    private static String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = address.substring(0, at);
        String host = address.substring(at);
        String head = local.substring(0, 1);
        return head + "***" + host;
    }

    /** Strip CR/LF so a stored value can't smuggle header/log injection; null when blank. */
    private static String stripCrlf(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.isEmpty() ? null : t;
    }
}
