/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.web.scim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;

/**
 * saiku#1438 — the real-world PATCH dialects Okta and Entra actually send. Each case here is a
 * body captured from one of the two connectors; they are the reason {@link ScimPatch} exists as a
 * separate, separately-tested translation layer.
 */
public class ScimPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad fixture: " + s, e);
        }
    }

    @Test
    public void normalisesOktaPlainPath() {
        assertEquals("active", ScimPatch.normalizePath("active", ScimSchemas.USER));
    }

    @Test
    public void normalisesEntraUrnQualifiedPath() {
        // Entra sends the full URN prefix on every path.
        assertEquals(
                "username",
                ScimPatch.normalizePath("urn:ietf:params:scim:schemas:core:2.0:User:userName", ScimSchemas.USER));
    }

    @Test
    public void normalisesLeadingSlashAndCasing() {
        assertEquals("active", ScimPatch.normalizePath("/Active", ScimSchemas.USER));
    }

    @Test
    public void keepsFilterQualifiedEmailPath() {
        assertEquals("emails[type eq \"work\"]", ScimPatch.normalizePath("emails[type eq \"work\"]", ScimSchemas.USER));
    }

    @Test
    public void absentPathIsNull() {
        assertEquals(null, ScimPatch.normalizePath(null, ScimSchemas.USER));
        assertEquals(null, ScimPatch.normalizePath("  ", ScimSchemas.USER));
    }

    @Test
    public void operationCasingIsIgnored() {
        // Okta sends "replace"; Entra sends "Replace"; the RFC says case-insensitive.
        assertEquals(ScimPatch.Operation.REPLACE, ScimPatch.Operation.of("replace"));
        assertEquals(ScimPatch.Operation.REPLACE, ScimPatch.Operation.of("Replace"));
        assertEquals(ScimPatch.Operation.REMOVE, ScimPatch.Operation.of("REMOVE"));
        assertEquals(ScimPatch.Operation.ADD, ScimPatch.Operation.of(" add "));
    }

    @Test
    public void unknownOperationFailsClosed() {
        try {
            ScimPatch.Operation.of("upsert");
            fail("an unknown op must not be silently ignored — it would drop a deactivate");
        } catch (ScimException e) {
            assertEquals(400, e.getResponse().getStatus());
            assertEquals("invalidSyntax", e.getScimType());
        }
    }

    @Test
    public void nullOperationFailsClosed() {
        try {
            ScimPatch.Operation.of(null);
            fail("a missing op must be rejected");
        } catch (ScimException e) {
            assertEquals("invalidSyntax", e.getScimType());
        }
    }

    @Test
    public void readBooleanAcceptsScalarAndValueEnvelope() {
        assertEquals(false, ScimPatch.readBoolean(ScimPatch.Operation.REPLACE, json("false")));
        assertEquals(true, ScimPatch.readBoolean(ScimPatch.Operation.REPLACE, json("true")));
        assertEquals(false, ScimPatch.readBoolean(ScimPatch.Operation.REPLACE, json("\"false\"")));
        assertEquals(true, ScimPatch.readBoolean(ScimPatch.Operation.REPLACE, json("{\"value\":true}")));
    }

    @Test
    public void readBooleanRejectsNonBoolean() {
        try {
            ScimPatch.readBoolean(ScimPatch.Operation.REPLACE, json("\"maybe\""));
            fail("a non-boolean must not be coerced");
        } catch (ScimException e) {
            assertEquals("invalidValue", e.getScimType());
        }
    }

    @Test
    public void readStringUnwrapsValueEnvelope() {
        assertEquals("a@b.com", ScimPatch.readString(ScimPatch.Operation.REPLACE, json("{\"value\":\"a@b.com\"}")));
        assertEquals("a@b.com", ScimPatch.readString(ScimPatch.Operation.REPLACE, json("\"a@b.com\"")));
    }

    @Test
    public void readMembersAcceptsArrayObjectAndWrapper() {
        List<ScimGroup.Member> array =
                ScimPatch.readMembers(ScimPatch.Operation.ADD, json("[{\"value\":\"7\"},{\"value\":\"9\"}]"));
        assertEquals(2, array.size());
        assertEquals("7", array.get(0).value);

        // Entra's "remove one member" shape is a bare object.
        List<ScimGroup.Member> single = ScimPatch.readMembers(ScimPatch.Operation.REMOVE, json("{\"value\":\"7\"}"));
        assertEquals(1, single.size());
        assertEquals("7", single.get(0).value);

        List<ScimGroup.Member> wrapped =
                ScimPatch.readMembers(ScimPatch.Operation.REPLACE, json("{\"members\":[{\"value\":\"3\"}]}"));
        assertEquals(1, wrapped.size());
        assertEquals("3", wrapped.get(0).value);
    }

    @Test
    public void readMembersAcceptsBareIdStrings() {
        List<ScimGroup.Member> members = ScimPatch.readMembers(ScimPatch.Operation.ADD, json("[\"7\",\"9\"]"));
        assertEquals(2, members.size());
        assertEquals("7", members.get(0).value);
    }

    @Test
    public void emptyOperationsIsRejected() {
        try {
            ScimPatch.operations(new ScimPatchRequest());
            fail("an empty PATCH body is invalid per RFC 7644 3.5.2");
        } catch (ScimException e) {
            assertEquals("invalidSyntax", e.getScimType());
        }
        try {
            ScimPatch.operations(null);
            fail("a null PATCH body is invalid");
        } catch (ScimException e) {
            assertEquals(400, e.getResponse().getStatus());
        }
    }

    @Test
    public void fieldsReadsPathlessValueObject() {
        ScimPatchRequest.Operation op = new ScimPatchRequest.Operation();
        op.op = "replace";
        op.value = json("{\"active\":false,\"userName\":\"jane\"}");
        assertTrue(ScimPatch.fields(op.value).containsKey("active"));
        assertEquals(2, ScimPatch.fields(op.value).size());
    }
}
