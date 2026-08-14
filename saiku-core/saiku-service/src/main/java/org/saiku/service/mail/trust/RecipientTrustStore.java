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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writable, file-backed recipient allowlist for the recipient-trust model (saiku#1811, PR1).
 *
 * <p>Persists {@code ${saiku.home}/mail-recipients.json} as a typed {@link RecipientTrustFile}: a set
 * of explicitly-allowed addresses and a set of allowed domain suffixes. Admin-writable only (the
 * admin gate lives on the REST resource + the {@code /rest/saiku/admin/**} Spring URL gate).
 *
 * <p><b>DORMANT infrastructure — this PR cannot mail anyone.</b> The store adds ZERO send capability:
 * no send path calls {@link #isAllowed(String)}, there is no consent/suppression, and the anti-relay
 * boundary (recipient is only ever the server's own {@code selfTo}) is untouched. {@link
 * #isAllowed(String)} is provided for the future gate but is pure string work — no network/DNS lookup
 * (no SSRF).
 *
 * <p><b>Fail-closed default.</b> An empty/absent store allows NOBODY — {@link #isAllowed(String)}
 * returns {@code false} until an admin explicitly adds an entry.
 *
 * <p><b>Normalisation.</b> Every stored address / domain is lowercased, CRLF-stripped and, for
 * addresses, RFC-validated ({@link InternetAddress#validate()}); malformed entries are dropped on
 * save. Domain matching is <b>exact host-suffix only</b>: {@code acme.com} matches {@code
 * x@acme.com} but NOT {@code x@evil-acme.com} (this bypass-closure is load-bearing).
 *
 * <p>When {@code saiku.home} is unset the constructor path is null and the store falls back to an
 * in-memory allowlist (never touches disk) so unit/embedded contexts still work.
 *
 * <p>All IO is best-effort: a missing file reads as an empty allowlist; an unreadable/corrupt file
 * logs and reads as empty rather than wedging the app. Writes are atomic (temp file + atomic move).
 */
public class RecipientTrustStore {

    private static final Logger log = LoggerFactory.getLogger(RecipientTrustStore.class);
    private static final String FILE_NAME = "mail-recipients.json";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Null when {@code saiku.home} is unset — then the store is in-memory only. */
    private final Path file;

    /** In-memory fallback used when {@link #file} is null (no {@code saiku.home}). */
    private volatile RecipientTrustFile inMemory = new RecipientTrustFile();

    /**
     * @param homeDir the resolved {@code ${saiku.home}} directory, or {@code null}/blank to run
     *     in-memory only. The allowlist file lives directly under it.
     */
    public RecipientTrustStore(Path homeDir) {
        this.file = homeDir == null ? null : homeDir.resolve(FILE_NAME);
    }

    /** True when a persisted allowlist file exists on disk (always false for the in-memory fallback). */
    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    /** The raw stored record, or empty when nothing is stored / it can't be parsed. */
    public Optional<RecipientTrustFile> read() {
        if (file == null) {
            return Optional.of(inMemory);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), RecipientTrustFile.class));
        } catch (IOException e) {
            log.warn("Could not read {} — treating recipient allowlist as empty ({})", FILE_NAME, e.getMessage());
            return Optional.empty();
        }
    }

    /** Read projection for the admin GET: the normalised allowlist plus a total count. */
    public RecipientTrustView readView() {
        RecipientTrustFile f = read().orElseGet(RecipientTrustFile::new);
        List<String> addresses = f.getAddresses() == null ? List.of() : List.copyOf(f.getAddresses());
        List<String> domains = f.getDomains() == null ? List.of() : List.copyOf(f.getDomains());
        return new RecipientTrustView(addresses, domains, addresses.size() + domains.size());
    }

    /**
     * Replace the allowlist with the given addresses + domain suffixes. Each entry is normalised
     * (lowercase, CRLF-strip) and — for addresses — RFC-validated; malformed or blank entries are
     * dropped, and duplicates are collapsed (insertion order preserved). Writing an empty allowlist
     * is legal and means "allow nobody".
     *
     * @return the redacted view of what was written.
     */
    public RecipientTrustView save(List<String> addresses, List<String> domains) {
        RecipientTrustFile f = new RecipientTrustFile();
        f.setAddresses(normaliseAddresses(addresses));
        f.setDomains(normaliseDomains(domains));

        if (file == null) {
            this.inMemory = f;
        } else {
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

        return new RecipientTrustView(
                List.copyOf(f.getAddresses()),
                List.copyOf(f.getDomains()),
                f.getAddresses().size() + f.getDomains().size());
    }

    /**
     * True when {@code address} is on the allowlist — an exact address match, or its host matches an
     * allowed domain suffix. Pure string work; performs NO network / DNS lookup. Fail-closed: a null,
     * blank, malformed, or unlisted address returns {@code false}, as does an empty store.
     *
     * <p><b>Not yet wired.</b> As of PR1 no send path calls this method — it exists for the future
     * {@code RecipientGate} (saiku#1811 PR2/PR3).
     */
    public boolean isAllowed(String address) {
        String normalised = normaliseAddress(address);
        if (normalised == null) {
            return false;
        }
        RecipientTrustFile f = read().orElse(null);
        if (f == null) {
            return false; // fail-closed: no allowlist -> allow nobody
        }
        if (f.getAddresses() != null && f.getAddresses().contains(normalised)) {
            return true;
        }
        String host = hostOf(normalised);
        if (host == null || f.getDomains() == null) {
            return false;
        }
        // Exact host match only — no substring/suffix comparison, so acme.com never matches
        // evil-acme.com. (The load-bearing bypass-closure; see class javadoc.)
        return f.getDomains().contains(host);
    }

    // ---- normalisation helpers (pure; no network) ----

    private static List<String> normaliseAddresses(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String raw : in) {
                String n = normaliseAddress(raw);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> normaliseDomains(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String raw : in) {
                String n = normaliseDomain(raw);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }

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
            // getAddress() drops any personal/display part — allowlist stores the bare address.
            String bare = ia.getAddress();
            return bare == null ? null : bare.toLowerCase();
        } catch (AddressException e) {
            return null;
        }
    }

    /**
     * Lowercase + CRLF-strip a domain suffix (host part only). Accepts a leading {@code @} for
     * convenience and strips it. Rejects anything with an {@code @} in the middle (that's an address,
     * not a domain) or without a dot; null when blank/invalid.
     */
    private static String normaliseDomain(String domain) {
        String s = stripCrlf(domain);
        if (s == null) {
            return null;
        }
        s = s.toLowerCase();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        // Must be a bare host: no remaining '@', at least one dot, no whitespace.
        if (s.isEmpty() || s.indexOf('@') >= 0 || s.indexOf('.') < 0 || s.indexOf(' ') >= 0) {
            return null;
        }
        return s;
    }

    /** The host part (after the single {@code @}) of a normalised address, or null if malformed. */
    private static String hostOf(String normalisedAddress) {
        int at = normalisedAddress.lastIndexOf('@');
        if (at < 0 || at == normalisedAddress.length() - 1) {
            return null;
        }
        return normalisedAddress.substring(at + 1);
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
