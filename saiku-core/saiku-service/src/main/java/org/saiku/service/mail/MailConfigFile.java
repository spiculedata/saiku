package org.saiku.service.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed representation of {@code ${saiku.home}/mail-config.json} — the admin-wizard-writable SMTP
 * settings layer (saiku#943 Phase 0, "mail-setup wizard"). Every persisted field has an explicit
 * Java field so a round-trip through Jackson never silently drops config (the dashboard-save
 * drop-on-reload lesson).
 *
 * <p><b>Password at rest.</b> {@link #password} holds the SMTP password <em>as stored on disk</em>:
 * AES-256-GCM ciphertext (a {@code v2:}-prefixed value from {@link
 * org.saiku.datasources.connection.encrypt.CryptoUtil#encrypt}). It is NEVER the plaintext and NEVER
 * leaves this layer un-redacted — {@link MailConfigStore} decrypts it only when handing a usable
 * {@link MailConfig} to the sender, and exposes a redacted view (password omitted, {@code
 * passwordSet} flag only) to any read path. This POJO is deliberately not the API response type.
 *
 * <p>Unknown properties are ignored so a newer on-disk file written by a future version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MailConfigFile {

    private String host;
    private int port = 587;
    private String username;

    /** AES-256-GCM ciphertext (v2:-prefixed) of the SMTP password, or null/blank when unset. */
    private String password;

    private String from;
    private boolean startTls = true;
    private boolean ssl = false;
    private String selfTo;

    public MailConfigFile() {}

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public boolean isStartTls() {
        return startTls;
    }

    public void setStartTls(boolean startTls) {
        this.startTls = startTls;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public String getSelfTo() {
        return selfTo;
    }

    public void setSelfTo(String selfTo) {
        this.selfTo = selfTo;
    }
}
