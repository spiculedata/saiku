/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * saiku#1904 — the {@code switch} on {@link AclType} in {@link Acl2#getMethods} used to fall
 * through to {@link AclMethod#WRITE} for PUBLIC (and any un-cased type), which made every PUBLIC
 * node — the seeded {@code /datasources} and {@code /legacyreports} included — writable by every
 * authenticated user. PUBLIC now means readable; WRITE needs an explicit grant, ownership, or an
 * admin role. PRIVATE and SECURED semantics are pinned unchanged alongside.
 */
public class Acl2DefaultMethodTest {

    private static final List<String> USER = Collections.singletonList("ROLE_USER");
    private static final List<String> ADMIN = Arrays.asList("ROLE_USER", "ROLE_ADMIN");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File folder;

    @Before
    public void setUp() throws Exception {
        folder = tmp.newFolder("shared");
    }

    @Test
    public void publicNode_isReadableButNotWritable_forNonAdmin() {
        Acl2 acl = seeded(AclType.PUBLIC, "admin", null);
        List<AclMethod> methods = acl.getMethods(folder, "bob", USER);
        assertTrue("PUBLIC must grant READ", methods.contains(AclMethod.READ));
        assertFalse("PUBLIC must NOT grant WRITE (saiku#1904)", methods.contains(AclMethod.WRITE));
        assertFalse("PUBLIC must NOT grant GRANT", methods.contains(AclMethod.GRANT));
        assertTrue(acl.canRead(folder, "bob", USER));
        assertFalse(acl.canWrite(folder, "bob", USER));
    }

    @Test
    public void publicNode_adminStillGetsEverything() {
        Acl2 acl = seeded(AclType.PUBLIC, "admin", null);
        assertTrue(acl.canWrite(folder, "root", ADMIN));
        assertTrue(acl.canGrant(folder, "root", ADMIN));
    }

    @Test
    public void defaultConstructedEntry_isPublic_andResolvesToRead() {
        // AclEntry() defaults to PUBLIC — the shape an acl.json with no explicit type produces.
        Acl2 writer = new Acl2(folder);
        writer.addEntry(folder.getPath(), new AclEntry());
        writer.serialize(folder);
        Acl2 acl = new Acl2(folder);
        acl.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        assertTrue(acl.canRead(folder, "bob", USER));
        assertFalse(acl.canWrite(folder, "bob", USER));
    }

    @Test
    public void privateNode_ownerGetsGrant_othersGetNothing() {
        Acl2 acl = seeded(AclType.PRIVATE, "alice", null);
        assertTrue(acl.canWrite(folder, "alice", USER));
        assertTrue(acl.canGrant(folder, "alice", USER));
        assertFalse(acl.canRead(folder, "bob", USER));
        assertFalse(acl.canWrite(folder, "bob", USER));
        assertTrue("admin override intact", acl.canWrite(folder, "root", ADMIN));
    }

    @Test
    public void securedNode_explicitRoleWriteGrant_isHonoured() {
        Map<String, List<AclMethod>> roles = new HashMap<>();
        roles.put("ROLE_USER", Arrays.asList(AclMethod.WRITE, AclMethod.READ));
        Acl2 acl = seeded(AclType.SECURED, "admin", roles);
        assertTrue(acl.canWrite(folder, "bob", USER));
        assertFalse(acl.canGrant(folder, "bob", USER));
    }

    @Test
    public void securedNode_withoutGrant_denies() {
        Map<String, List<AclMethod>> roles = new HashMap<>();
        roles.put("ROLE_ADMIN", Arrays.asList(AclMethod.WRITE, AclMethod.READ, AclMethod.GRANT));
        Acl2 acl = seeded(AclType.SECURED, "admin", roles);
        assertFalse(acl.canRead(folder, "bob", USER));
        assertFalse(acl.canWrite(folder, "bob", USER));
        assertTrue(acl.canWrite(folder, "root", ADMIN));
    }

    @Test
    public void childOfPublicFolder_inheritsReadNotWrite() throws Exception {
        seeded(AclType.PUBLIC, "admin", null);
        File child = new File(folder, "report.saiku");
        assertTrue(child.createNewFile());
        Acl2 acl = new Acl2(child);
        acl.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        assertTrue(acl.canRead(child, "bob", USER));
        assertFalse("inheritance must not widen to WRITE", acl.canWrite(child, "bob", USER));
    }

    /** Write an entry for {@link #folder} to its acl.json and return a fresh reader over it. */
    private Acl2 seeded(AclType type, String owner, Map<String, List<AclMethod>> roles) {
        Acl2 writer = new Acl2(folder);
        writer.addEntry(folder.getPath(), new AclEntry(owner, type, roles, null));
        writer.serialize(folder);
        Acl2 reader = new Acl2(folder);
        reader.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        return reader;
    }
}
