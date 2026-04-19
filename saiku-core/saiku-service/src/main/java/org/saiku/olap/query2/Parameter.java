package org.saiku.olap.query2;

public class Parameter {

    private String name;
    private String value;
    private ParameterType type = ParameterType.SIMPLE;
    private boolean mandatory = false;

    public enum ParameterType {
        SIMPLE,
        LIST,
        MDX
    }

    public Parameter() {}

    public Parameter(ParameterType type, String name, String value, boolean mandatory) {
        this.type = type;
        this.value = value;
        this.name = name;
        this.mandatory = mandatory;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the value
     */
    public String getValue() {
        return value;
    }

    /**
     * @return the type
     */
    public ParameterType getType() {
        return type;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
