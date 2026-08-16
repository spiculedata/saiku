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
import org.saiku.service.olap.ai.AiCubeRef;
import org.saiku.service.olap.ai.AiFilterSelection;

/**
 * Reads the CURRENT scalar value of a single measure against a cube (saiku#1098). This is the one seam
 * between the threshold-alert handler and the OLAP query stack, so:
 *
 * <ul>
 *   <li>the handler stays unit-testable with an in-memory fake reader (no live cube), and</li>
 *   <li>the production reader ({@link AiMeasureValueReader}) reuses the existing typed AI Query path
 *       ({@code AiCubeMetadataService} → {@code AiSchemaConverter} → {@code ThinQueryService}) rather
 *       than hand-writing MDX.</li>
 * </ul>
 *
 * <p><b>Owner context.</b> The scheduler has already established the job owner's {@code
 * SecurityContext} (via the owner-identity {@code JobRunner}) before the handler — and therefore this
 * reader — runs. The reader must NOT re-impersonate; it simply queries under whatever identity is
 * current, so the value reflects exactly the owner's row-level-security scope.
 */
@FunctionalInterface
public interface MeasureValueReader {

    /**
     * Evaluate {@code measure} on {@code cube} (optionally sliced by {@code filters}) and return the
     * single current numeric value.
     *
     * @return the scalar value of the measure
     * @throws Exception if the cube / measure doesn't resolve, the query fails, or the result is not a
     *     single numeric cell — the engine records a sanitized failure
     */
    double readMeasure(AiCubeRef cube, String measure, List<AiFilterSelection> filters) throws Exception;
}
