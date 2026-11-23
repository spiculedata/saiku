/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination.google;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.saiku.service.export.destination.ExportDeliveryException;

/**
 * A deliberately tiny JSON reader for the two Google JSON bodies the connector touches: the
 * service-account key file and the token response.
 *
 * <p>{@code ObjectMapper.readValue} into a {@code Map<String,String>} would coerce JSON
 * <i>numbers</i> and nested objects into Strings with surprising results, and a hand-rolled parser
 * would be a liability. So: a full parse into {@code Object}, then a strict "top-level object of
 * scalars" projection.
 *
 * <p>Both entry points are hardened against the one thing that actually matters here — a malformed or
 * hostile file must produce a sanitized {@link ExportDeliveryException}, never a stack trace that
 * echoes its contents.
 */
final class TokenResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenResponse() {}

    /**
     * The {@code access_token} from a Google token response, or null when absent. Nested values are
     * NOT flattened: a response whose top level is not a flat object of scalars yields null rather
     * than a surprising lookup.
     */
    static String readAccessToken(String json) throws ExportDeliveryException {
        Map<String, String> fields = readJsonFields(json);
        String token = fields.get("access_token");
        return token == null || token.isBlank() ? null : token;
    }

    /**
     * Parse a flat JSON object of scalar values into a string map. Non-scalar values (objects, arrays)
     * are skipped rather than stringified, so a nested {@code private_key} object can't be smuggled
     * past a schema check as {@code "{...}"}.
     */
    static Map<String, String> readJsonFields(String json) throws ExportDeliveryException {
        if (json == null || json.isBlank()) {
            throw new ExportDeliveryException("expected a JSON object, got an empty document");
        }
        Object parsed;
        try {
            parsed = MAPPER.readValue(json, new TypeReference<Object>() {});
        } catch (IOException e) {
            throw new ExportDeliveryException(
                    "expected a JSON object (" + e.getClass().getSimpleName() + ")");
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ExportDeliveryException("expected a JSON object at the top level");
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k instanceof String key && v instanceof String value) {
                out.put(key, value);
            } else if (k instanceof String key && v instanceof Number number) {
                out.put(key, number.toString());
            }
        });
        return out;
    }
}
