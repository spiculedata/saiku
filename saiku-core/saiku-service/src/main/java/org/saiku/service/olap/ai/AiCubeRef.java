/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.service.olap.ai;

/**
 * Lightweight, name-only cube reference an agent can fill in without
 * round-tripping the full {@code SaikuCube} object. Resolved against the
 * live OLAP schema at request time.
 */
public class AiCubeRef {

    private String connectionName;
    private String catalog;
    private String schema;
    private String cubeName;

    public AiCubeRef() {}

    public AiCubeRef(String connectionName, String catalog, String schema, String cubeName) {
        this.connectionName = connectionName;
        this.catalog = catalog;
        this.schema = schema;
        this.cubeName = cubeName;
    }

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String v) { this.connectionName = v; }

    public String getCatalog() { return catalog; }
    public void setCatalog(String v) { this.catalog = v; }

    public String getSchema() { return schema; }
    public void setSchema(String v) { this.schema = v; }

    public String getCubeName() { return cubeName; }
    public void setCubeName(String v) { this.cubeName = v; }

    @Override
    public String toString() {
        return connectionName + "/" + catalog + "/" + schema + "/" + cubeName;
    }
}
