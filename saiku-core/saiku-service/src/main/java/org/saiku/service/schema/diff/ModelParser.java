/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schema.diff;

/**
 * Parses one model payload into the format-neutral {@link ModelSnapshot} (saiku#1434).
 *
 * <p>Implementations must fail with {@link ModelDiffException.Reason#MALFORMED} on input that
 * claims their format but does not parse — never return a partial snapshot. A snapshot that
 * silently dropped half a cube would report a diff that is a lie, and the whole point of this
 * feature is to be trusted in a PR gate.
 */
public interface ModelParser {

    ModelFormat format();

    /**
     * @param content    raw model text
     * @param sourceName file name the content came from; used only for error messages
     * @throws ModelDiffException when the content is not a valid model in this format
     */
    ModelSnapshot parse(String content, String sourceName);
}
