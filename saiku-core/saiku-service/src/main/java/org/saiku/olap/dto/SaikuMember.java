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

public class SaikuMember extends AbstractSaikuObject {

    private String caption;
    private String dimensionUniqueName;
    private String description;
    private String levelUniqueName;
    private String hierarchyUniqueName;
    private Boolean calculated;

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
        super(uniqueName, name);
        this.caption = caption;
        this.description = description;
        this.dimensionUniqueName = dimensionUniqueName;
        this.levelUniqueName = levelUniqueName;
        this.hierarchyUniqueName = hierarchyUniqueName;
        this.calculated = calculated;
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
}
