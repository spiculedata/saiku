/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

/**
 * Request body for {@code POST /saiku/api/admin/mail-config} — the admin mail-setup wizard's save
 * (saiku#943 P0-B). The {@link #password} is inbound plaintext (the only direction a plaintext
 * password ever travels): the store encrypts it at rest and never hands it back. A blank/absent
 * password means "keep the existing stored secret".
 */
public class MailConfigRequest {

    private String host;
    private int port = 587;
    private String username;
    private String password;
    private String from;
    private boolean startTls = true;
    private boolean ssl = false;
    private String selfTo;

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
