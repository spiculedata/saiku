package org.saiku.repository;

import java.util.List;
import java.util.Map;

/**
 * The class that holds the acl for a single resource
 * @author marco
 *
 */
public class AclEntry {
    /**
     * the owner of the resource
     */
    private String owner;
    /**
     * the type of access to the resource : defaults to {@link AclType#PUBLIC}
     */
    private AclType type = AclType.PUBLIC;
    /**
     * the list of roles and their associated access granted for the resource
     */
    private Map<String, List<AclMethod>> roles;
    /**
     * the list of users and their associated access granted for the resource
     */
    private Map<String, List<AclMethod>> users;
    /**
     * needed in case it is an upgraded instance with no acl
     */
    private static final String STATIC_OWNER = "##upgraded_saiku_instance##";

    /**
     * Constructor needed for some time for upgraded versions ;
     * Creates an acl entry for a resource owned by saiku with
     * public access
     */
    @Deprecated
    public AclEntry() {
        this(STATIC_OWNER);
    }
    /**
     * Constructor. Creates an acl entry owned by <pre>owner</pre> with default
     * access type
     * @param owner
     */
    private AclEntry(String owner) {
        if (owner == null || owner.length() == 0)
            throw new IllegalArgumentException("Owner of the resource is mandatory");
        this.owner = owner;
    }
    /**
     * Constructor. Creates an acl entry owned by <pre>owner</pre> with access
     * type <pre>type</pre>
     * @param owner
     * @param type
     */
    public AclEntry(
            String owner, AclType type, Map<String, List<AclMethod>> roles, Map<String, List<AclMethod>> users) {
        this(owner);
        this.type = type;
        this.users = users;
        this.roles = roles;
    }
    /**
     * returns the owner of the resource
     * @return
     */
    public String getOwner() {
        return owner;
    }
    /**
     * returns the type of access of the file
     * @return
     */
    public AclType getType() {
        return type;
    }
    /**
     * returns the list of the roles and their grants
     * @return
     */
    public Map<String, List<AclMethod>> getRoles() {
        return roles;
    }

    /**
     * returns the list of the users and their grants
     * @return
     */
    public Map<String, List<AclMethod>> getUsers() {
        return users;
    }
}
