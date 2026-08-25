/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.importer;

/**
 * Created by bugg on 02/09/15.
 */
public class JujuSource {

    String name;
    String url;
    String username;
    String password;
    String driver;

    public JujuSource() {}

    public JujuSource(String name, String url, String username, String password, String driver) {
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
        this.driver = driver;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }
}
