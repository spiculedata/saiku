package org.saiku.repository;

import java.util.List;

public class RepositoryFileObject implements IRepositoryObject {

    private final Type type;
    private final String name;
    private final String id;
    private final String filetype;
    private final String path;
    private final List<AclMethod> acl;
    /** Optional ACL owner — populated when {@link Acl2#getOwner} finds one,
     *  null otherwise. Used by the catalogue's #935 owner filter. */
    private final String owner;
    /** Filesystem last-modified time in millis since epoch. Used by the
     *  catalogue's #935 modified-sort. {@code 0} when the underlying
     *  storage doesn't expose mtime. */
    private final long modified;

    /** Legacy constructor preserved for any external callers; new ones
     *  should use {@link #RepositoryFileObject(String, String, String, String, List, String, long)}
     *  so the {@code owner} + {@code modified} catalogue fields aren't lost. */
    public RepositoryFileObject(String filename, String id, String filetype, String path, List<AclMethod> acl) {
        this(filename, id, filetype, path, acl, null, 0L);
    }

    public RepositoryFileObject(
            String filename,
            String id,
            String filetype,
            String path,
            List<AclMethod> acl,
            String owner,
            long modified) {
        this.type = Type.FILE;
        this.name = filename;
        this.id = id;
        this.filetype = filetype;
        this.path = path;
        this.acl = acl;
        this.owner = owner;
        this.modified = modified;
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

    public String getOwner() {
        return owner;
    }

    public long getModified() {
        return modified;
    }
}
