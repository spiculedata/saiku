package org.saiku.service.mail;

import java.util.function.Function;

/**
 * Merges the two mail-config sources with <b>env-wins</b> precedence (saiku#943 P0-B, the rule Juan
 * confirmed): environment variables / system properties ALWAYS beat the wizard-written file, so a
 * deployment's ops-managed SMTP config can never be silently shadowed by an in-app save.
 *
 * <ul>
 *   <li>{@link #effective} returns the {@link MailConfig} the sender should use: env/prop where set,
 *       otherwise the file. This is the only place that reads the decrypted file config.
 *   <li>{@link #view} returns a read-safe {@link MailConfigView} for the wizard — password omitted
 *       ({@code passwordSet} only) — with {@code managedByOps} set when env/prop is supplying the
 *       mail config, so the UI renders the form read-only ("managed by ops") and a save is refused
 *       against an ops-managed deployment.
 * </ul>
 *
 * <p>"Managed by ops" is defined coarsely on purpose: if env/prop provides EITHER of the two
 * required fields (host or from) the whole config is treated as ops-managed. Mixing a half-env,
 * half-file config would be a confusing footgun, so the wizard is all-or-nothing against ops config.
 */
public final class MailConfigResolver {

    private final Function<String, String> env;
    private final Function<String, String> prop;
    private final MailConfigStore store;

    public MailConfigResolver(MailConfigStore store) {
        this(System::getenv, System::getProperty, store);
    }

    /** Injectable seam (env/prop lookups + store) — public so callers in other modules can test env-wins. */
    public MailConfigResolver(Function<String, String> env, Function<String, String> prop, MailConfigStore store) {
        this.env = env;
        this.prop = prop;
        this.store = store;
    }

    /** True when env/prop supplies the mail config (host or from) — ops is managing it, wizard is read-only. */
    public boolean managedByOps() {
        MailConfig envCfg = MailConfig.resolve(env, prop);
        return notBlank(envCfg.host()) || notBlank(envCfg.from());
    }

    /** The effective config for the sender: env/prop when ops-managed, else the wizard file (may be empty). */
    public MailConfig effective() {
        if (managedByOps()) {
            return MailConfig.resolve(env, prop);
        }
        return store.toMailConfig().orElseGet(() -> MailConfig.resolve(env, prop));
    }

    /**
     * Read-safe view for the wizard GET. When ops-managed, the returned view reflects the env/prop
     * values (password omitted) and {@code managedByOps=true}; otherwise it reflects the stored file
     * (or an empty view when nothing is configured) and {@code managedByOps=false}.
     */
    public MailConfigView view() {
        if (managedByOps()) {
            MailConfig c = MailConfig.resolve(env, prop);
            return new MailConfigView(
                    c.host(),
                    c.port(),
                    c.username(),
                    notBlank(c.password()),
                    c.from(),
                    c.startTls(),
                    c.ssl(),
                    c.selfTo(),
                    true);
        }
        return store.readView()
                .orElseGet(() -> new MailConfigView(null, 587, null, false, null, true, false, null, false));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
