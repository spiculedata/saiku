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
import org.saiku.service.util.security.Usernames;
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

    @Nullable
    private final File node;

    public Acl2(File n) {
        this.node = n;
        loadAclHome();
    }

    /**
     * The directory whose {@code acl.json} holds {@code f}'s entry: the folder
     * itself for a directory, otherwise its parent. A node's ACL lives in the
     * acl.json of its own folder (folders) or its parent folder (files) —
     * keyed by the node's absolute path. This is what lets a single file
     * (e.g. a {@code .saikudash} dashboard) carry its own ACL alongside its
     * siblings instead of being limited to whole-folder granularity (#940).
     */
    @Nullable
    private static File aclHome(@Nullable File f) {
        if (f == null) {
            return null;
        }
        return f.isDirectory() ? f : f.getParentFile();
    }

    /**
     * Best-effort load of the node's acl-home {@code acl.json} into
     * {@link #acl} so {@link #getEntry} sees persisted entries and
     * {@link #serialize} merges rather than overwrites siblings. A missing
     * file (fresh folder) just leaves the map empty.
     */
    private void loadAclHome() {
        File home = aclHome(node);
        if (home == null) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            TypeReference<Map<String, AclEntry>> ref = new TypeReference<Map<String, AclEntry>>() {};
            Map<String, AclEntry> data = mapper.readValue(new File(home, "acl.json"), ref);
            if (data != null) {
                acl.putAll(data);
            }
        } catch (Exception ignored) {
            // No acl.json at this level yet — start from an empty map.
        }
    }

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
            acl.put(canonicalKey(path), entry);
        }
    }

    @Nullable
    public AclEntry getEntry(String path) {
        return lookup(acl, path);
    }

    /**
     * Canonicalise a node path into the stable key form used for {@code acl.json}
     * entries. saiku#1660: the seed writer keyed entries by the path form
     * {@code FilesystemRepositoryManager.createFolder(...).getPath()} produces
     * (a raw {@code datadir + path} concatenation), while the save-time lookup in
     * {@link #getMethods} keyed by the form {@code getNode(...)} produces
     * ({@code resolveWithinDatadir(...).toFile().getPath()} — normalised/absolute).
     * On some platforms (notably Windows fresh homes) these two string forms
     * differ, so the seeded SECURED entry was never found and a legitimate admin
     * write was denied with a 500. Routing every write AND read through this
     * canonicaliser makes the same on-disk directory map to the same key
     * regardless of which construction path produced the {@link File}.
     *
     * <p>Best-effort: {@link File#getCanonicalPath()} may touch the filesystem and
     * can throw for exotic paths; on failure we fall back to a normalised absolute
     * path so we never regress into denying a legitimate write.
     */
    @NotNull
    private static String canonicalKey(@Nullable String path) {
        if (path == null) {
            return "";
        }
        return canonicalKey(new File(path));
    }

    @NotNull
    private static String canonicalKey(@NotNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file.getAbsoluteFile().toPath().normalize().toString();
        }
    }

    /**
     * Resolve an entry for {@code path} tolerating legacy key forms. Tries the
     * canonical key first (fresh homes seeded/written post-#1660), then the raw
     * {@code path} exactly as given (established homes whose {@code acl.json} was
     * written before the canonicalisation landed). This keeps long-standing
     * repositories working without a migration step.
     */
    @Nullable
    private static AclEntry lookup(@NotNull Map<String, AclEntry> data, @NotNull String path) {
        String canonical = canonicalKey(path);
        AclEntry entry = data.get(canonical);
        if (entry == null) {
            entry = data.get(path);
        }
        return entry;
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
            File home = aclHome(n);
            if (home == null) {
                home = n;
            }
            File f = new File(home, "acl.json");
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

    /**
     * Best-effort owner lookup for a path. Walks the same {@code acl.json}
     * chain that {@link #getMethods} consults; returns the {@code owner}
     * field from the file's own ACL entry, or the closest parent's, or
     * {@code null} when no ACL entry is found before the root.
     *
     * <p>Surfaced for the catalogue's #935 owner filter — needed at
     * listing time without forcing a separate {@code getResourceAcl}
     * round-trip per file. Best-effort only: a missing or malformed
     * {@code acl.json} yields {@code null} (the catalogue then renders
     * the file under an "unknown owner" bucket rather than failing the
     * listing).
     */
    @Nullable
    public String getOwner(@NotNull File file) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            TypeReference<Map<String, AclEntry>> ref = new TypeReference<Map<String, AclEntry>>() {};
            try {
                File home = aclHome(file);
                Map<String, AclEntry> aclData = mapper.readValue(new File(home, "acl.json"), ref);
                AclEntry entry = lookup(aclData, file.getPath());
                if (entry != null && StringUtils.isNotBlank(entry.getOwner())) {
                    return entry.getOwner();
                }
            } catch (Exception ignored) {
                // No acl.json at this level — fall through and walk up.
            }
            if (file.getParentFile() != null) {
                return getOwner(file.getParentFile());
            }
            return null;
        } catch (Exception e) {
            LOG.debug("Owner lookup failed for {}", file.getPath(), e);
            return null;
        }
    }

    @NotNull
    public List<AclMethod> getMethods(@NotNull File file, String username, @NotNull List<String> roles) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            AclEntry entry = null;
            Map<String, AclEntry> aclData = new TreeMap<>();

            try {
                TypeReference<Map<String, AclEntry>> ref = new TypeReference<Map<String, AclEntry>>() {};
                // Read the node's acl-home: a folder's own acl.json, or — for a
                // file — its parent's, where the file's per-resource entry lives
                // keyed by absolute path. A null/absent entry then falls through
                // to the parent-chain walk below (inheritance), unchanged (#940).
                File home = aclHome(file);
                aclData = mapper.readValue(new File(home, "acl.json"), ref);
                // saiku#1660: match by canonical key (with a raw-path fallback for
                // legacy acl.json files) so a fresh-home seed entry — keyed by the
                // writer's path form — is found regardless of the platform-specific
                // string divergence between the seed writer and this lookup.
                entry = lookup(aclData, file.getPath());
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
                        // saiku#1907 (CWE-178): case-insensitive owner match — see sameUser().
                        if (!sameUser(entry.getOwner(), username)) {
                            method = AclMethod.NONE;
                        } else {
                            method = AclMethod.GRANT;
                        }
                        break;
                    case SECURED:
                        List<AclMethod> allMethods = new ArrayList<>();

                        if (StringUtils.isNotBlank(entry.getOwner()) && sameUser(entry.getOwner(), username)) {
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
                        // saiku#1904: PUBLIC (and any un-cased type) used to resolve to WRITE, so
                        // every authenticated user could overwrite the seeded /datasources tree and
                        // plant a datasource descriptor. PUBLIC now means world-READABLE; writes
                        // require an explicit SECURED grant, ownership, or an admin role (which
                        // short-circuits above).
                        method = AclMethod.READ;
                        break;
                }
            } else {
                File parentFile = file.getParentFile();
                if (parentFile == null) {
                    method = AclMethod.NONE;
                } else if (isHomesDir(parentFile)) {
                    // saiku#1907: `file` is a per-user home folder (a direct child of
                    // /homes) that carries no ACL entry of its own. It must NOT inherit
                    // the permissive /homes SECURED default (defaultRole -> READ): doing
                    // so leaks every user's saved queries/dashboards to any ROLE_USER
                    // whenever the home's own PRIVATE entry fails to resolve (the
                    // key-normalisation / home-path seam described in the issue). Fail
                    // closed — only the home's owner (the user the folder is named after)
                    // may enter. ROLE_ADMIN already short-circuited to GRANT above, so
                    // admins are unaffected.
                    method = ownsHome(file.getName(), username) ? AclMethod.GRANT : AclMethod.NONE;
                } else if (parentFile.getName().equals("/")) {
                    return getAllAcls(rootMethod);
                } else {
                    List<AclMethod> parentMethods = getMethods(parentFile, username, roles);
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

    /**
     * The {@link AclType} of the nearest ancestor folder that carries its own ACL
     * entry, walking up from {@code file} and stopping at the {@code /homes}
     * container. Returns {@code null} when no ancestor entry is found before then.
     *
     * <p>saiku#1907 F2: the per-file home ACL stamp consults this so it never turns a
     * file saved inside a SECURED/PUBLIC (shared) folder into a PRIVATE file readable
     * only by its saver — which would lock the folder owner and every sharee out.
     */
    @Nullable
    AclType nearestAncestorAclType(@Nullable File file) {
        if (file == null) {
            return null;
        }
        File cur = file.getParentFile();
        while (cur != null) {
            AclEntry e = ownEntry(cur);
            if (e != null) {
                return e.getType();
            }
            if (isHomesDir(cur)) {
                break;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    /**
     * The ACL entry a folder holds for <em>itself</em> in its own {@code acl.json}
     * (keyed by its path), or {@code null}. Reads straight from disk so it is safe to
     * use while walking an ancestor chain. Best-effort — a missing/malformed file is
     * treated as "no entry".
     */
    @Nullable
    private static AclEntry ownEntry(@Nullable File dir) {
        if (dir == null) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            TypeReference<Map<String, AclEntry>> ref = new TypeReference<Map<String, AclEntry>>() {};
            File home = aclHome(dir);
            Map<String, AclEntry> data = mapper.readValue(new File(home, "acl.json"), ref);
            return lookup(data, dir.getPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Is {@code dir} the {@code /homes} container — the direct parent of every
     * per-user home folder? Matched by the folder's own name so it holds
     * regardless of the datadir/workspace prefix ({@code unknown/} vs a workspace)
     * that varies across call contexts — the seam saiku#1907 closes.
     */
    private static boolean isHomesDir(@Nullable File dir) {
        return dir != null && "homes".equals(dir.getName());
    }

    /**
     * Case-insensitive principal/owner comparison (saiku#1907, CWE-178). The
     * account store matches usernames case-insensitively, so the same account can
     * present as {@code admin} or {@code Admin}; a case-sensitive {@code equals}
     * desynchronised ACL ownership from the account, which could both lock the
     * owner out of their own resources and — with a mixed-case home — leak across
     * identities. Because the store cannot hold two accounts differing only in
     * case, an insensitive match never conflates two distinct users.
     */
    private static boolean sameUser(@Nullable String a, @Nullable String b) {
        return Usernames.sameUser(a, b);
    }

    /**
     * Does the home folder {@code folderName} belong to {@code username}? Home
     * folders are named either {@code <user>} (the filesystem repository) or, in
     * legacy/JCR-derived paths, {@code home:<user>}; both spellings are accepted.
     *
     * <p>Compared case-insensitively (saiku#1907, CWE-178): the account store
     * matches usernames case-insensitively, so a home owned by {@code admin} must
     * resolve for a caller principal {@code Admin}. Because the store cannot hold
     * two accounts differing only in case, this can never conflate two distinct
     * users.
     */
    private static boolean ownsHome(@Nullable String folderName, @Nullable String username) {
        if (folderName == null || username == null) {
            return false;
        }
        String owner = folderName.startsWith("home:") ? folderName.substring("home:".length()) : folderName;
        return Usernames.sameUser(owner, username);
    }
}
