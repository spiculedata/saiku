package org.saiku.olap.query2;

import java.util.ArrayList;
import java.util.List;

public class ThinMeasure {

    private String name;
    private String uniqueName;
    private String caption;
    private Type type;
    private final List<String> aggregators = new ArrayList<>();

    public enum Type {
        CALCULATED,
        EXACT
    }

    public ThinMeasure() {}

    public ThinMeasure(String name, String uniqueName, String caption, Type type) {
        this(name, uniqueName, caption, type, null);
    }

    public ThinMeasure(String name, String uniqueName, String caption, Type type, List<String> aggregators) {
        this.name = name;
        this.uniqueName = uniqueName;
        this.caption = caption;
        this.type = type;

        if (aggregators != null) {
            this.aggregators.addAll(aggregators);
        }
    }

    /**
     * @return the type
     */
    public Type getType() {
        return type;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the uniqueName
     */
    public String getUniqueName() {
        return uniqueName;
    }

    /**
     * @return the caption
     */
    public String getCaption() {
        return caption;
    }

    public List<String> getAggregators() {
        return aggregators;
    }
}
