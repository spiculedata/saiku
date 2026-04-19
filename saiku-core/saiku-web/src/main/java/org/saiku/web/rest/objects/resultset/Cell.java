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
package org.saiku.web.rest.objects.resultset;

import java.util.Properties;

public class Cell {

    private String value;
    private String type;
    private final Properties properties = new Properties();
    //	private Properties metaproperties;

    public enum Type {
        ROW_HEADER,
        ROW_HEADER_HEADER,
        COLUMN_HEADER,
        DATA_CELL,
        EMPTY,
        UNKNOWN,
        ERROR
    }

    public Cell() {}

    public Cell(String value) {
        this(value, Type.EMPTY);
    }

    public Cell(String value, Properties properties, Type type) {
        this.value = value;
        this.properties.putAll(properties);
        this.type = type.toString();
    }

    public Cell(String value, Type type) {
        this.value = value;
        this.type = type.toString();
    }

    public String getValue() {
        return value;
    }

    public Properties getProperties() {
        return properties;
    }
    //
    //	public Properties getMetaproperties() {
    //		return metaproperties;
    //	}

    public String getType() {
        return type;
    }
}
