/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.export.destination;

/**
 * One admin-supplied config field on an {@link ExportDestination}. The destination publishes these;
 * Saiku's admin API and any future config UI render from them, so a third-party destination gets a
 * config form for free.
 *
 * @param name the stable key this field is stored under (also the map key in
 *     {@link ExportDestinationConfig})
 * @param label human label for the admin form
 * @param required when true, {@link ExportDestination#validateConfig} must reject a config without it
 * @param secret when true the value is written to the owner-only secrets file, is <b>never</b> echoed
 *     back by the admin API, and is masked in any error/log path
 * @param help optional one-line help text
 */
public record ExportDestinationConfigField(String name, String label, boolean required, boolean secret, String help) {

    public ExportDestinationConfigField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("config field name is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("config field label is required");
        }
        help = help == null ? "" : help;
    }

    /** A required, non-secret text field. */
    public static ExportDestinationConfigField required(String name, String label) {
        return new ExportDestinationConfigField(name, label, true, false, "");
    }

    /** A required, <b>secret</b> field — stored in the owner-only secrets file, never returned by the API. */
    public static ExportDestinationConfigField secret(String name, String label) {
        return new ExportDestinationConfigField(name, label, true, true, "");
    }

    /** An optional, non-secret text field. */
    public static ExportDestinationConfigField optional(String name, String label, String help) {
        return new ExportDestinationConfigField(name, label, false, false, help);
    }
}
