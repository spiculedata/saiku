/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem-backed access control list. Jackrabbit-era {@code javax.jcr.Node}
 * variants were removed in Phase 2.
 */
class Acl2 {

    private static final Logger LOG = LoggerFactory.getLogger(Acl2.class);

    private List<String> adminRoles;

    /**
     * Default method granted on un-ACL'd nodes whose parent chain runs out
     * before hitting an {@code acl.json}. Flipped from {@link AclMethod#WRITE}
     * to {@link AclMethod#READ} for saiku#951.
     *
     * <p>Note: this value is currently <b>not reached at runtime</b> for any
     * real filesystem path. The fallback branch in {@link #getMethods} that
     * consults {@code rootMethod} guards on
     * {@code file.getParentFile().getName().equals("/")}, but the Java
     * {@link File} model returns the empty string ({@code ""}) for the
     * filesystem root on Unix (and {@code "C:"}-style names on Windows) —
     * never the literal {@code "/"}. Every un-ACL'd path therefore walks the
     * parent chain up to a null parent and resolves to {@link AclMethod#NONE}.
     *
     * <p>Flipping the default is defensive: if a future change repairs the
     * parent-detection guard to actually trigger this branch, the inherited
     * default is least-privilege (READ, not WRITE) so the fix doesn't
     * accidentally widen access. The previous value of {@link AclMethod#WRITE}
     * was a Jackrabbit-era artifact carried over verbatim during the
     * filesystem port.
     */
    private AclMethod rootMethod = AclMethod.READ;

    @NotNull
    private final Map<String, AclEntry> acl = new TreeMap<>();

    public Acl2(File n) {}

    public void setAdminRoles(List<String> adminRoles) {
        this.adminRoles = adminRoles;
    }

    public void setRootAcl(String rootAcl) {
        try {
            if (StringUtils.isNotBlank(rootAcl)) {
                rootMethod = AclMethod.valueOf(rootAcl);
            }
        } catch (Exception e) {
            LOG.error("Failed to set root ACL", e);
        }
    }

    @NotNull
    private List<AclMethod> getAllAcls(@Nullable AclMethod maxMethod) {
        List<AclMethod> methods = new ArrayList<>();
        if (maxMethod != null) {
            for (AclMethod m : AclMethod.values()) {
                if (m.ordinal() > 0 && m.ordinal() <= maxMethod.ordinal()) {
                    methods.add(m);
                }
            }
        }
        return methods;
    }

    public void addEntry(String path, @Nullable AclEntry entry) {
        if (entry != null) {
            acl.put(path, entry);
        }
    }

    @Nullable
    public AclEntry getEntry(String path) {
        return acl.containsKey(path) ? acl.get(path) : null;
    }

    public List<String> getAdminRoles() {
        return adminRoles;
    }

    private boolean isAdminRole(String role) {
        return adminRoles != null && adminRoles.contains(role);
    }

    private boolean isAdminRole(@NotNull List<String> roles) {
        for (String role : roles) {
            if (isAdminRole(role)) {
                return true;
            }
        }
        return false;
    }

    public void serialize(File n) {
        try {
            File f = new File(n, "acl.json");
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(f, acl);
        } catch (Exception e) {
            LOG.info("Error while writing ACL files at path: " + n.getPath(), e.getCause());
        }
    }

    public boolean canWrite(File node, String user, List<String> roles) {
        if (node == null) {
            return false;
        }
        List<AclMethod> acls = getMethods(node, user, roles);
        return acls.contains(AclMethod.WRITE);
    }

    public boolean canRead(File node, String user, List<String> roles) {
        if (node == null) {
            return false;
        }
        List<AclMethod> acls = getMethods(node, user, roles);
        return acls.contains(AclMethod.READ);
    }

    public boolean canGrant(File node, String user, List<String> roles) {
        if (node == null) {
            return false;
        }
        List<AclMethod> acls = getMethods(node, user, roles);
        return acls.contains(AclMethod.GRANT);
    }

    public boolean canRead(String relativePath, String user, List<String> roles) {
        if (relativePath == null) {
            return false;
        }
        List<AclMethod> acls = getMethods(new File(relativePath), user, roles);
        return acls.contains(AclMethod.READ);
    }

    @NotNull
    public List<AclMethod> getMethods(@NotNull File file, String username, @NotNull List<String> roles) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            AclEntry entry = null;
            Map<String, AclEntry> aclData = new TreeMap<>();

            try {
                TypeReference<Map<String, AclEntry>> ref = new TypeReference<Map<String, AclEntry>>() {};
                aclData = mapper.readValue(new File(file, "acl.json"), ref);
                entry = aclData.get(file.getPath());
            } catch (Exception e) {
                LOG.debug("Exception: " + file.getPath(), e.getCause());
            }

            AclMethod method;

            if (file.getPath().startsWith("..")) {
                return getAllAcls(AclMethod.NONE);
            }
            if (isAdminRole(roles)) {
                return getAllAcls(AclMethod.GRANT);
            }

            if (entry != null) {
                switch (entry.getType()) {
                    case PRIVATE:
                        if (!entry.getOwner().equals(username)) {
                            method = AclMethod.NONE;
                        } else {
                            method = AclMethod.GRANT;
                        }
                        break;
                    case SECURED:
                        List<AclMethod> allMethods = new ArrayList<>();

                        if (StringUtils.isNotBlank(entry.getOwner())
                                && entry.getOwner().equals(username)) {
                            allMethods.add(AclMethod.GRANT);
                        }

                        List<AclMethod> userMethods =
                                entry.getUsers() != null && entry.getUsers().containsKey(username)
                                        ? entry.getUsers().get(username)
                                        : new ArrayList<AclMethod>();

                        List<AclMethod> roleMethods = new ArrayList<>();
                        for (String role : roles) {
                            List<AclMethod> r =
                                    entry.getRoles() != null && entry.getRoles().containsKey(role)
                                            ? entry.getRoles().get(role)
                                            : new ArrayList<AclMethod>();
                            roleMethods.addAll(r);
                        }

                        allMethods.addAll(userMethods);
                        allMethods.addAll(roleMethods);

                        if (allMethods.size() == 0) {
                            method = AclMethod.NONE;
                        } else {
                            method = AclMethod.max(allMethods);
                        }

                        break;
                    default:
                        method = AclMethod.WRITE;
                        break;
                }
            } else {
                if (file.getParentFile() == null) {
                    method = AclMethod.NONE;
                } else if (file.getParentFile().getName().equals("/")) {
                    return getAllAcls(rootMethod);
                } else {
                    List<AclMethod> parentMethods = getMethods(file.getParentFile(), username, roles);
                    method = AclMethod.max(parentMethods);
                }
            }

            return getAllAcls(method);
        } catch (Exception e) {
            LOG.debug("Error", e.getCause());
        }

        List<AclMethod> noMethod = new ArrayList<>();
        noMethod.add(AclMethod.NONE);
        return noMethod;
    }
}
