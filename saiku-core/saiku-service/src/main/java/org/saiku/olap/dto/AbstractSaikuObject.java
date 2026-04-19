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

public class AbstractSaikuObject implements ISaikuObject, Comparable<ISaikuObject> {

    private String uniqueName;
    private String name;

    AbstractSaikuObject() {}

    AbstractSaikuObject(String uniqueName, String name) {
        this.uniqueName = uniqueName;
        this.name = name;
    }

    public String getUniqueName() {
        return uniqueName;
    }

    public String getName() {
        return name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((uniqueName == null) ? 0 : uniqueName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AbstractSaikuObject other = (AbstractSaikuObject) obj;
        if (uniqueName == null) {
            if (other.uniqueName != null) {
                return false;
            }
        } else if (!uniqueName.equals(other.uniqueName)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return this.uniqueName;
    }

    public int compareTo(ISaikuObject o) {
        return getUniqueName().compareTo(o.getUniqueName());
    }
}
