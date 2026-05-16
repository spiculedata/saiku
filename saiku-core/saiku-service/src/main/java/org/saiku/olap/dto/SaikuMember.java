/*
 *   Copyright 2012 OSBI Ltd
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
package org.saiku.olap.dto;

import java.util.HashMap;
import java.util.Map;

public class SaikuMember extends AbstractSaikuObject {

    private String caption;
    private String dimensionUniqueName;
    private String description;
    private String levelUniqueName;
    private String hierarchyUniqueName;
    private Boolean calculated;
    /** Raw schema-level annotations (e.g. {@code <Annotation name="saiku.semantic.unit">USD</Annotation>}).
     *  Populated by {@code ObjectUtil.convertMeasure} from olap4j's
     *  {@code MetadataElement.getAnnotations()} so downstream consumers (AI schema
     *  projection, saiku#818) can read them without reaching back to olap4j. */
    private Map<String, String> annotations;
    /** Whether the member should appear in analyst-facing UIs. Defaults to
     *  true so legacy callers that don't pass the flag get the safe
     *  (always-show) behaviour. saiku#778 surfaces this from the
     *  schema-level {@code visible="false"} attribute on
     *  {@code <CalculatedMember>} so cubes that ship helper measures can
     *  hide them from the member tree. */
    private Boolean visible = true;

    SaikuMember() {}

    public SaikuMember(
            String name,
            String uniqueName,
            String caption,
            String description,
            String dimensionUniqueName,
            String hierarchyUniqueName,
            String levelUniqueName,
            boolean calculated) {
        this(
                name,
                uniqueName,
                caption,
                description,
                dimensionUniqueName,
                hierarchyUniqueName,
                levelUniqueName,
                calculated,
                true);
    }

    public SaikuMember(
            String name,
            String uniqueName,
            String caption,
            String description,
            String dimensionUniqueName,
            String hierarchyUniqueName,
            String levelUniqueName,
            boolean calculated,
            boolean visible) {
        super(uniqueName, name);
        this.caption = caption;
        this.description = description;
        this.dimensionUniqueName = dimensionUniqueName;
        this.levelUniqueName = levelUniqueName;
        this.hierarchyUniqueName = hierarchyUniqueName;
        this.calculated = calculated;
        this.visible = visible;
    }

    public SaikuMember(
            String name,
            String uniqueName,
            String caption,
            String description,
            String dimensionUniqueName,
            String hierarchyUniqueName,
            String levelUniqueName) {
        super(uniqueName, name);
        this.caption = caption;
        this.description = description;
        this.dimensionUniqueName = dimensionUniqueName;
        this.levelUniqueName = levelUniqueName;
        this.hierarchyUniqueName = hierarchyUniqueName;
        this.calculated = false;
        this.visible = true;
    }

    public String getCaption() {
        return caption;
    }

    public String getDescription() {
        return description;
    }

    public String getLevelUniqueName() {
        return levelUniqueName;
    }

    public String getDimensionUniqueName() {
        return dimensionUniqueName;
    }

    public String getHierarchyUniqueName() {
        return hierarchyUniqueName;
    }

    public Boolean isCalculated() {
        return calculated;
    }

    public void setCalculated(Boolean calculated) {
        this.calculated = calculated;
    }

    /** @see #visible */
    public Boolean isVisible() {
        return visible;
    }

    public void setVisible(Boolean visible) {
        this.visible = visible;
    }

    /** Returns a defensive copy, or {@code null} if no annotations were attached. */
    public Map<String, String> getAnnotations() {
        return annotations == null ? null : new HashMap<>(annotations);
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations == null ? null : new HashMap<>(annotations);
    }
}
