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

import java.util.List;

public class SaikuConnection extends AbstractSaikuObject {

    /** Well-known type strings surfaced to the client. Kept as strings (not an enum) so the
     *  wire format matches the existing {@code SaikuDatasource.Type} names 1:1 without a
     *  Jackson mapping layer. */
    public static final String TYPE_OLAP = "OLAP";

    public static final String TYPE_OSSIE = "OSSIE";

    private List<SaikuCatalog> catalogs;

    /**
     * Datasource type discriminator for the client. {@code OLAP} means the connection surfaces
     * a Mondrian catalog/schema/cube tree; {@code OSSIE} means catalogs is empty and the client
     * should call {@code /discover/{connection}/ossie-model} for a semantic-model tree.
     */
    private String type = TYPE_OLAP;

    public SaikuConnection() {
        super(null, null);
        throw new RuntimeException("Unsupported Constructor. Serialization only");
    }

    public SaikuConnection(String connectionName, List<SaikuCatalog> catalogs) {
        super(connectionName, connectionName);
        this.catalogs = catalogs;
    }

    public SaikuConnection(String connectionName, List<SaikuCatalog> catalogs, String type) {
        super(connectionName, connectionName);
        this.catalogs = catalogs;
        this.type = type;
    }

    public List<SaikuCatalog> getCatalogs() {
        return catalogs;
    }

    public String getType() {
        return type;
    }
}
