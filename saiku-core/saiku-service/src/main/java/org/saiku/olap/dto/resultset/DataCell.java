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
package org.saiku.olap.dto.resultset;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Class CellInfo.
 */
public class DataCell extends AbstractBaseCell implements Serializable {

    private static final long serialVersionUID = 1L;

    private Double rawNumber = null;

    private List<Integer> coordinates = null;

    private String formatString = null; // Definition of the property which holds the format string
    // used to format cell values.

    private final Map<String, String> properties = new HashMap<>();

    /**
     * Blank constructor for serialization purposes, don't use it.
     */
    public DataCell() {
        super();
    }

    /**
     * Construct a Data Cell containing olap data.
     */
    public DataCell(final boolean right, final boolean sameAsPrev, List<Integer> coordinates) {
        super();
        this.right = right;
        this.sameAsPrev = sameAsPrev;
        this.coordinates = coordinates;
    }

    public String getFormatString() {
        return formatString;
    }

    public void setFormatString(String formatString) {
        this.formatString = formatString;
    }

    public Number getRawNumber() {
        return rawNumber;
    }

    public void setRawNumber(final Double rawNumber) {
        this.rawNumber = rawNumber;
    }

    public List<Integer> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<Integer> coordinates) {
        this.coordinates = coordinates;
    }

    public void setProperty(String name, String value) {
        properties.put(name, value);
    }

    public void setProperties(Map<String, String> props) {
        properties.putAll(props);
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public String getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public String toString() {
        return "DataCell{" + ", rawNumber="
                + rawNumber + ", coordinates="
                + coordinates + ", formatString='"
                + formatString + '}';
    }
}
