package org.saiku.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.service.user.UserService;

/**
 * Default-ACL coverage for saiku#948. Verifies that
 * {@link FilesystemRepositoryManager#start} seeds explicit ACLs on every
 * shared write surface — not just {@code /homes/} — and that each ACL lands
 * on its own folder rather than overwriting {@code /homes/acl.json}
 * repeatedly (the latent bug the seed refactor fixed).
 */
public class FilesystemRepositoryManagerSeedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FilesystemRepositoryManager manager;
    private File datadir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() throws Exception {
        resetSingleton();

        datadir = tmp.newFolder("repo");
        manager = newManager(datadir.getAbsolutePath());

        UserService us = new UserService();
        us.setAdminRoles(Collections.singletonList("ROLE_ADMIN"));
        injectUserService(manager, us);

        // start() drives the seedSkeleton() helper.
        manager.start(us);
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
    }

    @Test
    public void homes_seeded_with_SECURED_acl() throws Exception {
        JsonNode entry = readEntry("homes");
        assertEquals("SECURED", entry.get("type").asText());
        assertEquals("admin", entry.get("owner").asText());
    }

    @Test
    public void datasources_seeded_with_PUBLIC_admin_grant() throws Exception {
        JsonNode entry = readEntry("datasources");
        assertEquals("PUBLIC", entry.get("type").asText());
        JsonNode roles = entry.get("roles");
        assertTrue("ROLE_ADMIN grant must be present on /datasources/", roles.has("ROLE_ADMIN"));
    }

    @Test
    public void dashboards_seeded_with_SECURED_admin_grant() throws Exception {
        JsonNode entry = readEntry("dashboards");
        assertEquals("SECURED", entry.get("type").asText());
        JsonNode roles = entry.get("roles");
        assertTrue("ROLE_ADMIN grant must be present on /dashboards/", roles.has("ROLE_ADMIN"));
    }

    @Test
    public void queries_seeded_with_SECURED_admin_grant() throws Exception {
        JsonNode entry = readEntry("queries");
        assertEquals("SECURED", entry.get("type").asText());
        JsonNode roles = entry.get("roles");
        assertTrue("ROLE_ADMIN grant must be present on /queries/", roles.has("ROLE_ADMIN"));
    }

    /**
     * Regression for the latent bug: prior code held one {@code File n} for
     * {@code /homes/} and re-serialised every subsequent folder's ACL onto
     * that same reference. After the fix each acl.json must live in its
     * own folder, not all in /homes/.
     */
    @Test
    public void each_seeded_folder_has_its_own_acl_json() {
        File workspace = new File(datadir, "unknown");
        assertTrue("/homes/acl.json missing", new File(workspace, "homes/acl.json").exists());
        assertTrue(
                "/datasources/acl.json missing — pre-fix this overwrote /homes/acl.json",
                new File(workspace, "datasources/acl.json").exists());
        assertTrue("/dashboards/acl.json missing", new File(workspace, "dashboards/acl.json").exists());
        assertTrue("/queries/acl.json missing", new File(workspace, "queries/acl.json").exists());
        assertTrue("/legacyreports/acl.json missing", new File(workspace, "legacyreports/acl.json").exists());
    }

    private JsonNode readEntry(String folder) throws Exception {
        File aclJson = new File(datadir, "unknown/" + folder + "/acl.json");
        assertTrue("acl.json missing for /" + folder + "/", aclJson.exists());
        JsonNode root = mapper.readTree(aclJson);
        // Acl2.serialize writes a single-key map keyed by the folder's absolute path.
        JsonNode entry = root.elements().next();
        assertTrue("acl.json for /" + folder + "/ has no entry payload", entry != null && !entry.isMissingNode());
        return entry;
    }

    private static FilesystemRepositoryManager newManager(String path) throws Exception {
        Constructor<FilesystemRepositoryManager> ctor = FilesystemRepositoryManager.class.getDeclaredConstructor(
                String.class, String.class, ScopedRepo.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "ROLE_USER", new ScopedRepo(), false);
    }

    private static void injectUserService(FilesystemRepositoryManager mgr, UserService us) throws Exception {
        Field f = FilesystemRepositoryManager.class.getDeclaredField("userService");
        f.setAccessible(true);
        f.set(mgr, us);
    }

    private static void resetSingleton() throws Exception {
        Field ref = FilesystemRepositoryManager.class.getDeclaredField("ref");
        ref.setAccessible(true);
        ref.set(null, null);
    }
}
