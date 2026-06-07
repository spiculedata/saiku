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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SaikuCube extends AbstractSaikuObject {

    private String connection;
    private String catalog;
    private String schema;
    private String caption;
    private boolean visible;
    /** Declarative time-intelligence directives parsed from the cube's
     *  {@code <TimeCalcs>} schema section (saiku#1221 Phase 3; ships with
     *  mondrian-saiku#112). Empty list when none authored or the cube's
     *  schema XML isn't reachable. Surfaced in the wire response so the SPA
     *  date-filter modal can render them as one-click measures. */
    private List<SaikuTimeCalc> timeCalcs;

    public SaikuCube() {}

    public SaikuCube(
            String connectionName, String uniqueCubeName, String name, String caption, String catalog, String schema) {
        this(connectionName, uniqueCubeName, name, caption, catalog, schema, true);
    }

    public SaikuCube(
            String connectionName,
            String uniqueCubeName,
            String name,
            String caption,
            String catalog,
            String schema,
            boolean visible) {
        super(uniqueCubeName, name);
        this.connection = connectionName;
        this.catalog = catalog;
        this.schema = schema;
        this.caption = caption;
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getUniqueName() {
        String uniqueName = "[" + connection + "].[" + catalog + "]";
        uniqueName += ".[" + schema + "].[" + getName() + "]";
        return uniqueName;
    }

    @Override
    public String getName() {
        return super.getName();
    }

    public String getCaption() {
        return caption;
    }

    public String getCatalog() {
        return catalog;
    }

    public String getConnection() {
        return connection;
    }

    public String getSchema() {
        return schema;
    }

    /** Returns a defensive copy; never null (empty list when none parsed). */
    public List<SaikuTimeCalc> getTimeCalcs() {
        return timeCalcs == null ? Collections.emptyList() : new ArrayList<>(timeCalcs);
    }

    public void setTimeCalcs(List<SaikuTimeCalc> timeCalcs) {
        this.timeCalcs = timeCalcs == null ? null : new ArrayList<>(timeCalcs);
    }
}
