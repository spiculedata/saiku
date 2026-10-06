/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.FileSessionDataStore;
import org.eclipse.jetty.session.SessionHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.saiku.launcher.SaikuLauncher.ServeCommand;

/**
 * saiku#1859 — the launcher must write a session to disk as it is USED, not only when the cache
 * shuts down.
 *
 * <p>Jetty's default {@code NEVER_EVICT} cache only flushes on shutdown, so a {@code kill -9}, a
 * crash or a container stop lost every session that had not been written yet. These assertions
 * pin the wiring that makes {@code saiku-home/sessions/} a truthful record of who is signed in.
 */
public class SessionPersistenceWiringTest {

    @Rule
    public TemporaryFolder home = new TemporaryFolder();

    @Test
    public void configureSessionPersistence_writes_sessions_to_the_sessions_directory() throws Exception {
        File sessionsDir = new File(home.getRoot(), "sessions");
        SessionHandler sessionHandler = new SessionHandler();

        ServeCommand.configureSessionPersistence(sessionHandler, sessionsDir);

        assertTrue("the sessions directory must be created", sessionsDir.isDirectory());
        assertTrue(
                "sessions must be backed by a file store, not memory",
                sessionHandler.getSessionCache() instanceof DefaultSessionCache);
        DefaultSessionCache cache = (DefaultSessionCache) sessionHandler.getSessionCache();
        assertTrue(
                "sessions must be persisted with a FileSessionDataStore",
                cache.getSessionDataStore() instanceof FileSessionDataStore);
        assertEquals(sessionsDir, ((FileSessionDataStore) cache.getSessionDataStore()).getStoreDir());
    }

    @Test
    public void configureSessionPersistence_saves_as_the_session_is_used_not_only_at_shutdown() throws Exception {
        SessionHandler sessionHandler = new SessionHandler();

        ServeCommand.configureSessionPersistence(sessionHandler, new File(home.getRoot(), "sessions"));

        DefaultSessionCache cache = (DefaultSessionCache) sessionHandler.getSessionCache();
        assertTrue(
                "a session must be written when it is created, so an abrupt stop cannot lose it",
                cache.isSaveOnCreate());
        assertTrue(
                "a session must be flushed at response commit, not left dirty in memory",
                cache.isFlushOnResponseCommit());
    }

    @Test
    public void configureSessionPersistence_keeps_the_seven_day_idle_window() throws Exception {
        SessionHandler sessionHandler = new SessionHandler();

        ServeCommand.configureSessionPersistence(sessionHandler, new File(home.getRoot(), "sessions"));

        assertEquals(7 * 24 * 60 * 60, sessionHandler.getMaxInactiveInterval());
    }

    @Test
    public void configureSessionPersistence_is_idempotent_over_a_restarted_home() throws Exception {
        // Second boot against an existing home: re-wiring must not blow away what is already
        // persisted there (that is the whole point of the store surviving a restart).
        File sessionsDir = home.newFolder("sessions");
        Files.write(
                new File(sessionsDir, "1791377120210___0.0.0.0_node0uq4kxp5mrz9w13fzu7hvja1ho1").toPath(),
                "persisted".getBytes("UTF-8"));
        File marker = new File(sessionsDir, "1791377120210___0.0.0.0_node0uq4kxp5mrz9w13fzu7hvja1ho1");

        ServeCommand.configureSessionPersistence(new SessionHandler(), sessionsDir);

        assertTrue("an existing session file must survive a re-wire", marker.isFile());
    }
}
