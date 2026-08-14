/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.email;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for {@code POST /saiku/admin/mail-recipients} — replaces the recipient allowlist
 * (saiku#1811, PR1). The store normalises + validates every entry and drops anything malformed, so an
 * inbound list is best-effort. Sending empty lists clears the allowlist ("allow nobody").
 */
public class RecipientTrustRequest {

    private List<String> addresses = new ArrayList<>();
    private List<String> domains = new ArrayList<>();

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
    }

    public List<String> getDomains() {
        return domains;
    }

    public void setDomains(List<String> domains) {
        this.domains = domains;
    }
}
