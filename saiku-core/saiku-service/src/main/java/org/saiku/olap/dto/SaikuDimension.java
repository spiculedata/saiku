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
import java.util.List;
import java.util.Map;

public class SaikuDimension extends AbstractSaikuObject {

    private String caption;
    private String description;
    private boolean visible;
    private List<SaikuHierarchy> hierarchies;
    /** saiku#818 follow-up: raw schema-level annotations (e.g.
     *  {@code <Annotation name="saiku.semantic.synonyms">time, date</Annotation>}).
     *  Populated by {@code ObjectUtil.convert(Dimension)} via the same
     *  {@code mondrian.olap.Annotated} reflection seam used for measures and
     *  levels. */
    private Map<String, String> annotations;
    /** Mondrian-native dimension type from olap4j's {@code Dimension.Type} enum
     *  (TIME / MEASURE / OTHER). Set from {@code <Dimension type="TIME">} in
     *  the schema XML. Lets the SPA detect time dimensions without falling
     *  back to caption substring matching (saiku#1221). */
    private String dimensionType;

    public SaikuDimension() {
        super(null, null);
        throw new RuntimeException("Unsupported Constructor. Serialization only");
    }

    public SaikuDimension(
            String name,
            String uniqueName,
            String caption,
            String description,
            boolean visible,
            List<SaikuHierarchy> hierarchies) {
        super(uniqueName, name);
        this.caption = caption;
        this.description = description;
        this.visible = visible;
        this.hierarchies = hierarchies;
    }

    public String getCaption() {
        return caption;
    }

    public String getDescription() {
        return description;
    }

    public boolean isVisible() {
        return visible;
    }

    public List<SaikuHierarchy> getHierarchies() {
        return hierarchies;
    }

    /** Returns a defensive copy, or {@code null} if no annotations were attached. */
    public Map<String, String> getAnnotations() {
        return annotations == null ? null : new HashMap<>(annotations);
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations == null ? null : new HashMap<>(annotations);
    }

    public String getDimensionType() {
        return dimensionType;
    }

    public void setDimensionType(String dimensionType) {
        this.dimensionType = dimensionType;
    }
}
