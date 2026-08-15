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
import org.saiku.service.olap.ThinQueryService;
import org.saiku.service.olap.ai.AiCubeMetadataService;
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;
import org.saiku.service.olap.ai.AiMeasureSelection;
import org.saiku.service.olap.ai.AiQueryRequest;
import org.saiku.service.olap.ai.AiSchema;
import org.saiku.service.olap.ai.AiSchemaConverter;

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
 * <p>All of these collaborators live in {@code saiku-service}, so the alert handler needs no
 * {@code saiku-web} dependency to run a query.
 */
public final class AiMeasureValueReader implements MeasureValueReader {

    private final AiCubeMetadataService cubeMetadataService;
    private final ThinQueryService thinQueryService;
    private final AiSchemaConverter converter = new AiSchemaConverter();

    public AiMeasureValueReader(AiCubeMetadataService cubeMetadataService, ThinQueryService thinQueryService) {
        if (cubeMetadataService == null || thinQueryService == null) {
            throw new IllegalArgumentException("cubeMetadataService and thinQueryService are required");
        }
        this.cubeMetadataService = cubeMetadataService;
        this.thinQueryService = thinQueryService;
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
