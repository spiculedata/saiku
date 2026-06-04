package org.saiku.service.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.saiku.service.datasource.DatasourceService;

/**
 * Unit coverage for {@link CommentService} (issue #942) — append/list/soft-delete
 * mechanics, per-tile scoping, @-mention parsing, the canRead gate, and the
 * traversal-flattened storage path. Uses an in-memory {@link DatasourceService}
 * stub so no repository/filesystem is needed.
 */
public class CommentServiceTest {

    /** In-memory internal-file store; getFileData controls the canRead gate. */
    private static class FakeDs extends DatasourceService {
        final Map<String, String> internal = new HashMap<>();
        boolean dashboardReadable = true;

        @Override
        public String saveInternalFile(String path, String content, String type) {
            internal.put(path, content);
            return "Save Okay";
        }

        @Override
        public String getInternalFileData(String path) {
            return internal.get(path);
        }

        @Override
        public String getFileData(String path, String username, List<String> roles) {
            return dashboardReadable ? "{\"id\":\"x\"}" : null;
        }
    }

    private FakeDs ds;
    private CommentService svc;

    private static final String DASH = "dashboards/foo.saikudash";

    @Before
    public void setUp() {
        ds = new FakeDs();
        svc = new CommentService();
        svc.setDatasourceService(ds);
    }

    @Test
    public void add_then_list_scoped_by_tile() {
        svc.add(DASH, "t1", "admin", "hello on tile one");
        svc.add(DASH, "t2", "bob", "different tile");
        svc.add(DASH, "t1", "admin", "second on t1");

        List<Comment> t1 = svc.list(DASH, "t1");
        assertEquals(2, t1.size());
        assertEquals("hello on tile one", t1.get(0).body);
        assertEquals("second on t1", t1.get(1).body); // insertion order preserved
        assertEquals(1, svc.list(DASH, "t2").size());
        assertEquals(0, svc.list(DASH, "tX").size());
    }

    @Test
    public void soft_delete_hides_from_list_but_keeps_order() {
        Comment a = svc.add(DASH, "t1", "admin", "first");
        svc.add(DASH, "t1", "admin", "second");
        assertTrue(svc.softDelete(DASH, a.id));

        List<Comment> live = svc.list(DASH, "t1");
        assertEquals("deleted one is hidden", 1, live.size());
        assertEquals("second", live.get(0).body);
        // The deleted row is still on disk (findById sees it) so order is kept.
        assertTrue(svc.findById(DASH, a.id).deleted);
        assertFalse("deleting unknown id is false", svc.softDelete(DASH, "nope"));
    }

    @Test
    public void mentions_are_parsed_from_body() {
        Comment c = svc.add(DASH, "t1", "admin", "ping @bob and @krishna, also @bob again");
        assertEquals(List.of("bob", "krishna"), c.mentions); // de-duped, in order
        assertEquals(List.of(), CommentService.parseMentions("no mentions here"));
        assertEquals(List.of("a.b-c_d"), CommentService.parseMentions("hi @a.b-c_d!"));
    }

    @Test
    public void missing_file_is_empty_thread() {
        assertEquals(0, svc.list("dashboards/never.saikudash", "t1").size());
        assertNull(svc.findById("dashboards/never.saikudash", "anything"));
    }

    @Test
    public void canRead_gate() {
        ds.dashboardReadable = true;
        assertTrue(svc.canReadDashboard(DASH, "admin", List.of("ROLE_ADMIN")));
        ds.dashboardReadable = false;
        assertFalse(svc.canReadDashboard(DASH, "bob", List.of("ROLE_USER")));
        // Non-dashboard paths are rejected outright.
        assertFalse(svc.canReadDashboard("dashboards/foo.saiku", "admin", List.of("ROLE_ADMIN")));
        assertFalse(svc.canReadDashboard(null, "admin", List.of("ROLE_ADMIN")));
    }

    @Test
    public void comments_path_is_flattened_and_namespaced() {
        // A single flat file at the datadir root; separators / traversal chars
        // collapse to underscores (the internal writer won't mkdir a subdir).
        assertEquals(
                "saiku-comments-dashboards_foo.saikudash.jsonl",
                CommentService.commentsPath("dashboards/foo.saikudash"));
        String evil = CommentService.commentsPath("../../etc/passwd");
        assertTrue(evil.startsWith("saiku-comments-"));
        assertFalse("no parent-dir hops survive", evil.contains(".."));
        assertFalse("no separators survive", evil.contains("/") || evil.contains("\\"));
    }
}
