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
package org.saiku.datasources.datasource;

import java.util.Properties;

public class SaikuDatasource {

    private String name;
    private Type type;
    private Properties properties;

    public SaikuDatasource() {}

    public SaikuDatasource(String name, Type type, Properties properties) {
        this.name = name;
        this.type = type;
        this.properties = properties;
    }

    public enum Type {
        OLAP
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }

    @Override
    public SaikuDatasource clone() {
        Properties props = (Properties) properties.clone();
        return new SaikuDatasource(name, type, props);
    }
}
