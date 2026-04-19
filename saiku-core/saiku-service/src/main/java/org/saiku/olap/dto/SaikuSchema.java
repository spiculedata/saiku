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

public class SaikuSchema extends AbstractSaikuObject {

    private List<SaikuCube> cubes;

    public SaikuSchema() {
        super(null, null);
        throw new RuntimeException("Unsupported Constructor. Serialization only");
    }

    public SaikuSchema(String name, List<SaikuCube> cubes) {
        super(name, name);
        this.cubes = cubes;
    }

    public List<SaikuCube> getCubes() {
        return cubes;
    }
}
