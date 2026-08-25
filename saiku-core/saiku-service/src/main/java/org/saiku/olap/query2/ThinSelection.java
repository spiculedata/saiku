/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.olap.query2;

import java.util.ArrayList;
import java.util.List;

public class ThinSelection {

    public enum Type {
        INCLUSION,
        EXCLUSION,
        RANGE
    }

    private Type type = Type.INCLUSION;
    private List<ThinMember> members = new ArrayList<>();
    private String parameter = null;

    public ThinSelection() {}

    public ThinSelection(Type type, List<ThinMember> members) {
        this(type, members, null);
    }

    private ThinSelection(Type type, List<ThinMember> members, String parameter) {
        this.type = type;
        if (members != null) {
            this.members.addAll(members);
        }
        this.parameter = parameter;
    }
    /**
     * @return the type
     */
    public Type getType() {
        return type;
    }
    /**
     * @param type the type to set
     */
    public void setType(Type type) {
        this.type = type;
    }
    /**
     * @return the members
     */
    public List<ThinMember> getMembers() {
        return members;
    }
    /**
     * @param members the members to set
     */
    public void setMembers(List<ThinMember> members) {
        this.members = members;
    }

    public String getParameterName() {
        return parameter;
    }

    public void setParameterName(String name) {
        this.parameter = name;
    }
}
