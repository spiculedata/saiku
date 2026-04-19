package org.saiku.olap.query2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by bugg on 15/09/15.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThinCalculatedMember {

    private String parentMember;
    private String parentMemberLevel;
    private String previousLevel;
    private String dimension;
    private String name;
    private String uniqueName;
    private String caption;
    private Map<String, String> properties = new HashMap<>();
    private String formula;
    private String hierarchyName;
    private String assignedLevel;

    public ThinCalculatedMember() {}

    public ThinCalculatedMember(
            String dimension,
            String hierarchyName,
            String name,
            String uniqueName,
            String caption,
            String formula,
            Map<String, String> properties,
            String parentMember,
            String assignedLevel) {
        this(dimension, hierarchyName, name, uniqueName, caption, formula, properties);
        this.parentMember = parentMember;
    }

    public ThinCalculatedMember(
            String dimension,
            String hierarchyName,
            String name,
            String uniqueName,
            String caption,
            String formula,
            Map<String, String> properties,
            String parentMember,
            String parentMemberLevel,
            String assignedLevel) {
        this(dimension, hierarchyName, name, uniqueName, caption, formula, properties);
        this.parentMember = parentMember;
        this.parentMemberLevel = parentMemberLevel;
    }

    public ThinCalculatedMember(
            String dimension,
            String hierarchyName,
            String name,
            String uniqueName,
            String caption,
            String formula,
            Map<String, String> properties,
            String parentMember,
            String parentMemberLevel,
            String lastLevel,
            String assignedLevel) {
        this(dimension, hierarchyName, name, uniqueName, caption, formula, properties);
        this.parentMember = parentMember;
        this.parentMemberLevel = parentMemberLevel;
        this.previousLevel = lastLevel;
    }

    public ThinCalculatedMember(
            String dimension,
            String hierarchyName,
            String name,
            String uniqueName,
            String caption,
            String formula,
            Map<String, String> properties) {
        this.dimension = dimension;
        this.hierarchyName = hierarchyName;
        this.uniqueName = uniqueName;
        this.formula = formula;
        this.name = name;
        this.caption = caption;
        this.properties = properties;
    }

    public String getDimension() {
        return dimension;
    }

    /**
     * @return the uniqueName
     */
    public String getUniqueName() {
        return uniqueName;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the caption
     */
    public String getCaption() {
        return caption;
    }

    /**
     * @return the properties
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * @return the formula
     */
    public String getFormula() {
        return formula;
    }

    /**
     * @return the hierarchyUniqueName
     */
    public String getHierarchyName() {
        return hierarchyName;
    }

    /**
     *
     * @return the parent member.
     */
    public String getParentMember() {
        return parentMember;
    }

    public String getParentMemberLevel() {
        return parentMemberLevel;
    }

    public String getPreviousLevel() {
        return previousLevel;
    }

    public String getAssignedLevel() {
        return assignedLevel;
    }

    public void setAssignedLevel(String assignedLevel) {
        this.assignedLevel = assignedLevel;
    }
}
