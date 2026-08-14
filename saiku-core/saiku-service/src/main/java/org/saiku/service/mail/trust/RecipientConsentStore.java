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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writable, file-backed <b>double-opt-in consent</b> store for the recipient-trust model (saiku#1811,
 * PR3). Mirrors the {@link RecipientTrustStore} / {@link SuppressionStore} file-backed pattern (atomic
 * write, in-memory fallback, normalisation) and adds the consent lifecycle: an admin
 * {@link #requestConsent(String) requests consent} (mints a token, records {@link ConsentStatus#PENDING
 * PENDING}), and only the recipient — holding the raw token delivered to their mailbox in a LATER PR —
 * can {@link #confirm(String, String) confirm} it to {@link ConsentStatus#CONFIRMED CONFIRMED}.
 *
 * <p>Persists {@code ${saiku.home}/mail-consent.json} as a typed {@link ConsentFile}.
 *
 * <p><b>DORMANT infrastructure — this PR cannot mail anyone.</b> No send path exists and none consults
 * this store; there is no consent-invite email in this PR (that is #1811 PR4). {@link
 * #requestConsent(String)} only records intent + mints a token; it does not send. The anti-relay
 * boundary (recipient is only ever the server's own {@code selfTo}) is untouched.
 *
 * <p><b>Nothing sensitive is reversible on disk.</b> The raw token is high-entropy {@link SecureRandom}
 * and is returned ONCE from {@link #requestConsent(String)} (for the future invite mail) — never
 * persisted. Only a per-entry <b>salted SHA-256 hash</b> of the token is stored. {@link #confirm}
 * re-hashes the supplied token under the stored salt and constant-time compares ({@link
 * MessageDigest#isEqual}) — no decryption, no enumeration oracle.
 *
 * <p><b>An admin cannot self-confirm a third party.</b> Confirmation requires the raw token, which is
 * returned only to the caller of {@link #requestConsent(String)} for delivery to the recipient's own
 * mailbox (a later PR). An admin holding only the on-disk hash cannot reverse it into a valid token.
 *
 * <p><b>Fail-closed.</b> {@link #isConfirmed(String)} returns {@code true} ONLY for a stored,
 * {@link ConsentStatus#CONFIRMED} entry; absent / PENDING / REVOKED / corrupt all read as not confirmed.
 *
 * <p><b>Normalisation.</b> Every stored / queried address is lowercased, CRLF-stripped and RFC-validated
 * ({@link InternetAddress#validate()}); a malformed address is dropped on request and treated as
 * not-confirmed on query.
 *
 * <p>When {@code saiku.home} is unset the constructor path is null and the store falls back to an
 * in-memory record (never touches disk). All IO is best-effort: a missing/corrupt file reads as empty
 * (logged by count/message only, never an address); writes are atomic (temp file + move). No individual
 * address and no token is ever logged.
 */
public class RecipientConsentStore {

    private static final Logger log = LoggerFactory.getLogger(RecipientConsentStore.class);
    private static final String FILE_NAME = "mail-consent.json";

    /** Default token time-to-live: 72h from request. Overridable via system property for tests/ops. */
    private static final long DEFAULT_TTL_MILLIS =
            Long.getLong("saiku.mail.consent.ttlMillis", 72L * 60L * 60L * 1000L);

    /** 32 bytes = 256 bits of token entropy — comfortably unguessable. */
    private static final int TOKEN_BYTES = 32;

    private static final int SALT_BYTES = 16;
    private static final String HASH_ALGO = "SHA-256";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RNG = new SecureRandom();

    /** Null when {@code saiku.home} is unset — then the store is in-memory only. */
    private final Path file;

    private final long ttlMillis;

    /** In-memory fallback used when {@link #file} is null (no {@code saiku.home}). */
    private volatile ConsentFile inMemory = new ConsentFile();

    /**
     * @param homeDir the resolved {@code ${saiku.home}} directory, or {@code null}/blank to run
     *     in-memory only. The consent file lives directly under it.
     */
    public RecipientConsentStore(Path homeDir) {
        this(homeDir, DEFAULT_TTL_MILLIS);
    }

    /** Explicit-TTL ctor (tests exercise expiry with a short/zero TTL). */
    public RecipientConsentStore(Path homeDir, long ttlMillis) {
        this.file = homeDir == null ? null : homeDir.resolve(FILE_NAME);
        this.ttlMillis = ttlMillis;
    }

    /** True when a persisted consent file exists on disk (always false for the in-memory fallback). */
    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    /** The raw stored record, or empty when nothing is stored / it can't be parsed. */
    public Optional<ConsentFile> read() {
        if (file == null) {
            return Optional.of(inMemory);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), ConsentFile.class));
        } catch (IOException e) {
            log.warn("Could not read {} — treating consent store as empty ({})", FILE_NAME, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Request consent for {@code address}: mint a high-entropy raw token, record the recipient as
     * {@link ConsentStatus#PENDING} with a salted hash of the token + a fresh expiry, and RETURN the raw
     * token so the caller can (in a later PR) mail the recipient their confirm link. The raw token is
     * NEVER persisted; only its salted hash is.
     *
     * <p>Idempotent upsert: requesting again for the same address re-mints a fresh token and expiry and
     * resets the entry to PENDING (a superseding invite). A null / blank / malformed address returns
     * {@code null} and stores nothing. Never logs the address or the token.
     *
     * <p><b>Dormant:</b> this does NOT send mail. See {@code // #1811 PR4} markers for where the invite
     * send will be wired.
     *
     * @return the raw consent token (URL-safe base64, no padding), or {@code null} for an invalid
     *     address.
     */
    public synchronized String requestConsent(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return null;
        }
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hash = hash(salt, rawToken);

        long now = System.currentTimeMillis();
        ConsentFile f = read().orElseGet(ConsentFile::new);
        List<ConsentEntry> entries = f.getEntries() == null ? new ArrayList<>() : new ArrayList<>(f.getEntries());

        ConsentEntry entry = null;
        for (ConsentEntry e : entries) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                entry = e;
                break;
            }
        }
        if (entry == null) {
            entry = new ConsentEntry();
            entry.setAddress(normalised);
            entries.add(entry);
        }
        entry.setStatus(ConsentStatus.PENDING);
        entry.setRequestedAt(now);
        entry.setConfirmedAt(0L);
        entry.setTokenExpiresAt(now + ttlMillis);
        entry.setTokenSalt(saltB64);
        entry.setTokenHash(hash);

        f.setEntries(entries);
        persist(f);
        // #1811 PR4: the send layer will call requestConsent(...) then mail the recipient their confirm
        // link (RecipientGate.clear() gating the invite itself). Nothing is sent here.
        return rawToken;
    }

    /**
     * Confirm consent for {@code address} against the supplied raw {@code token}: constant-time match
     * the salted hash AND require the token unexpired → flip {@link ConsentStatus#PENDING} to
     * {@link ConsentStatus#CONFIRMED} (idempotent — re-confirming an already-confirmed address is a
     * no-op success). Any mismatch, expiry, unknown/absent entry, REVOKED entry, or malformed input
     * returns {@code false} and changes nothing. Never throws; never logs the address or token.
     *
     * @return {@code true} iff the address is CONFIRMED after this call as a result of a valid token
     *     (or was already confirmed); {@code false} otherwise.
     */
    public synchronized boolean confirm(String address, String token) {
        String normalised = normaliseAddress(address);
        if (normalised == null || token == null || token.isBlank()) {
            return false;
        }
        ConsentFile f = read().orElse(null);
        if (f == null || f.getEntries() == null) {
            return false;
        }
        List<ConsentEntry> entries = new ArrayList<>(f.getEntries());
        ConsentEntry entry = null;
        for (ConsentEntry e : entries) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                entry = e;
                break;
            }
        }
        if (entry == null) {
            return false;
        }
        // Already confirmed — idempotent success, but ONLY if a prior valid confirm landed. We do not
        // re-verify the token here (the hash may have been cleared on first confirm).
        if (entry.getStatus() == ConsentStatus.CONFIRMED) {
            return true;
        }
        if (entry.getStatus() != ConsentStatus.PENDING) {
            return false; // REVOKED (or unknown) never confirms
        }
        if (entry.getTokenExpiresAt() > 0 && System.currentTimeMillis() > entry.getTokenExpiresAt()) {
            return false; // expired
        }
        String salt = entry.getTokenSalt();
        String storedHash = entry.getTokenHash();
        if (salt == null || storedHash == null) {
            return false;
        }
        byte[] saltBytes;
        try {
            saltBytes = Base64.getDecoder().decode(salt);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String suppliedHash = hash(saltBytes, token.trim());
        boolean match = MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8), suppliedHash.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            return false;
        }
        entry.setStatus(ConsentStatus.CONFIRMED);
        entry.setConfirmedAt(System.currentTimeMillis());
        // Burn the token: once confirmed, the hash+salt are no longer needed and must not linger.
        entry.setTokenHash(null);
        entry.setTokenSalt(null);
        f.setEntries(entries);
        persist(f);
        return true;
    }

    /**
     * True when {@code address} has a stored entry in state {@link ConsentStatus#CONFIRMED}. Pure
     * string/hash work; no network. Fail-closed: absent / PENDING / REVOKED / malformed / corrupt all
     * return {@code false}. This is the method a {@link RecipientGate} consults LAST.
     */
    public boolean isConfirmed(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return false;
        }
        ConsentFile f = read().orElse(null);
        if (f == null || f.getEntries() == null) {
            return false;
        }
        for (ConsentEntry e : f.getEntries()) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                return e.getStatus() == ConsentStatus.CONFIRMED;
            }
        }
        return false;
    }

    /**
     * Revoke consent for {@code address}: set {@link ConsentStatus#REVOKED} and burn any pending token.
     * A no-op returning {@code false} when the address is unknown / invalid. Idempotent.
     *
     * @return {@code true} if an entry moved to REVOKED (or was already REVOKED); {@code false} if
     *     unknown/invalid.
     */
    public synchronized boolean revoke(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return false;
        }
        ConsentFile f = read().orElse(null);
        if (f == null || f.getEntries() == null) {
            return false;
        }
        List<ConsentEntry> entries = new ArrayList<>(f.getEntries());
        for (ConsentEntry e : entries) {
            if (normalised.equals(normaliseAddress(e.getAddress()))) {
                e.setStatus(ConsentStatus.REVOKED);
                e.setTokenHash(null);
                e.setTokenSalt(null);
                f.setEntries(entries);
                persist(f);
                return true;
            }
        }
        return false;
    }

    /**
     * Redacted admin view of the consent store: masked addresses (local part reduced to its first
     * character), status, timestamps, plus the total count. NEVER returns raw addresses or the token
     * hash/salt.
     */
    public ConsentView list() {
        ConsentFile f = read().orElseGet(ConsentFile::new);
        List<ConsentView.Entry> out = new ArrayList<>();
        List<ConsentEntry> entries = f.getEntries() == null ? List.of() : f.getEntries();
        for (ConsentEntry e : entries) {
            out.add(new ConsentView.Entry(
                    mask(e.getAddress()),
                    e.getStatus(),
                    e.getRequestedAt(),
                    e.getConfirmedAt(),
                    e.getTokenExpiresAt()));
        }
        return new ConsentView(List.copyOf(out), out.size());
    }

    // ---- persistence ----

    private void persist(ConsentFile f) {
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

    // ---- hashing (salted, one-way) ----

    /** base64( SHA-256( salt || rawToken ) ). One-way — the raw token is never recoverable. */
    private static String hash(byte[] salt, String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGO);
            md.update(salt);
            md.update(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
