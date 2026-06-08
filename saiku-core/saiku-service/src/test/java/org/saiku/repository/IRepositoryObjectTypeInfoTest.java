/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.junit.Test;

/**
 * saiku#1165 (audit-3) — {@link IRepositoryObject} uses polymorphic Jackson
 * (de)serialisation. It was previously annotated {@code @JsonTypeInfo(use = Id.CLASS)},
 * which embeds a fully-qualified class name in the {@code @class} property and turns the
 * type into a polymorphic-deserialisation gadget shape. Hardened to
 * {@code use = Id.NAME} so the {@code @JsonSubTypes} mapping becomes a closed allow-list:
 * the only acceptable type ids are {@code "folder"} and {@code "file"}.
 */
public class IRepositoryObjectTypeInfoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void folderSerialisesWithNameTypeIdNotFqcn() throws Exception {
        RepositoryFolderObject folder =
                new RepositoryFolderObject("docs", "/docs", "/docs", null, Collections.emptyList());

        String json = mapper.writeValueAsString(folder);

        assertTrue("type id must be the allow-list name 'folder': " + json, json.contains("\"@class\":\"folder\""));
        assertFalse(
                "the fully-qualified class name must not leak into the type id: " + json,
                json.contains("org.saiku.repository.RepositoryFolderObject"));
    }

    @Test
    public void fileSerialisesWithNameTypeIdNotFqcn() throws Exception {
        RepositoryFileObject file =
                new RepositoryFileObject("report.saiku", "/report.saiku", "saiku", "/report.saiku", null);

        String json = mapper.writeValueAsString(file);

        assertTrue("type id must be the allow-list name 'file': " + json, json.contains("\"@class\":\"file\""));
        assertFalse(
                "the fully-qualified class name must not leak into the type id: " + json,
                json.contains("org.saiku.repository.RepositoryFileObject"));
    }
}
