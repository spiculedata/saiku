/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.schedule.delivery;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.export.destination.ExportArtifact;
import org.saiku.service.export.destination.ExportDeliveryException;
import org.saiku.service.export.destination.ExportDestination;
import org.saiku.service.export.destination.ExportDestinationConfig;
import org.saiku.service.export.destination.ExportDestinationConfigStore;
import org.saiku.service.export.destination.ExportDestinationRegistry;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.schedule.ScheduledJobFile;

/**
 * End-to-end shape of the {@code EXPORT_DELIVERY} job (saiku#1987), with fakes for the query engine
 * and the destination. The property under test is the split: the payload names a destination and a
 * source, and every credential is resolved from the config store at delivery time.
 */
public class ExportDeliveryJobHandlerTest {

    /** A destination that records the config it was handed, so we can assert secrets arrive. */
    private static class RecordingDestination implements ExportDestination {
        ExportArtifact artifact;
        ExportDestinationConfig config;
        int calls;

        @Override
        public String id() {
            return "GOOGLE_DRIVE";
        }

        @Override
        public String displayName() {
            return "Google Drive";
        }

        @Override
        public java.util.List<org.saiku.service.export.destination.ExportDestinationConfigField> configFields() {
            return java.util.List.of();
        }

        @Override
        public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
            config.require("folderId");
        }

        @Override
        public org.saiku.service.export.destination.ExportDeliveryResult deliver(
                ExportArtifact artifact, ExportDestinationConfig config) {
            this.calls++;
            this.artifact = artifact;
            this.config = config;
            return org.saiku.service.export.destination.ExportDeliveryResult.of(id(), "remote-1", "ok");
        }
    }

    private static ScheduledJobFile job(Map<String, Object> payload) {
        ScheduledJobFile f = new ScheduledJobFile();
        f.setId("job-1");
        f.setType(ExportDeliveryJobHandler.JOB_TYPE);
        f.setPayload(payload);
        return f;
    }

    private static Map<String, Object> payload(String destinationId) {
        Map<String, Object> source = new HashMap<>();
        source.put("type", "SAVED_QUERY_CSV");
        source.put("savedQuery", "/sales/q1.sai");
        Map<String, Object> p = new HashMap<>();
        p.put("destination", destinationId);
        p.put("source", source);
        return p;
    }

    private static ExportArtifactProducer producerReturning(ExportArtifact artifact) {
        return new ExportArtifactProducer() {
            @Override
            public String type() {
                return "SAVED_QUERY_CSV";
            }

            @Override
            public ExportArtifact produce(ExportSourceSpec spec) {
                return artifact;
            }
        };
    }

    // ---------------- the happy path ----------------

    @Test
    public void producesTheArtifactThenDeliversItToTheNamedDestination() throws Exception {
        ExportDestinationConfigStore store = new ExportDestinationConfigStore(null);
        store.save(
                "GOOGLE_DRIVE",
                ExportDestinationConfig.of(Map.of("folderId", "1AbCdEf"), Map.of("privateKey", "SECRET")));

        RecordingDestination destination = new RecordingDestination();
        ExportDestinationRegistry registry = new ExportDestinationRegistry().with("GOOGLE_DRIVE", destination);
        ExportArtifact artifact = ExportArtifact.of("q1.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8));
        ExportDeliveryJobHandler handler =
                new ExportDeliveryJobHandler(registry, store, Map.of("SAVED_QUERY_CSV", producerReturning(artifact)));

        handler.handle(job(payload("GOOGLE_DRIVE")));

        assertEquals(1, destination.calls);
        assertEquals("q1.csv", destination.artifact.filename());
        assertEquals("1AbCdEf", destination.config.get("folderId"));
        assertEquals("the destination must receive its credentials", "SECRET", destination.config.secret("privateKey"));
    }

    /** The load-bearing property: a job file can be copied, diffed and backed up with no leak. */
    @Test
    public void theJobPayloadNeverCarriesTheCredentials() throws Exception {
        ExportDestinationConfigStore store = new ExportDestinationConfigStore(null);
        store.save(
                "GOOGLE_DRIVE",
                ExportDestinationConfig.of(Map.of("folderId", "1AbCdEf"), Map.of("privateKey", "SECRET")));

        Map<String, Object> p = payload("GOOGLE_DRIVE");
        String serialised = p.toString();
        assertFalse("a credential leaked into the job payload: " + serialised, serialised.contains("SECRET"));
        assertFalse(serialised, serialised.contains("privateKey"));
    }

    @Test
    public void destinationLookupIsCaseInsensitive() throws Exception {
        ExportDestinationConfigStore store = new ExportDestinationConfigStore(null);
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "1AbCdEf")));
        RecordingDestination destination = new RecordingDestination();
        new ExportDeliveryJobHandler(
                        new ExportDestinationRegistry().with("GOOGLE_DRIVE", destination),
                        store,
                        Map.of(
                                "SAVED_QUERY_CSV",
                                producerReturning(ExportArtifact.of("q1.csv", "text/csv", new byte[] {1}))))
                .handle(job(payload("google_drive")));
        assertEquals(1, destination.calls);
    }

    // ---------------- failure paths ----------------

    @Test
    public void failsClearlyOnAnUnknownDestination() {
        ExportDeliveryJobHandler handler = new ExportDeliveryJobHandler(
                new ExportDestinationRegistry().with("GOOGLE_DRIVE", new RecordingDestination()),
                new ExportDestinationConfigStore(null),
                Map.of());
        try {
            handler.handle(job(payload("DROPBOX")));
            fail("expected an ExportDeliveryException");
        } catch (Exception e) {
            assertTrue(e.getClass().getSimpleName(), e instanceof ExportDeliveryException);
            assertTrue(e.getMessage(), e.getMessage().contains("DROPBOX"));
        }
    }

    @Test
    public void failsWhenTheDestinationHasNoSavedConfig() {
        RecordingDestination destination = new RecordingDestination();
        ExportDeliveryJobHandler handler = new ExportDeliveryJobHandler(
                new ExportDestinationRegistry().with("GOOGLE_DRIVE", destination),
                new ExportDestinationConfigStore(null),
                Map.of("SAVED_QUERY_CSV", producerReturning(ExportArtifact.of("q1.csv", "text/csv", new byte[] {1}))));
        try {
            handler.handle(job(payload("GOOGLE_DRIVE")));
            fail("expected an ExportDeliveryException");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no saved configuration"));
            assertEquals("nothing should have been delivered", 0, destination.calls);
        }
    }

    /** The key file can be moved or chmod'ed away since the config was written — re-check at run time. */
    @Test
    public void revalidatesTheConfigAtDeliveryTimeNotOnlyAtSaveTime() {
        ExportDestinationConfigStore store = new ExportDestinationConfigStore(null);
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "1AbCdEf")));
        ExportDestination destination = new RecordingDestination() {
            @Override
            public void validateConfig(ExportDestinationConfig config) throws ExportDeliveryException {
                if (config.get("folderId") == null) {
                    throw ExportDeliveryException.misconfigured("the folder was unset out of band");
                }
            }
        };
        // Store a config the destination will reject, to prove validateConfig is on the delivery path.
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("other", "x")));
        ExportDeliveryJobHandler handler = new ExportDeliveryJobHandler(
                new ExportDestinationRegistry().with("GOOGLE_DRIVE", destination),
                store,
                Map.of("SAVED_QUERY_CSV", producerReturning(ExportArtifact.of("q1.csv", "text/csv", new byte[] {1}))));
        try {
            handler.handle(job(payload("GOOGLE_DRIVE")));
            fail("expected the re-validation to fail the run");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("folder was unset out of band"));
        }
    }

    @Test
    public void failsOnAMissingDestinationKey() {
        ExportDeliveryJobHandler handler = new ExportDeliveryJobHandler(
                new ExportDestinationRegistry(), new ExportDestinationConfigStore(null), Map.of());
        try {
            handler.handle(job(Map.of()));
            fail("expected an IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("payload.destination"));
        }
    }

    @Test
    public void failsOnAnUnregisteredProducerType() {
        RecordingDestination destination = new RecordingDestination();
        Map<String, Object> p = payload("GOOGLE_DRIVE");
        try {
            new ExportDeliveryJobHandler(
                            new ExportDestinationRegistry().with("GOOGLE_DRIVE", destination),
                            new ExportDestinationConfigStore(null),
                            Map.of())
                    .handle(job(p));
            fail("expected the unknown-source rejection");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("SAVED_QUERY_CSV"));
            assertEquals("nothing should have been produced", 0, destination.calls);
        }
    }

    @Test
    public void failsOnANullPayload() {
        ExportDeliveryJobHandler handler = new ExportDeliveryJobHandler(
                new ExportDestinationRegistry(), new ExportDestinationConfigStore(null), Map.of());
        try {
            handler.handle(job(null));
            fail("expected an IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("payload.destination"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void failsOnANullJob() throws Exception {
        new ExportDeliveryJobHandler(new ExportDestinationRegistry(), new ExportDestinationConfigStore(null), Map.of())
                .handle(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void theConstructorRejectsAMissingCollaborator() {
        new ExportDeliveryJobHandler(new ExportDestinationRegistry(), null, Map.of());
    }

    // ---------------- the producer contract ----------------

    /**
     * The producer is the "what": it must run off-request and under the owner's already-established
     * SecurityContext. Here we only pin that the handler hands the spec straight through and does not
     * re-derive the source itself.
     */
    @Test
    public void handsTheParsedSourceSpecToTheProducer() throws Exception {
        ExportDestinationConfigStore store = new ExportDestinationConfigStore(null);
        store.save("GOOGLE_DRIVE", ExportDestinationConfig.ofSettings(Map.of("folderId", "1AbCdEf")));
        ExportSourceSpec[] seen = new ExportSourceSpec[1];
        ExportArtifactProducer producer = new ExportArtifactProducer() {
            @Override
            public String type() {
                return "SAVED_QUERY_CSV";
            }

            @Override
            public ExportArtifact produce(ExportSourceSpec spec) {
                seen[0] = spec;
                return ExportArtifact.of("q1.csv", "text/csv", new byte[] {1});
            }
        };
        new ExportDeliveryJobHandler(
                        new ExportDestinationRegistry().with("GOOGLE_DRIVE", new RecordingDestination()),
                        store,
                        Map.of("SAVED_QUERY_CSV", producer))
                .handle(job(payload("GOOGLE_DRIVE")));
        assertNotNull(seen[0]);
        assertEquals("/sales/q1.sai", seen[0].savedQueryPath());
    }

    // ---------------- the off-request producer ----------------

    @Test
    public void theCsvProducerRunsAThinQueryOffRequestAndFlattensIt() throws Exception {
        AbstractBaseCell[][] headers = {{header("Store")}};
        AbstractBaseCell[][] body = {{cell("Berlin")}};
        CellDataSet cellSet = new CellDataSet(1, 1);
        cellSet.setCellSetHeaders(headers);
        cellSet.setCellSetBody(body);

        FakeDatasourceService ds = new FakeDatasourceService("{\"name\":\"q1\"}");
        SavedQueryCsvArtifactProducer producer =
                new SavedQueryCsvArtifactProducer(ds, () -> new StubThinQueryService(cellSet));

        ExportArtifact artifact = producer.produce(
                ExportSourceSpec.fromPayload(Map.of("type", "SAVED_QUERY_CSV", "savedQuery", "/sales/Q1 Revenue.sai")));

        assertEquals("Q1-Revenue.csv", artifact.filename());
        assertEquals("text/csv", artifact.contentType());
        assertTrue(artifact.contentAsString(), artifact.contentAsString().contains("Berlin"));
        assertEquals("/sales/Q1 Revenue.sai", artifact.metadata().get("sourceFile"));
        assertNotNull(artifact.metadata().get("generatedAt"));
    }

    @Test
    public void anExplicitFileNameInThePayloadWins() throws Exception {
        CellDataSet cellSet = new CellDataSet(1, 1);
        cellSet.setCellSetHeaders(new AbstractBaseCell[][] {{header("Store")}});
        cellSet.setCellSetBody(new AbstractBaseCell[][] {{cell("Berlin")}});
        SavedQueryCsvArtifactProducer producer = new SavedQueryCsvArtifactProducer(
                new FakeDatasourceService("{}"), () -> new StubThinQueryService(cellSet));
        ExportArtifact artifact = producer.produce(ExportSourceSpec.fromPayload(
                Map.of("type", "SAVED_QUERY_CSV", "savedQuery", "/sales/q1.sai", "fileName", "weekly.csv")));
        assertEquals("weekly.csv", artifact.filename());
    }

    @Test
    public void aMissingSavedQueryIsAClearFailureNotAStackTrace() {
        SavedQueryCsvArtifactProducer producer = new SavedQueryCsvArtifactProducer(
                new FakeDatasourceService(null), () -> new StubThinQueryService(new CellDataSet(1, 1)));
        try {
            producer.produce(
                    ExportSourceSpec.fromPayload(Map.of("type", "SAVED_QUERY_CSV", "savedQuery", "/nope.sai")));
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("/nope.sai"));
        }
    }

    @Test
    public void aMalformedSavedQueryIsAClearFailureThatDoesNotEchoTheDocument() {
        SavedQueryCsvArtifactProducer producer = new SavedQueryCsvArtifactProducer(
                new FakeDatasourceService("{ not json at all, and it contains CONFIDENTIAL-DATA"),
                () -> new StubThinQueryService(new CellDataSet(1, 1)));
        try {
            producer.produce(ExportSourceSpec.fromPayload(Map.of("type", "SAVED_QUERY_CSV", "savedQuery", "/x.sai")));
            fail("expected an ExportDeliveryException");
        } catch (ExportDeliveryException e) {
            assertFalse(
                    "the document leaked into the error: " + e.getMessage(),
                    e.getMessage().contains("CONFIDENTIAL-DATA"));
            assertTrue(e.getMessage(), e.getMessage().contains("/x.sai"));
        }
    }

    @Test
    public void fileNameDerivationStripsPathSeparatorsAndDots() {
        assertEquals("q1.csv", SavedQueryCsvArtifactProducer.deriveFileName("/sales/q1.sai"));
        assertEquals("Q1-Revenue.csv", SavedQueryCsvArtifactProducer.deriveFileName("/sales/Q1 Revenue.sai"));
        assertEquals("sai.csv", SavedQueryCsvArtifactProducer.deriveFileName("/sales/.sai"));
        // A crafted path must never survive into a remote file name.
        assertFalse(SavedQueryCsvArtifactProducer.deriveFileName("/a/../b.sai").contains("/"));
        assertFalse(SavedQueryCsvArtifactProducer.deriveFileName("/a/../b.sai").contains(".."));
    }

    // ---------------- fakes ----------------

    private static AbstractBaseCell header(String value) {
        DataCell c = new DataCell();
        c.setFormattedValue(value);
        return c;
    }

    private static AbstractBaseCell cell(String value) {
        return header(value);
    }

    /** A {@link DatasourceService} that answers one canned query document. */
    private static final class FakeDatasourceService extends DatasourceService {
        private final String content;

        FakeDatasourceService(String content) {
            this.content = content;
        }

        @Override
        public String getFileData(String path, String username, java.util.List<String> roles) {
            return content;
        }
    }

    /**
     * A {@link ThinQueryService} that returns a canned cell set. In production the real service is
     * built fresh per run from singleton collaborators precisely because the session-scoped bean
     * cannot be touched on a scheduler thread.
     */
    private static final class StubThinQueryService extends ThinQueryService {
        private final CellDataSet cellSet;

        StubThinQueryService(CellDataSet cellSet) {
            this.cellSet = cellSet;
        }

        @Override
        public ThinQuery createQuery(ThinQuery tq) {
            return tq;
        }

        @Override
        public CellDataSet execute(ThinQuery tq, String formatter) {
            return cellSet;
        }
    }
}
