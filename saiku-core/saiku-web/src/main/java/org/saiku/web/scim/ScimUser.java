/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM 2.0 core {@code User} (RFC 7643 §4.1) plus the {@code User} extension attributes Saiku needs.
 *
 * <p>Only the core schema is served — deliberately. An IdP connector that asks for
 * {@code enterprise:2.0:User} should not receive a half-truth: unknown attributes are simply
 * absent, which is legal SCIM, and the provisioning profile documents the supported subset.
 *
 * <p>Null fields are omitted ({@code NON_NULL}) because SCIM clients treat an explicit
 * {@code "name": null} differently from an absent {@code name} on PATCH round-trips.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimUser {

    @JsonProperty("schemas")
    public List<String> schemas = List.of(ScimSchemas.USER);

    @JsonProperty("id")
    public String id;

    @JsonProperty("externalId")
    public String externalId;

    /** Maps 1:1 onto the Saiku username, canonicalised through {@code Usernames.canonicalize}. */
    @JsonProperty("userName")
    public String userName;

    @JsonProperty("name")
    public Name name;

    @JsonProperty("displayName")
    public String displayName;

    @JsonProperty("emails")
    public List<Email> emails;

    /** {@code false} deactivates the Saiku account (USERS.ENABLED=0). */
    @JsonProperty("active")
    public Boolean active;

    @JsonProperty("groups")
    public List<Member> groups;

    @JsonProperty("meta")
    public Meta meta;

    /** SCIM multi-valued attributes are complex; a typed class is far less error-prone to
     *  serialise than {@code Map<String,Object>}, and Okta round-trips them verbatim. */
    public static class Name {
        @JsonProperty("givenName")
        public String givenName;

        @JsonProperty("familyName")
        public String familyName;

        @JsonProperty("formatted")
        public String formatted;

        public Name() {}

        public Name(String givenName, String familyName) {
            this.givenName = givenName;
            this.familyName = familyName;
        }

        /** Best-effort display name; never null so a client always has something to show. */
        public String bestDisplay() {
            if (formatted != null && !formatted.isBlank()) {
                return formatted;
            }
            String g = givenName == null ? "" : givenName.trim();
            String f = familyName == null ? "" : familyName.trim();
            if (g.isEmpty()) {
                return f;
            }
            if (f.isEmpty()) {
                return g;
            }
            return g + " " + f;
        }
    }

    public static class Email {
        @JsonProperty("value")
        public String value;

        @JsonProperty("type")
        public String type;

        @JsonProperty("primary")
        public Boolean primary;

        public Email() {}

        public Email(String value, String type, boolean primary) {
            this.value = value;
            this.type = type;
            this.primary = primary;
        }
    }

    /** A user's group (and, in Saiku, role) membership — read-only back to the IdP. */
    public static class Member {
        @JsonProperty("value")
        public String value;

        @JsonProperty("display")
        public String display;

        @JsonProperty("type")
        public String type = "direct";

        @JsonProperty("$ref")
        public String ref;

        public Member() {}

        public Member(String value, String display) {
            this.value = value;
            this.display = display;
        }
    }

    public static class Meta {
        @JsonProperty("resourceType")
        public String resourceType = ScimSchemas.RESOURCE_TYPE_USER;

        @JsonProperty("created")
        public String created;

        @JsonProperty("lastModified")
        public String lastModified;

        @JsonProperty("location")
        public String location;
    }
}
