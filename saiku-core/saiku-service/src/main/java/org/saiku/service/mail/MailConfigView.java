package org.saiku.service.mail;

/**
 * Read-safe projection of the stored mail config for any caller that reads settings back (the admin
 * wizard's GET, health checks, etc). It carries every field EXCEPT the password: the SMTP password is
 * write-only by design — it is encrypted at rest and never returned, so a compromised read path
 * (or an over-broad log) can't leak it. {@link #passwordSet} tells the UI whether a password is on
 * file (so it can render "•••• (set)") without disclosing it.
 *
 * <p>{@link #managedByOps} flags a field whose effective value comes from an environment variable or
 * system property rather than the writable file — env still wins (ops override), and the wizard shows
 * such fields read-only as "managed by ops" so a UI save can't silently shadow a deploy. In P0-A the
 * flag is always {@code false} (no env-precedence merge yet); P0-B/precedence wiring sets it.
 */
public record MailConfigView(
        String host,
        int port,
        String username,
        boolean passwordSet,
        String from,
        boolean startTls,
        boolean ssl,
        String selfTo,
        boolean managedByOps) {}
