/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.List;
import org.apache.commons.lang3.builder.ToStringBuilder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
public class RepositoryFolderObject implements IRepositoryObject {

    private Type type;
    private String name;
    private String id;
    private String path;
    private List<IRepositoryObject> repoObjects;
    private List<AclMethod> acl;

    public RepositoryFolderObject(
            String name, String id, String path, List<AclMethod> acl, List<IRepositoryObject> repoObjects) {
        this.type = Type.FOLDER;
        this.name = name;
        this.id = id;
        this.path = path;
        this.repoObjects = repoObjects;
        this.acl = acl;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public List<AclMethod> getAcl() {
        return acl;
    }

    @JsonProperty
    public List<IRepositoryObject> getRepoObjects() {
        return repoObjects;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setRepoObjects(List<IRepositoryObject> repoObjects) {
        this.repoObjects = repoObjects;
    }

    public void setAcl(List<AclMethod> acl) {
        this.acl = acl;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }
}
