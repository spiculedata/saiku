/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.cache.SaikuQueryCache;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.QueryCoalescer;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MVP artifact producer (saiku#1987): execute a saved {@code .saiku} query and hand back CSV.
 *
 * <p><b>Runs off-request, exactly like {@code AiMeasureValueReader} (#1098).</b> The delivery job
 * executes on a scheduler worker thread with no bound HTTP request, so it must never touch the
 * {@code scope="session"} {@code thinQueryBean} — its scoped proxy throws
 * {@code IllegalStateException: No thread-bound request found} off-request, which would fail every run
 * and auto-disable the job after the backoff threshold. Instead this takes {@link ThinQueryService}'s
 * real <b>singleton</b> collaborators (the same ones {@code saiku-beans.xml} wires into
 * {@code thinQueryBean}) and builds a fresh {@code ThinQueryService} per run.
 *
 * <p>Owner RLS is preserved by the {@code SecurityContext} the owner-identity job runner has already
 * established — this class does <b>not</b> re-impersonate, and it reads the saved query under the
 * owner's roles via {@link DatasourceService#getFileData}.
 */
public final class SavedQueryCsvArtifactProducer implements ExportArtifactProducer {

    private static final Logger log = LoggerFactory.getLogger(SavedQueryCsvArtifactProducer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** Builds a throwaway {@link ThinQueryService} from the singleton collaborators. */
    @FunctionalInterface
    public interface ThinQueryServiceFactory {
        ThinQueryService create();
    }

    private final DatasourceService datasourceService;
    private final ThinQueryServiceFactory thinQueryServiceFactory;

    /** Production constructor — the collaborators {@code thinQueryBean} is wired with. */
    public SavedQueryCsvArtifactProducer(
            DatasourceService datasourceService,
            OlapDiscoverService olapDiscoverService,
            SaikuQueryCache queryCache,
            UserService userService,
            QueryCoalescer queryCoalescer,
            OssieQueryService ossieQueryService) {
        this(datasourceService, () -> {
            ThinQueryService tqs = new ThinQueryService();
            tqs.setOlapDiscoverService(olapDiscoverService);
            tqs.setQueryCache(queryCache);
            tqs.setUserService(userService);
            tqs.setQueryCoalescer(queryCoalescer);
            tqs.setOssieQueryService(ossieQueryService);
            return tqs;
        });
    }

    /** Visible for tests: inject a factory that mints a fake executor. */
    public SavedQueryCsvArtifactProducer(
            DatasourceService datasourceService, ThinQueryServiceFactory thinQueryServiceFactory) {
        if (datasourceService == null || thinQueryServiceFactory == null) {
            throw new IllegalArgumentException("datasourceService and thinQueryServiceFactory are required");
        }
        this.datasourceService = datasourceService;
        this.thinQueryServiceFactory = thinQueryServiceFactory;
    }

    @Override
    public String type() {
        return ExportSourceSpec.TYPE_SAVED_QUERY_CSV;
    }

    @Override
    public ExportArtifact produce(ExportSourceSpec spec) throws ExportDeliveryException {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        String path = spec.savedQueryPath();
        String fileContent;
        try {
            // The owner-identity runner has already established the owner's SecurityContext, so
            // getFileData() resolves the caller's roles exactly as an interactive read would.
            fileContent = datasourceService.getFileData(path, null, null);
        } catch (Exception e) {
            throw new ExportDeliveryException("could not read the saved query at " + path);
        }
        if (StringUtils.isBlank(fileContent)) {
            throw new ExportDeliveryException("the saved query at " + path + " is empty");
        }

        ThinQuery thinQuery;
        try {
            thinQuery = MAPPER.readValue(fileContent, ThinQuery.class);
        } catch (Exception e) {
            // The .saiku JSON is user-authored; its parse error is not a secret, but a full Jackson
            // message can echo the whole document, so we only report the type.
            throw new ExportDeliveryException("the saved query at " + path + " is not a valid Saiku query ("
                    + e.getClass().getSimpleName() + ")");
        }
        if (thinQuery == null) {
            throw new ExportDeliveryException("the saved query at " + path + " parsed to nothing");
        }

        CellDataSet cellSet;
        try {
            ThinQueryService tqs = thinQueryServiceFactory.create();
            tqs.createQuery(thinQuery);
            cellSet = tqs.execute(thinQuery, "flattened");
        } catch (Exception e) {
            log.warn("Scheduled export query {} failed", path, e);
            throw new ExportDeliveryException("the saved query at " + path + " failed to execute");
        }

        String fileName = spec.fileName() != null ? spec.fileName() : deriveFileName(path);
        return ExportArtifact.builder(fileName, "text/csv", CellSetCsvWriter.toCsvBytes(cellSet))
                .metadata("sourceFile", path)
                .metadata(
                        "generatedAt",
                        java.time.Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
                .build();
    }

    /**
     * Derive a destination-safe file name from the query path: base name, {@code .csv} extension, and
     * the characters {@link ExportArtifact} forbids already stripped. Returns {@code export.csv} when
     * the path yields nothing usable, so a run never fails over a cosmetic detail.
     */
    static String deriveFileName(String savedQueryPath) {
        String base = savedQueryPath;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        StringBuilder safe = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ') {
                safe.append(c == ' ' ? '-' : c);
            }
        }
        String cleaned = safe.toString();
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            cleaned = "export-" + STAMP.format(java.time.Instant.now().atOffset(ZoneOffset.UTC));
        }
        return cleaned + ".csv";
    }

    /** The supported producer types, for the admin API's help output. */
    public static List<String> supportedTypes() {
        return List.of(ExportSourceSpec.TYPE_SAVED_QUERY_CSV);
    }
}
