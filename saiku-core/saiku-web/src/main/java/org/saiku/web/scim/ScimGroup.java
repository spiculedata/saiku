/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM 2.0 core {@code Group} (RFC 7643 §8.7).
 *
 * <p>In Saiku a group maps onto a role name: the group's {@code displayName} is the role string
 * recorded in {@code USER_ROLES} for every member. See {@link ScimGroupStore} for the group
 * records themselves and {@code docs/SCIM-PROVISIONING.md} for the caveat that the bundled
 * in-memory auth profile reads roles from {@code users.properties}, not from this table.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScimGroup {

    @JsonProperty("schemas")
    public List<String> schemas = List.of(ScimSchemas.GROUP);

    @JsonProperty("id")
    public String id;

    @JsonProperty("externalId")
    public String externalId;

    @JsonProperty("displayName")
    public String displayName;

    @JsonProperty("members")
    public List<Member> members;

    @JsonProperty("meta")
    public Meta meta;

    public static class Member {
        @JsonProperty("value")
        public String value;

        @JsonProperty("display")
        public String display;

        @JsonProperty("type")
        public String type = "User";

        public Member() {}

        public Member(String value, String display) {
            this.value = value;
            this.display = display;
        }
    }

    public static class Meta {
        @JsonProperty("resourceType")
        public String resourceType = ScimSchemas.RESOURCE_TYPE_GROUP;

        @JsonProperty("created")
        public String created;

        @JsonProperty("lastModified")
        public String lastModified;

        @JsonProperty("location")
        public String location;
    }
}
