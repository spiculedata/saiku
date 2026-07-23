package org.saiku.service.mail;

import java.util.function.Function;

/** SMTP settings, resolved env > system property > default. */
public record MailConfig(
        String host, int port, String username, String password,
        String from, boolean startTls, boolean ssl) {

    /** Configured only when a host and a from-address are both present. */
    public boolean isConfigured() {
        return notBlank(host) && notBlank(from);
    }

    public static MailConfig resolve(Function<String, String> env, Function<String, String> prop) {
        String host = pick(env, prop, "SAIKU_MAIL_SMTP_HOST", "saiku.mail.smtp.host", null);
        String from = pick(env, prop, "SAIKU_MAIL_FROM", "saiku.mail.from", null);
        String user = pick(env, prop, "SAIKU_MAIL_SMTP_USERNAME", "saiku.mail.smtp.username", null);
        String pass = pick(env, prop, "SAIKU_MAIL_SMTP_PASSWORD", "saiku.mail.smtp.password", null);
        int port = parseInt(pick(env, prop, "SAIKU_MAIL_SMTP_PORT", "saiku.mail.smtp.port", "587"), 587);
        boolean startTls = parseBool(pick(env, prop, "SAIKU_MAIL_SMTP_STARTTLS", "saiku.mail.smtp.starttls", "true"), true);
        boolean ssl = parseBool(pick(env, prop, "SAIKU_MAIL_SMTP_SSL", "saiku.mail.smtp.ssl", "false"), false);
        return new MailConfig(host, port, user, pass, from, startTls, ssl);
    }

    private static String pick(Function<String, String> env, Function<String, String> prop,
            String envKey, String propKey, String def) {
        String v = env.apply(envKey);
        if (notBlank(v)) return v.trim();
        v = prop.apply(propKey);
        if (notBlank(v)) return v.trim();
        return def;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private static boolean parseBool(String s, boolean def) {
        return s == null ? def : Boolean.parseBoolean(s.trim());
    }
}
