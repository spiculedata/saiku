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
import java.util.List;

public class SaikuTag extends AbstractSaikuObject {

    private List<SaikuTuple> saikuTuples;
    private String name;
    private List<SimpleCubeElement> saikuTupleDimensions;
    private List<SaikuDimensionSelection> saikuDimensionSelections;

    public SaikuTag() {}

    public SaikuTag(
            String name,
            List<SimpleCubeElement> saikuTupleDimensions,
            List<SaikuTuple> saikuTuples,
            List<SaikuDimensionSelection> filterSelections) {
        super(name, name);
        this.saikuTuples = saikuTuples;
        this.name = name;
        this.saikuTupleDimensions = saikuTupleDimensions;
        this.saikuDimensionSelections = filterSelections;
    }

    public List<SaikuMember> getSaikuMembers(String dimensionUniqueName) {
        List<SaikuMember> members = new ArrayList<>();
        for (SaikuTuple t : saikuTuples) {
            for (SaikuMember m : t.getSaikuMembers()) {
                if (m.getDimensionUniqueName().equals(dimensionUniqueName)) {
                    members.add(m);
                }
            }
        }
        return members;
    }

    public List<SaikuTuple> getSaikuTuples() {
        return saikuTuples;
    }

    public List<SimpleCubeElement> getSaikuTupleDimensions() {
        return saikuTupleDimensions;
    }

    public List<SaikuDimensionSelection> getSaikuDimensionSelections() {
        return saikuDimensionSelections;
    }

    public String getName() {
        return name;
    }
}
