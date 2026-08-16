/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.schedule.alert;

import java.util.List;
import org.saiku.olap.dto.resultset.AbstractBaseCell;
import org.saiku.olap.dto.resultset.CellDataSet;
import org.saiku.olap.dto.resultset.DataCell;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.service.cache.SaikuQueryCache;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.olap.QueryCoalescer;
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;
import org.saiku.service.ossie.OssieQueryService;
import org.saiku.service.user.UserService;

/**
 * Production {@link MeasureValueReader} (saiku#1098): resolve one measure's current value by reusing
 * the same typed AI Query stack the {@code POST /ai/query} endpoint uses — no hand-written MDX.
 *
 * <ol>
 *   <li>{@link AiCubeMetadataService#getSchema(AiCubeRef)} resolves the live schema (and validates the
 *       cube ref).</li>
 *   <li>{@link AiSchemaConverter#convert(AiQueryRequest, AiSchema)} builds an MDX-mode {@link
 *       ThinQuery} for a single measure on COLUMNS with no rows (a grand-total query) plus any
 *       admin-configured slicer filters. Name validation against the live schema happens here.</li>
 *   <li>{@link ThinQueryService#execute(ThinQuery)} runs it under the CURRENT {@code SecurityContext}
 *       (the scheduler already impersonated the owner — this reader does not re-impersonate), so the
 *       returned value honours exactly the owner's row-level-security scope.</li>
 *   <li>The single body cell's raw number is returned.</li>
 * </ol>
 *
 * <p><b>Runs off-request (saiku#1098 fix).</b> The handler runs on a scheduler WORKER thread, where
 * there is NO bound HTTP request. The AI Query endpoint's {@code thinQueryBean} is
 * {@code scope="session"} + {@code <aop:scoped-proxy/>}, so touching it off-request throws
 * {@code IllegalStateException: No thread-bound request found} — which would FAIL every alert run and
 * auto-disable the job after the backoff threshold. So this reader does NOT hold a session/request
 * scoped bean. It takes {@link ThinQueryService}'s real collaborators — all SINGLETONS
 * ({@link OlapDiscoverService}, {@link SaikuQueryCache}, {@link UserService}, {@link QueryCoalescer},
 * {@link OssieQueryService}) — and builds a fresh {@link ThinQueryService} per {@code readMeasure}
 * call via {@link ThinQueryServiceFactory}, wired exactly as Spring wires {@code thinQueryBean}.
 * {@code ThinQueryService} holds only per-execution state, so a throwaway instance is correct here.
 *
 * <p>Owner RLS is preserved by {@code SecurityContextHolder} (set by the scheduler's owner-identity
 * runner) + {@link UserService#getCurrentUserRoles()} — NOT by the bean scope — so the value still
 * reflects exactly the owner's role scope.
 */
public final class AiMeasureValueReader implements MeasureValueReader {

    /**
     * Builds a ready-to-execute {@link ThinQueryService} from singleton collaborators. Default
     * production impl wires it identically to the {@code thinQueryBean} Spring definition; tests inject
     * a fake to assert the off-request query path without real Mondrian collaborators.
     */
    @FunctionalInterface
    interface ThinQueryServiceFactory {
        ThinQueryService create();
    }

    private final AiCubeMetadataService cubeMetadataService;
    private final ThinQueryServiceFactory thinQueryServiceFactory;
    private final AiSchemaConverter converter = new AiSchemaConverter();

    /**
     * Production constructor — takes the SAME singleton collaborators the {@code thinQueryBean}
     * definition wires, and constructs a fresh {@link ThinQueryService} per run (no scoped proxy).
     * {@code queryCache}, {@code queryCoalescer} and {@code ossieQueryService} are optional (nullable),
     * matching how {@code ThinQueryService} treats them.
     */
    public AiMeasureValueReader(
            AiCubeMetadataService cubeMetadataService,
            OlapDiscoverService olapDiscoverService,
            SaikuQueryCache queryCache,
            UserService userService,
            QueryCoalescer queryCoalescer,
            OssieQueryService ossieQueryService) {
        this(
                cubeMetadataService,
                defaultFactory(olapDiscoverService, queryCache, userService, queryCoalescer, ossieQueryService));
    }

    /** Visible for tests — inject the schema service + a factory that mints a (possibly fake) executor. */
    AiMeasureValueReader(AiCubeMetadataService cubeMetadataService, ThinQueryServiceFactory thinQueryServiceFactory) {
        if (cubeMetadataService == null || thinQueryServiceFactory == null) {
            throw new IllegalArgumentException("cubeMetadataService and thinQueryServiceFactory are required");
        }
        this.cubeMetadataService = cubeMetadataService;
        this.thinQueryServiceFactory = thinQueryServiceFactory;
    }

    /** Wire a ThinQueryService exactly as saiku-beans.xml wires {@code thinQueryBean}. */
    private static ThinQueryServiceFactory defaultFactory(
            OlapDiscoverService olapDiscoverService,
            SaikuQueryCache queryCache,
            UserService userService,
            QueryCoalescer queryCoalescer,
            OssieQueryService ossieQueryService) {
        if (olapDiscoverService == null) {
            throw new IllegalArgumentException("olapDiscoverService is required");
        }
        return () -> {
            ThinQueryService tqs = new ThinQueryService();
            tqs.setOlapDiscoverService(olapDiscoverService);
            tqs.setQueryCache(queryCache);
            tqs.setUserService(userService);
            tqs.setQueryCoalescer(queryCoalescer);
            tqs.setOssieQueryService(ossieQueryService);
            return tqs;
        };
    }

    @Override
    public double readMeasure(AiCubeRef cube, String measure, List<AiFilterSelection> filters) throws Exception {
        AiQueryRequest req = new AiQueryRequest();
        req.setCube(cube);
        req.getMeasures().add(new AiMeasureSelection(measure));
        // No rows/columns levels — a single grand-total cell. NON EMPTY off so a legitimate empty/zero
        // result still returns a cell to read rather than collapsing to nothing.
        req.setNonEmpty(false);
        if (filters != null && !filters.isEmpty()) {
            req.setFilters(filters);
        }

        AiSchema schema = cubeMetadataService.getSchema(cube);
        ThinQuery tq = converter.convert(req, schema);

        // Build a fresh executor per run from singleton collaborators — NEVER a session-scoped proxy,
        // so this is safe on the scheduler worker thread (no bound request).
        ThinQueryService thinQueryService = thinQueryServiceFactory.create();
        CellDataSet cds = thinQueryService.execute(tq);
        return extractScalar(cds, measure);
    }

    /** Pull the single numeric value out of the (typically 1x1) result body. */
    static double extractScalar(CellDataSet cds, String measure) throws AlertQueryException {
        if (cds == null) {
            throw new AlertQueryException("no result for measure '" + measure + "'");
        }
        AbstractBaseCell[][] body = cds.getCellSetBody();
        if (body == null || body.length == 0 || body[0] == null || body[0].length == 0) {
            throw new AlertQueryException("empty result for measure '" + measure + "'");
        }
        AbstractBaseCell cell = body[0][0];
        if (cell instanceof DataCell dc) {
            Number raw = dc.getRawNumber();
            if (raw != null) {
                return raw.doubleValue();
            }
            // Fall back to parsing the raw string value (some cells carry only the string form).
            Double parsed = parse(dc.getRawValue());
            if (parsed != null) {
                return parsed;
            }
        }
        throw new AlertQueryException("measure '" + measure + "' did not evaluate to a number");
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
