/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.saiku.database.dto.SaikuUser;
import org.saiku.service.user.UserService;
import org.saiku.service.util.security.Usernames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCIM 2.0 provisioning logic (issue #1438) — the translation layer between the core SCIM schema
 * (RFC 7643) and the Saiku user directory.
 *
 * <p>Mapping (the "proposed shape" in the issue):
 * <ul>
 *   <li>{@code userName} ⇄ Saiku username, canonicalised through {@link Usernames} so an IdP
 *       spelling it {@code Bob.Smith} still resolves the one account it provisioned as
 *       {@code bob.smith}.</li>
 *   <li>{@code emails[type eq "work"].value} (first primary, else first) ⇄ {@code USERS.EMAIL}.</li>
 *   <li>{@code active} ⇄ {@code USERS.ENABLED}; {@code DELETE /Users/{id}} is a SOFT delete
 *       ({@code active=false}), never a hard row delete — Okta and Entra both re-issue a
 *       deactivate for a user they have already deactivated.</li>
 *   <li>{@code name.givenName} / {@code name.familyName} / {@code displayName} ⇄ the
 *       {@code GIVEN_NAME} / {@code FAMILY_NAME} / {@code DISPLAY_NAME} columns.</li>
 *   <li>Groups ⇄ roles: a group's {@code displayName} is the role string recorded in
 *       {@code USER_ROLES} for each member.</li>
 * </ul>
 *
 * <p><b>Not persisted:</b> {@code externalId} and any {@code enterprise:2.0:User} attribute. Saiku
 * has no column for either, so they are accepted and dropped rather than echoed back as if they
 * had been stored — an IdP that later filters on an attribute we never kept would otherwise get
 * silently wrong answers.
 *
 * <p>All lookups are fail-closed: a directory read that throws yields "not found" / "conflict"
 * rather than a partial answer, and a mutating call that throws propagates as a 500 rather than
 * reporting a success the database never recorded.
 */
public class ScimService {

    private static final Logger log = LoggerFactory.getLogger(ScimService.class);

    /** Every Saiku account carries this; SCIM never manages it, so it is always preserved. */
    private static final String BASE_ROLE = "ROLE_USER";

    /** {@code USER_ROLES.ROLE} is VARCHAR(45); a longer group name would blow up the INSERT. */
    private static final int MAX_ROLE_LENGTH = 45;

    private final UserService userService;
    private final ScimGroupStore groupStore;
    private final String defaultWorkspace;

    public ScimService(UserService userService, ScimGroupStore groupStore) {
        this(userService, groupStore, System.getProperty("saiku.scim.default-workspace", ""));
    }

    public ScimService(UserService userService, ScimGroupStore groupStore, String defaultWorkspace) {
        this.userService = userService;
        this.groupStore = groupStore;
        this.defaultWorkspace = defaultWorkspace;
    }

    public String getDefaultWorkspace() {
        return defaultWorkspace;
    }

    /* ============================== Users ============================== */

    public ScimListResponse listUsers(String filter, int startIndex, int count) {
        List<SaikuUser> all = directory();
        List<SaikuUser> matched = new ArrayList<>();
        for (SaikuUser u : all) {
            if (u == null || u.getUsername() == null) {
                continue;
            }
            if (matchesUserFilter(u, filter)) {
                matched.add(u);
            }
        }
        int[] window = page(matched.size(), startIndex, count);
        List<ScimUser> out = new ArrayList<>();
        for (SaikuUser u : matched.subList(window[0], window[1])) {
            out.add(toScimUser(u));
        }
        return new ScimListResponse(out, window[2], matched.size(), out.size());
    }

    public ScimUser getUser(String id) {
        return toScimUser(requireUser(id));
    }

    public SaikuUser requireUser(String id) {
        SaikuUser u = findById(id);
        if (u == null) {
            throw ScimException.notFound("User " + id + " not found");
        }
        return u;
    }

    public ScimUser createUser(ScimUser in) {
        if (in == null || in.userName == null || in.userName.isBlank()) {
            throw ScimException.badRequest("invalidValue", "userName is required");
        }
        String username = Usernames.canonicalize(in.userName.trim());
        if (!isSaneUsername(username)) {
            throw ScimException.badRequest("invalidValue", "userName contains unsupported characters");
        }
        if (userService.findByUsername(username) != null) {
            throw ScimException.conflict("uniqueness", "userName already exists");
        }

        SaikuUser u = new SaikuUser();
        u.setUsername(username);
        // SCIM-provisioned accounts authenticate through the IdP (SSO), not a local password.
        // A random high-entropy value is stored so the NOT NULL column is satisfied and no
        // guessable credential exists — the account is reachable only via SSO.
        u.setPassword(randomPassword());
        applyEmailList(u, in.emails);
        applyProfile(u, in);
        u.setRoles(new String[] {BASE_ROLE});
        SaikuUser created = userService.addUser(u);
        if (in.active != null && !in.active) {
            // addUser always inserts enabled=TRUE (the DAO hard-codes it), so an explicit
            // active=false on create has to be applied after the row exists.
            userService.setEnabled(created, false);
        }
        grantGroupsOnCreate(created, in);
        log.info("SCIM created user '{}' (id={})", created.getUsername(), created.getId());
        return toScimUser(created);
    }

    /** PUT = full replace. Absent optional attributes become absent, per RFC 7644 §3.5.1. */
    public ScimUser replaceUser(String id, ScimUser in) {
        SaikuUser u = requireUser(id);
        if (in == null) {
            throw ScimException.badRequest("invalidSyntax", "A User body is required");
        }
        if (in.userName != null && !in.userName.isBlank()) {
            String requested = Usernames.canonicalize(in.userName.trim());
            if (!Usernames.sameUser(requested, u.getUsername())) {
                SaikuUser clash = userService.findByUsername(requested);
                if (clash != null && clash.getId() != u.getId()) {
                    throw ScimException.conflict("uniqueness", "userName already exists");
                }
                if (!isSaneUsername(requested)) {
                    throw ScimException.badRequest("invalidValue", "userName contains unsupported characters");
                }
                u.setUsername(requested);
                userService.updateUser(u, false);
                // updateUser forces enabled=TRUE; restore the state the caller asked for.
                userService.setEnabled(u, in.active == null || in.active);
            }
        }
        applyProfile(u, in);
        // A PUT replaces the whole resource: an absent emails array clears the address, and a
        // present one replaces it outright.
        applyEmailList(u, in.emails);
        userService.updateProfile(u);
        userService.setEnabled(u, in.active == null || in.active);
        replaceGroupMembership(u, in.groups);
        return toScimUser(userService.findByUsername(u.getUsername()));
    }

    public ScimUser patchUser(String id, ScimPatchRequest body) {
        SaikuUser u = requireUser(id);
        List<ScimPatchRequest.Operation> ops = ScimPatch.operations(body);
        boolean profileChanged = false;
        for (ScimPatchRequest.Operation op : ops) {
            String path = ScimPatch.normalizePath(op.path, ScimSchemas.USER);
            ScimPatch.Operation operation = ScimPatch.Operation.of(op.op);
            if (path == null) {
                // Path-less op: the value is an object of attribute-path -> value.
                ScimPatch.requireValue(op);
                for (java.util.Map.Entry<String, JsonNode> e :
                        ScimPatch.fields(op.value).entrySet()) {
                    if (applyUserAttribute(u, e.getKey(), operation, e.getValue())) {
                        profileChanged = true;
                    }
                }
                continue;
            }
            if (applyUserAttribute(u, path, operation, op.value)) {
                profileChanged = true;
            }
        }

        // An active-only PATCH must not blank out the profile the IdP sent at create time, so the
        // profile/email columns are written only when this PATCH actually touched them.
        if (profileChanged) {
            userService.updateProfile(u);
        }
        // setEnabled is idempotent, and re-asserting the current value is what makes
        // "active=false" survive the write ordering above.
        userService.setEnabled(u, u.isEnabled());
        log.info("SCIM patched user '{}' ({} operations)", u.getUsername(), ops.size());
        return toScimUser(userService.findByUsername(u.getUsername()));
    }

    /**
     * SCIM DELETE on a user is a deactivation, not a row delete: an IdP routinely re-sends
     * deactivate for a user it has already deactivated, and RFC 7644 §3.6 gives the resource
     * provider latitude. The account keeps its query history and ACL ownership.
     */
    public void deactivateUser(String id) {
        SaikuUser u = requireUser(id);
        userService.setEnabled(u, false);
        log.info("SCIM deactivated user '{}' (id={})", u.getUsername(), u.getId());
    }

    /* ============================== Groups ============================== */

    public ScimListResponse listGroups(String filter, int startIndex, int count) {
        List<ScimGroupStore.GroupRecord> all = groupStore.listAll();
        List<ScimGroupStore.GroupRecord> matched = new ArrayList<>();
        for (ScimGroupStore.GroupRecord r : all) {
            if (matchesGroupFilter(r, filter)) {
                matched.add(r);
            }
        }
        int[] window = page(matched.size(), startIndex, count);
        List<ScimGroup> out = new ArrayList<>();
        for (ScimGroupStore.GroupRecord r : matched.subList(window[0], window[1])) {
            out.add(toScimGroup(r));
        }
        return new ScimListResponse(out, window[2], matched.size(), out.size());
    }

    public ScimGroup getGroup(String id) {
        return toScimGroup(requireGroup(id));
    }

    public ScimGroupStore.GroupRecord requireGroup(String id) {
        ScimGroupStore.GroupRecord r = groupStore.load(id);
        if (r == null) {
            throw ScimException.notFound("Group " + id + " not found");
        }
        return r;
    }

    public ScimGroup createGroup(ScimGroup in) {
        String displayName = requireDisplayName(in);
        if (groupStore.findByDisplayName(displayName) != null) {
            throw ScimException.conflict("uniqueness", "A group with that displayName already exists");
        }
        ScimGroupStore.GroupRecord r = groupStore.create(displayName, new ArrayList<>(), now());
        setMembers(r, memberUsernames(in.members));
        groupStore.save(r);
        log.info("SCIM created group '{}' (id={})", r.displayName, r.id);
        return toScimGroup(r);
    }

    public ScimGroup replaceGroup(String id, ScimGroup in) {
        ScimGroupStore.GroupRecord r = requireGroup(id);
        if (in == null) {
            throw ScimException.badRequest("invalidSyntax", "A Group body is required");
        }
        String displayName = requireDisplayName(in);
        renameGroup(r, displayName);
        setMembers(r, memberUsernames(in.members));
        groupStore.save(r);
        return toScimGroup(r);
    }

    public ScimGroup patchGroup(String id, ScimPatchRequest body) {
        ScimGroupStore.GroupRecord r = requireGroup(id);
        List<ScimPatchRequest.Operation> ops = ScimPatch.operations(body);
        for (ScimPatchRequest.Operation op : ops) {
            String path = ScimPatch.normalizePath(op.path, ScimSchemas.GROUP);
            if (path == null) {
                ScimPatch.requireValue(op);
                for (java.util.Map.Entry<String, JsonNode> e :
                        ScimPatch.fields(op.value).entrySet()) {
                    applyGroupAttribute(r, e.getKey(), ScimPatch.Operation.of(op.op), e.getValue());
                }
                continue;
            }
            applyGroupAttribute(r, path, ScimPatch.Operation.of(op.op), op.value);
        }
        groupStore.save(r);
        return toScimGroup(r);
    }

    /**
     * Group delete revokes the role from every member before dropping the record, so a deleted
     * IdP group cannot leave a stale grant behind in {@code USER_ROLES}.
     */
    public void deleteGroup(String id) {
        ScimGroupStore.GroupRecord r = requireGroup(id);
        setMembers(r, Set.of());
        groupStore.delete(r.id);
        log.info("SCIM deleted group '{}' (id={})", r.displayName, r.id);
    }

    /* ====================== discovery (Okta/Entra probes) ====================== */

    public int maxPageSize() {
        return 200;
    }

    /* ============================== internals ============================== */

    private List<SaikuUser> directory() {
        List<SaikuUser> users = userService.getUsers();
        return users == null ? new ArrayList<>() : users;
    }

    private SaikuUser findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        int numeric;
        try {
            numeric = Integer.parseInt(id.trim());
        } catch (NumberFormatException e) {
            // A non-numeric id can never name a Saiku account; SCIM ids are opaque, so this is a
            // plain miss rather than an error.
            return null;
        }
        for (SaikuUser u : directory()) {
            if (u != null && u.getId() == numeric) {
                return u;
            }
        }
        return null;
    }

    /** 1-based, inclusive {@code startIndex} over an already-filtered list. */
    private int[] page(int total, int startIndex, int count) {
        int start = startIndex < 1 ? 1 : startIndex;
        int size = count <= 0 ? maxPageSize() : Math.min(count, maxPageSize());
        int from = Math.min(start - 1, total);
        int to = Math.min(from + size, total);
        return new int[] {from, to, start};
    }

    private boolean matchesUserFilter(SaikuUser u, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        for (String clause : filter.split("(?i)\\s+and\\s+")) {
            if (!matchesUserClause(u, clause.trim())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesUserClause(SaikuUser u, String clause) {
        String[] parts = clause.split("\\s+");
        if (parts.length < 3) {
            throw ScimException.badRequest("invalidFilter", "Unsupported filter: " + clause);
        }
        String attr = parts[0].toLowerCase(Locale.ROOT);
        String op = parts[1].toLowerCase(Locale.ROOT);
        String raw = unquote(String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)));
        switch (attr) {
            case "username":
                if ("eq".equals(op)) {
                    return Usernames.sameUser(raw, u.getUsername());
                }
                if ("sw".equals(op)) {
                    return u.getUsername() != null
                            && u.getUsername().toLowerCase(Locale.ROOT).startsWith(raw.toLowerCase(Locale.ROOT));
                }
                break;
            case "id":
                if ("eq".equals(op)) {
                    return String.valueOf(u.getId()).equals(raw);
                }
                break;
            case "displayname":
                if ("eq".equals(op)) {
                    return u.getDisplayName() != null && u.getDisplayName().equalsIgnoreCase(raw);
                }
                break;
            case "active":
                if ("eq".equals(op)) {
                    return u.isEnabled() == Boolean.parseBoolean(raw);
                }
                break;
            default:
                // externalId is not persisted (see class javadoc) — refusing is honest,
                // returning "no match" would be a silent wrong answer.
                throw ScimException.badRequest("invalidFilter", "Unsupported filter attribute: " + attr);
        }
        throw ScimException.badRequest("invalidFilter", "Unsupported filter: " + clause);
    }

    private boolean matchesGroupFilter(ScimGroupStore.GroupRecord r, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        for (String clause : filter.split("(?i)\\s+and\\s+")) {
            String[] parts = clause.trim().split("\\s+");
            if (parts.length < 3) {
                throw ScimException.badRequest("invalidFilter", "Unsupported filter: " + clause);
            }
            String attr = parts[0].toLowerCase(Locale.ROOT);
            String op = parts[1].toLowerCase(Locale.ROOT);
            String raw = unquote(String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)));
            boolean hit;
            if ("displayname".equals(attr) && "eq".equals(op)) {
                hit = r.displayName != null && r.displayName.equalsIgnoreCase(raw);
            } else if ("id".equals(attr) && "eq".equals(op)) {
                hit = r.id.equals(raw);
            } else {
                throw ScimException.badRequest("invalidFilter", "Unsupported filter attribute: " + attr);
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * The {@code emails} array maps onto Saiku's single EMAIL column: the primary entry wins, else
     * the first. A null array clears it (PUT semantics); an empty array is a no-op so a connector
     * that sends {@code []} alongside an otherwise-good payload does not wipe the address.
     */
    private void applyEmailList(SaikuUser u, List<ScimUser.Email> emails) {
        if (emails == null) {
            u.setEmail(null);
            return;
        }
        if (emails.isEmpty()) {
            return;
        }
        ScimUser.Email chosen = null;
        for (ScimUser.Email e : emails) {
            if (e == null || e.value == null || e.value.isBlank()) {
                continue;
            }
            if (Boolean.TRUE.equals(e.primary)) {
                chosen = e;
                break;
            }
            if (chosen == null) {
                chosen = e;
            }
        }
        if (chosen == null) {
            return;
        }
        if (chosen.value.length() > 100) {
            throw ScimException.badRequest("invalidValue", "email is too long");
        }
        u.setEmail(chosen.value);
    }

    private void applyProfile(SaikuUser u, ScimUser in) {
        if (in.name != null) {
            u.setGivenName(in.name.givenName);
            u.setFamilyName(in.name.familyName);
        }
        u.setDisplayName(
                in.displayName != null
                        ? in.displayName
                        : (in.name != null && in.name.bestDisplay() != null ? in.name.bestDisplay() : u.getUsername()));
    }

    /**
     * Applies a create-time {@code groups} array. Okta and Entra send group membership as the
     * group's <i>id</i>; a connector configured to send display names works too, so both resolve.
     *
     * <p>The user is also recorded in the group's member list: granting the role without the
     * membership would make {@code GET /Users/{id}} report no groups for a user the IdP just told
     * us is in one.
     */
    private void grantGroupsOnCreate(SaikuUser u, ScimUser in) {
        if (in.groups == null || in.groups.isEmpty()) {
            return;
        }
        for (ScimUser.Member m : in.groups) {
            ScimGroupStore.GroupRecord r = resolveGroupRecord(m == null ? null : m.value);
            if (r == null) {
                log.warn(
                        "SCIM create referenced unknown group '{}' — user created without it",
                        m == null ? null : m.value);
                continue;
            }
            grantRole(u, r.displayName);
            if (r.members == null) {
                r.members = new ArrayList<>();
            }
            if (!r.members.contains(u.getUsername())) {
                r.members.add(u.getUsername());
                groupStore.save(r);
            }
        }
    }

    private void grantRole(SaikuUser u, String role) {
        if (role == null || role.isBlank()) {
            return;
        }
        Set<String> roles = new LinkedHashSet<>(rolesOf(u));
        roles.add(role);
        userService.replaceRoles(u, roles.toArray(new String[0]));
    }

    private void revokeRole(SaikuUser u, String role) {
        if (role == null || role.isBlank()) {
            return;
        }
        Set<String> roles = new LinkedHashSet<>(rolesOf(u));
        roles.remove(role);
        if (roles.isEmpty()) {
            roles.add(BASE_ROLE);
        }
        userService.replaceRoles(u, roles.toArray(new String[0]));
    }

    private List<String> rolesOf(SaikuUser u) {
        String[] roles = u.getRoles();
        if (roles == null || roles.length == 0) {
            String[] fresh = userService.getRoles(u);
            roles = fresh == null ? new String[0] : fresh;
            u.setRoles(roles);
        }
        List<String> out = new ArrayList<>();
        for (String r : roles) {
            if (r != null && !r.isBlank()) {
                out.add(r);
            }
        }
        if (out.isEmpty()) {
            out.add(BASE_ROLE);
        }
        return out;
    }

    /**
     * Reconciles a user's roles against the full SCIM group list. IdP-driven group membership is
     * authoritative, so roles this service manages are replaced wholesale while the base
     * {@code ROLE_USER} and any role the SCIM surface never touched are preserved.
     */
    private void replaceGroupMembership(SaikuUser u, List<ScimUser.Member> members) {
        if (members == null) {
            return;
        }
        Set<String> managed = managedRoleNames();
        Set<String> desired = new LinkedHashSet<>();
        for (ScimUser.Member m : members) {
            String role = resolveGroupRole(m == null ? null : m.value);
            if (role != null) {
                desired.add(role);
            }
        }
        Set<String> next = new LinkedHashSet<>();
        for (String r : rolesOf(u)) {
            if (!managed.contains(r) || desired.contains(r)) {
                next.add(r);
            }
        }
        next.addAll(desired);
        if (!next.isEmpty()) {
            next.add(BASE_ROLE);
        }
        userService.replaceRoles(u, next.toArray(new String[0]));
    }

    /** Group role names this server manages through SCIM. */
    private Set<String> managedRoleNames() {
        Set<String> out = new LinkedHashSet<>();
        for (ScimGroupStore.GroupRecord r : groupStore.listAll()) {
            if (r.displayName != null) {
                out.add(r.displayName);
            }
        }
        return out;
    }

    private String resolveGroupRole(String value) {
        ScimGroupStore.GroupRecord r = resolveGroupRecord(value);
        return r == null ? null : r.displayName;
    }

    /** A group value may be the SCIM group id or, on connectors configured that way, the name. */
    private ScimGroupStore.GroupRecord resolveGroupRecord(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        ScimGroupStore.GroupRecord r = groupStore.load(value.trim());
        if (r == null) {
            r = groupStore.findByDisplayName(value.trim());
        }
        return r;
    }

    /** Resolves a PATCH/PUT member list into canonical usernames, ignoring unknown members. */
    private Set<String> memberUsernames(List<ScimGroup.Member> members) {
        Set<String> out = new LinkedHashSet<>();
        if (members == null) {
            return out;
        }
        for (ScimGroup.Member m : members) {
            if (m == null) {
                continue;
            }
            SaikuUser u = findById(m.value);
            if (u == null) {
                u = userService.findByUsername(m.value);
            }
            if (u == null) {
                log.warn("SCIM group member '{}' does not resolve to a Saiku user — skipped", m.value);
                continue;
            }
            out.add(u.getUsername());
        }
        return out;
    }

    /**
     * Writes a group's member list: members that joined get the group's role, members that left
     * lose it, and the group's record is updated. A member that no longer exists in the directory
     * is dropped from the record rather than resurrecting a deleted account's grant.
     */
    private void setMembers(ScimGroupStore.GroupRecord r, Set<String> desired) {
        List<String> current = r.members == null ? new ArrayList<>() : new ArrayList<>(r.members);
        for (String username : desired) {
            SaikuUser u = userService.findByUsername(username);
            if (u != null) {
                grantRole(u, r.displayName);
            }
        }
        for (String username : current) {
            if (!desired.contains(username)) {
                SaikuUser u = userService.findByUsername(username);
                if (u != null) {
                    revokeRole(u, r.displayName);
                }
            }
        }
        List<String> surviving = new ArrayList<>();
        for (String username : desired) {
            if (userService.findByUsername(username) != null) {
                surviving.add(username);
            }
        }
        r.members = surviving;
    }

    private String requireDisplayName(ScimGroup in) {
        if (in == null || in.displayName == null || in.displayName.isBlank()) {
            throw ScimException.badRequest("invalidValue", "displayName is required");
        }
        String name = in.displayName.trim();
        if (name.length() > MAX_ROLE_LENGTH) {
            throw ScimException.badRequest(
                    "invalidValue", "displayName must be at most " + MAX_ROLE_LENGTH + " characters");
        }
        return name;
    }

    /**
     * @return true when the attribute was recognised and applied. A {@code false} return for an
     *     unrecognised path is an error, not a silent skip: silently dropping
     *     {@code active=false} would leave a deprovisioned user active.
     */
    private boolean applyUserAttribute(SaikuUser u, String path, ScimPatch.Operation op, JsonNode value) {
        switch (path) {
            case "active":
                u.setEnabled(ScimPatch.readBoolean(op, value));
                return true;
            case "username":
                return applyUserName(u, op, value);
            case "name.givenname":
                u.setGivenName(ScimPatch.readString(op, value));
                return true;
            case "name.familyname":
                u.setFamilyName(ScimPatch.readString(op, value));
                return true;
            case "name.formatted":
                u.setDisplayName(ScimPatch.readString(op, value));
                return true;
            case "displayname":
                u.setDisplayName(ScimPatch.readString(op, value));
                return true;
            case "emails":
            case "emails[type eq \"work\"].value":
            case "emails[type eq \"work\"]":
                applyEmail(u, op, value);
                return true;
            case "groups":
                // Group membership is authoritative through /Groups; an inline "groups" write on
                // /Users is accepted only in the "no members to reconcile" form.
                if (ScimPatch.Operation.REMOVE.equals(op)) {
                    replaceGroupMembership(u, List.of());
                }
                return true;
            default:
                throw ScimException.badRequest("invalidPath", "Unsupported attribute: " + path);
        }
    }

    private boolean applyUserName(SaikuUser u, ScimPatch.Operation op, JsonNode value) {
        if (ScimPatch.Operation.REMOVE.equals(op)) {
            throw ScimException.badRequest("mutability", "userName cannot be removed");
        }
        String requested =
                Usernames.canonicalize(ScimPatch.readString(op, value).trim());
        if (!isSaneUsername(requested)) {
            throw ScimException.badRequest("invalidValue", "userName contains unsupported characters");
        }
        if (Usernames.sameUser(requested, u.getUsername())) {
            return true;
        }
        SaikuUser clash = userService.findByUsername(requested);
        if (clash != null && clash.getId() != u.getId()) {
            throw ScimException.conflict("uniqueness", "userName already exists");
        }
        u.setUsername(requested);
        userService.updateUser(u, false);
        return true;
    }

    private void applyEmail(SaikuUser u, ScimPatch.Operation op, JsonNode value) {
        if (ScimPatch.Operation.REMOVE.equals(op)) {
            u.setEmail(null);
            return;
        }
        String email = ScimPatch.readString(op, value);
        if (email != null && !email.isBlank() && email.length() > 100) {
            throw ScimException.badRequest("invalidValue", "email is too long");
        }
        u.setEmail(email);
    }

    private void applyGroupAttribute(
            ScimGroupStore.GroupRecord r, String path, ScimPatch.Operation op, JsonNode value) {
        switch (path) {
            case "displayname":
                if (ScimPatch.Operation.REMOVE.equals(op)) {
                    throw ScimException.badRequest("mutability", "displayName cannot be removed");
                }
                String name = ScimPatch.readString(op, value).trim();
                if (name.isEmpty() || name.length() > MAX_ROLE_LENGTH) {
                    throw ScimException.badRequest("invalidValue", "Invalid displayName");
                }
                renameGroup(r, name);
                return;
            case "members":
                applyMembers(r, op, value);
                return;
            default:
                throw ScimException.badRequest("invalidPath", "Unsupported group attribute: " + path);
        }
    }

    /**
     * Renames a group, moving the role grant with it: a displayName <i>is</i> the role name, so
     * leaving the old grant behind would hand every member a role no group owns (and the new
     * group would not grant anything). Shared by PUT and PATCH so the two cannot diverge.
     */
    private void renameGroup(ScimGroupStore.GroupRecord r, String name) {
        if (name.equals(r.displayName)) {
            return;
        }
        ScimGroupStore.GroupRecord clash = groupStore.findByDisplayName(name);
        if (clash != null && !clash.id.equals(r.id)) {
            throw ScimException.conflict("uniqueness", "A group with that displayName already exists");
        }
        for (String username : new ArrayList<>(r.members == null ? List.<String>of() : r.members)) {
            SaikuUser u = userService.findByUsername(username);
            if (u != null) {
                revokeRole(u, r.displayName);
                grantRole(u, name);
            }
        }
        r.displayName = name;
    }

    private void applyMembers(ScimGroupStore.GroupRecord r, ScimPatch.Operation op, JsonNode value) {
        Set<String> desired = new LinkedHashSet<>(r.members == null ? List.of() : r.members);
        List<ScimGroup.Member> supplied = ScimPatch.readMembers(op, value);
        if (ScimPatch.Operation.REMOVE.equals(op)) {
            for (String username : memberUsernames(supplied)) {
                desired.remove(username);
            }
        } else {
            for (String username : memberUsernames(supplied)) {
                desired.add(username);
            }
        }
        setMembers(r, desired);
    }

    private ScimUser toScimUser(SaikuUser u) {
        ScimUser out = new ScimUser();
        out.id = String.valueOf(u.getId());
        out.userName = u.getUsername();
        if (u.getGivenName() != null || u.getFamilyName() != null) {
            out.name = new ScimUser.Name(u.getGivenName(), u.getFamilyName());
        }
        out.displayName = u.getDisplayName();
        if (u.getEmail() != null && !u.getEmail().isBlank()) {
            out.emails = List.of(new ScimUser.Email(u.getEmail(), "work", true));
        }
        out.active = u.isEnabled();
        out.groups = groupMemberships(u);
        out.meta = new ScimUser.Meta();
        out.meta.resourceType = ScimSchemas.RESOURCE_TYPE_USER;
        out.meta.location = ScimSchemas.BASE_PATH + "/Users/" + out.id;
        return out;
    }

    private List<ScimUser.Member> groupMemberships(SaikuUser u) {
        List<ScimUser.Member> out = new ArrayList<>();
        for (ScimGroupStore.GroupRecord r : groupStore.listAll()) {
            if (r.members != null && r.members.contains(u.getUsername())) {
                out.add(new ScimUser.Member(r.id, r.displayName));
            }
        }
        return out;
    }

    private ScimGroup toScimGroup(ScimGroupStore.GroupRecord r) {
        ScimGroup g = new ScimGroup();
        g.id = r.id;
        g.displayName = r.displayName;
        g.members = new ArrayList<>();
        for (String username : r.members == null ? List.<String>of() : r.members) {
            SaikuUser u = userService.findByUsername(username);
            g.members.add(new ScimGroup.Member(u == null ? username : String.valueOf(u.getId()), username));
        }
        g.meta = new ScimGroup.Meta();
        g.meta.created = iso(r.created);
        g.meta.lastModified = iso(r.lastModified);
        g.meta.location = ScimSchemas.BASE_PATH + "/Groups/" + g.id;
        return g;
    }

    /**
     * Usernames reach {@code USERS} as a login identity and into {@code /homes/<user>} paths, so
     * the accepted set is deliberately narrow: no path separators, no traversal, no whitespace.
     * IdP-generated names (Okta {@code jsmith@example.com} → {@code jsmith_example.com} depending
     * on the connector's mapping) are honoured as configured by the operator; this only rejects
     * values that could escape the user's home.
     */
    static boolean isSaneUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 45) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '@' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return !username.contains("..");
    }

    private static String randomPassword() {
        byte[] buf = new byte[24];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    static String iso(long millis) {
        if (millis <= 0) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(millis).toString();
    }
}
