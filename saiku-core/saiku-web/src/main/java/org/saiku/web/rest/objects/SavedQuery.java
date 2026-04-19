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

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class SavedQuery implements Comparable<SavedQuery> {
    private String name;
    private String lastModified;
    private String xml;

    public SavedQuery() {}

    public SavedQuery(String name, String lastModified, String xml) {
        this.name = name;
        this.lastModified = lastModified;
        this.xml = xml;
    }

    private String getName() {
        return name;
    }

    public String getLastModified() {
        return lastModified;
    }

    public String getXml() {
        return xml;
    }

    public void setXml(String xml) {
        this.xml = xml;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public int compareTo(SavedQuery o) {
        return name.compareTo(o.getName());
    }
}
