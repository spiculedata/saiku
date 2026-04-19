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
package org.saiku.web.rest.objects;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
class SelectionListRestObject {

    private List<SelectionRestObject> selections = new ArrayList<>();

    public SelectionListRestObject() {}

    public SelectionListRestObject(List<SelectionRestObject> selections) {
        this.selections = selections;
    }

    public List<SelectionRestObject> getSelections() {
        return selections;
    }

    public void setSelections(List<SelectionRestObject> selections) {
        this.selections = selections;
    }
}
