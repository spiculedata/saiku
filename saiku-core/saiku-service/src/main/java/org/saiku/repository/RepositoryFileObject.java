package org.saiku.repository;

import java.util.List;

public class RepositoryFileObject implements IRepositoryObject {

    private final Type type;
    private final String name;
    private final String id;
    private final String filetype;
    private final String path;
    private final List<AclMethod> acl;

    public RepositoryFileObject(String filename, String id, String filetype, String path, List<AclMethod> acl) {
        this.type = Type.FILE;
        this.name = filename;
        this.id = id;
        this.filetype = filetype;
        this.path = path;
        this.acl = acl;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getFileType() {
        return filetype;
    }

    public String getPath() {
        return path;
    }

    public String getId() {
        return id;
    }

    public List<AclMethod> getAcl() {
        return acl;
    }
}
