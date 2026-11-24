/*
 * Copyright 2026 Spicule Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.saiku.service.datasource;

import java.lang.reflect.Constructor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * saiku#2547 (CWE-532): masks the credential values a failed connection can leak into a log line
 * or an error response.
 *
 * <p>When a connection fails, the exception message carries the full connect string. For a
 * {@code security.type=passthrough} datasource that string carries the signed-in user's login name
 * and <b>plaintext password</b> as {@code JdbcUser=}/{@code JdbcPassword=} (built in
 * {@code SaikuOlapConnection#connect(Properties)}); for other datasources it carries the stored
 * warehouse password the same way, or inside a JDBC URL's user-info
 * ({@code jdbc:postgresql://user:password@host}). {@code SecurityAwareConnectionManager} and
 * {@code AbstractConnectionManager} log that exception at ERROR, and the admin datasource
 * endpoints return its message — so {@code saiku.log}, a log shipper, or
 * {@code GET /rest/saiku/admin/log/{logname}} exposes it.
 *
 * <p>This class is the one redaction applied wherever those connection failures are logged or
 * returned:
 *
 * <ul>
 *   <li>{@link #redact(String)} masks password-bearing connect-string fields through the next
 *       Mondrian field boundary, even when the raw value contains spaces or delimiters. It also
 *       masks URL query passwords, URL user-info passwords and Oracle thin URL passwords. Other
 *       fields, including {@code JdbcUser=}, remain available to identify the datasource and root
 *       cause (saiku#2547 C3).
 *   <li>{@link #redact(Throwable)} returns a copy of the exception whose whole cause chain (and
 *       every suppressed exception) carries redacted messages but the original stack traces and
 *       types — the deepest constructible match, falling back to a typed wrapper when the concrete
 *       class has no copyable constructor — so the original exception can still be thrown or
 *       logged safely.
 * </ul>
 *
 * <p>Deliberately NOT redacted: {@code JdbcUser=} and the user half of user-info. A login name is
 * not the secret the issue is about, and keeping it keeps the log line actionable.
 */
public final class ConnectStringRedactor {

    /** The mask every credential value is replaced with. */
    private static final String MASK = "***";

    /**
     * Password-bearing keys in connect strings and URL query parameters. Mondrian removes Saiku's
     * quoting before including the connect string in an error, so a raw value may contain spaces,
     * semicolons, ampersands and quotes. Other password-suffixed JDBC property names also carry
     * credentials, for example {@code sslpassword} and {@code trustStorePassword}.
     */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)(?<![\\w.])((?:[a-z][\\w.]*)?password|passwd|pwd)\\s*=\\s*");

    /** Mondrian separates fields with a semicolon followed by the next key and equals sign. */
    private static final Pattern CONNECT_FIELD = Pattern.compile(";\\s*[a-z][\\w.]*\\s*=", Pattern.CASE_INSENSITIVE);

    /** URL query parameters end at a query separator, fragment or whitespace. */
    private static final Pattern QUERY_END = Pattern.compile("[;&\\s#]");

    /**
     * The password half of a URL's user-info: {@code scheme://user:password@host}. Only a
     * user-info that carries a password is touched; a bare {@code user@host} is left alone.
     */
    private static final Pattern USER_INFO_PASSWORD =
            Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)([^/\\s:]*)?:([^@/\\s]*)@");

    /** Oracle thin URLs use {@code user/password@host} instead of URL user-info. */
    private static final Pattern ORACLE_THIN_PASSWORD =
            Pattern.compile("(?i)(jdbc:oracle:thin:[^:/@\\s]+/)[^@\\s]*(@)");

    private ConnectStringRedactor() {}

    /**
     * Masks credential values in free text (an exception message, an error response body). Null
     * and empty input pass through unchanged; text with no credential shape is returned as-is so
     * the redaction is a no-op on ordinary log lines.
     *
     * @param text the text to redact, may be null
     * @return the text with every credential value replaced by {@value #MASK}, or {@code null}
     *         when the input was null
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder maskedKeys = new StringBuilder(text.length());
        Matcher keys = SECRET_KEY.matcher(text);
        int cursor = 0;
        while (keys.find(cursor)) {
            int valueStart = keys.end();
            boolean queryParameter =
                    keys.start() > 0 && (text.charAt(keys.start() - 1) == '?' || text.charAt(keys.start() - 1) == '&');
            Matcher boundary = (queryParameter ? QUERY_END : CONNECT_FIELD).matcher(text);
            int valueEnd = boundary.find(valueStart) ? boundary.start() : text.length();
            maskedKeys.append(text, cursor, valueStart).append(MASK);
            cursor = valueEnd;
        }
        maskedKeys.append(text, cursor, text.length());
        String masked = maskedKeys.toString();
        masked = USER_INFO_PASSWORD.matcher(masked).replaceAll("$1$2:" + MASK + "@");
        masked = ORACLE_THIN_PASSWORD.matcher(masked).replaceAll("$1" + MASK + "$2");
        return masked;
    }

    /**
     * Redacts an exception for logging: the returned throwable carries the original types, stack
     * traces and cause/suppressed structure, but every message in the chain has had its
     * credential values masked by {@link #redact(String)}.
     *
     * <p>The original throwable is never modified and is returned unchanged when its whole chain
     * is already clean. A link whose message must change is rebuilt from the original class when
     * a constructor can produce it — {@code (String, Throwable)} or {@code (String)} — falling
     * back to a {@link Redacted} wrapper prefixed with the original class name when it cannot, so
     * the log never loses the failing type. This keeps the exception object itself (its fields,
     * its behaviour) out of the copy: the copy is for the log, not for rethrowing.
     *
     * @param t the exception to redact, may be null
     * @return a throwable with the same types, traces and structure as {@code t} but redacted
     *         messages, or {@code null} when the input was null
     */
    public static Throwable redact(Throwable t) {
        if (t == null) {
            return null;
        }
        // Self-referential causes ("I am my own cause") are legal; treat them as chain ends.
        Throwable cause = t.getCause();
        if (cause == t) {
            cause = null;
        }
        Throwable redactedCause = redact(cause);
        String message = t.getMessage();
        String redactedMessage = redact(message);
        boolean messageClean = message == null ? redactedMessage == null : message.equals(redactedMessage);
        // Suppressed exceptions ("while closing X, also failed with ...") are printed with the
        // exception and can carry the connect string too, so a clean message is not enough.
        boolean suppressedClean = true;
        for (Throwable suppressed : t.getSuppressed()) {
            if (redact(suppressed) != suppressed) {
                suppressedClean = false;
                break;
            }
        }
        if (messageClean && redactedCause == cause && suppressedClean) {
            return t;
        }

        Throwable copy = copyOf(t, redactedMessage, redactedCause);
        if (copy == null) {
            copy = new Redacted(
                    redactedMessage == null
                            ? t.getClass().getName()
                            : t.getClass().getName() + ": " + redactedMessage,
                    redactedCause);
        }
        copy.setStackTrace(t.getStackTrace());
        // Suppressed exceptions ("while closing X, also failed with ...") are printed with the
        // exception and can carry the connect string too.
        for (Throwable suppressed : t.getSuppressed()) {
            copy.addSuppressed(redact(suppressed));
        }
        return copy;
    }

    /**
     * Builds a same-class redacted copy of {@code t} with {@code message} and {@code cause}, or
     * {@code null} when the class has no constructor that can. Reflective on purpose: the redactor
     * must work for every exception type in a connection-failure chain (Mondrian's, the JDBC
     * driver's, Saiku's own) without a dependency on any of them.
     */
    private static Throwable copyOf(Throwable t, String message, Throwable cause) {
        Class<?> cls = t.getClass();
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 1 && params.length != 2) {
                continue;
            }
            if (params[0] != String.class || (params.length == 2 && params[1] != Throwable.class)) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                Throwable copy = params.length == 2
                        ? (Throwable) ctor.newInstance(message, cause)
                        : (Throwable) ctor.newInstance(message);
                if (params.length == 1 && cause != null) {
                    copy.initCause(cause);
                }
                return copy;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Cannot reconstruct this type — fall through to the next shape.
            }
        }
        return null;
    }

    /** Fallback copy when an exception type has no copyable constructor: keeps type and message. */
    private static final class Redacted extends RuntimeException {
        private Redacted(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
