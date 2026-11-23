/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * The {@code source} block of an {@code EXPORT_DELIVERY} job payload (saiku#1987) — which artifact to
 * produce. Parsed and validated up front so a malformed job fails with a clear message at parse time
 * rather than after a query has run.
 *
 * <p>Currently one shape, {@code SAVED_QUERY_CSV}: execute a saved {@code .saiku} query and flatten
 * the resulting cell set to CSV. A dashboard artifact would be a sibling, not a variant — a new
 * {@link ExportArtifactProducer#type()}, same validation shape.
 *
 * <p><b>No secrets here, ever.</b> The block carries a repository path and an optional output file
 * name. Credentials are resolved from {@link org.saiku.service.export.destination.ExportDestinationConfigStore}
 * at delivery time, so a job file sitting in {@code saiku-home/jobs/} is safe to copy around, diff and
 * back up.
 */
public final class ExportSourceSpec {

    public static final String TYPE_SAVED_QUERY_CSV = "SAVED_QUERY_CSV";

    private final String type;
    private final String savedQueryPath;
    private final String fileName;

    private ExportSourceSpec(String type, String savedQueryPath, String fileName) {
        this.type = type;
        this.savedQueryPath = savedQueryPath;
        this.fileName = fileName;
    }

    public String type() {
        return type;
    }

    /** Repository path of the saved {@code .saiku} query, e.g. {@code /home/sales/q1.sai}. */
    public String savedQueryPath() {
        return savedQueryPath;
    }

    /**
     * The file name to deliver under. Null means "derive it from the query name", which
     * {@link SavedQueryCsvArtifactProducer} does and then sanitises.
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Parse the {@code source} block of a job payload.
     *
     * @throws IllegalArgumentException on a missing/unknown type, or a missing/blank query path —
     *     the job engine records that as a FAILED run and backs off
     */
    public static ExportSourceSpec fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("payload.source is required (an object)");
        }
        String typeStr = readString(m.get("type"));
        if (StringUtils.isBlank(typeStr)) {
            throw new IllegalArgumentException(
                    "payload.source.type is required (currently " + TYPE_SAVED_QUERY_CSV + ")");
        }
        String type = typeStr.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_SAVED_QUERY_CSV.equals(type)) {
            throw new IllegalArgumentException(
                    "unknown source type '" + typeStr + "' — currently only " + TYPE_SAVED_QUERY_CSV + " is supported");
        }
        String path = readString(m.get("savedQuery"));
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("payload.source.savedQuery is required for a " + type + " source");
        }
        String fileName = readString(m.get("fileName"));
        return new ExportSourceSpec(type, path.trim(), StringUtils.isBlank(fileName) ? null : fileName.trim());
    }

    private static String readString(Object value) {
        return value instanceof String s ? s : null;
    }

    @Override
    public String toString() {
        return "ExportSourceSpec[type=" + type + ", savedQuery=" + savedQueryPath + "]";
    }
}
