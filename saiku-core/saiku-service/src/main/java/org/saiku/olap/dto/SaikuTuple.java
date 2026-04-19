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

public class SaikuTuple {

    private List<SaikuMember> saikuMembers;

    public SaikuTuple() {}

    public SaikuTuple(List<SaikuMember> members) {
        this.saikuMembers = members;
    }

    public List<SaikuMember> getSaikuMembers() {
        return saikuMembers;
    }

    public void setSaikuMembers(List<SaikuMember> members) {
        this.saikuMembers = members;
    }

    public SaikuMember getSaikuMember(String dimensionUniqueName) {
        for (SaikuMember m : saikuMembers) {
            if (m.getDimensionUniqueName().equals(dimensionUniqueName)) {
                return m;
            }
        }
        return null;
    }

    public List<SaikuMember> getOtherSaikuMembers(String dimensionUniqueName) {
        List<SaikuMember> others = new ArrayList<>();
        for (SaikuMember m : saikuMembers) {
            if (!m.getDimensionUniqueName().equals(dimensionUniqueName)) {
                others.add(m);
            }
        }
        return others;
    }
}
