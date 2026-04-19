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
import java.util.Properties;

public class SaikuQuery extends AbstractSaikuObject {

    private SaikuCube cube;

    private List<SaikuAxis> axes;

    private String mdx;

    private String type;

    private Properties properties;

    public SaikuQuery() {
        super(null, null);
        throw new RuntimeException("Unsupported Constructor. Serialization only");
    }

    public SaikuQuery(String name, SaikuCube cube, List<SaikuAxis> axes, String mdx, String type, Properties props) {
        super(name, name);
        this.cube = cube;
        this.axes = axes;
        this.mdx = mdx;
        this.type = type;
        this.properties = props;
    }

    public List<SaikuAxis> getSaikuAxes() {
        return axes;
    }

    @Override
    public String getUniqueName() {
        return cube.getUniqueName() + ".[" + getName() + "]";
    }

    public SaikuCube getCube() {
        return cube;
    }

    public String getMdx() {
        return mdx;
    }

    public String getType() {
        return type;
    }

    public Properties getProperties() {
        return properties;
    }
}
