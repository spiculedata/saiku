package org.saiku.service.schema.generate.draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the draft Mondrian schema tree produced by the inferrer pipeline.
 *
 * <p>Mutable by design — downstream ops (LLM suggestions, user edits) rewrite fields in place.
 */
public class DraftSchema {

    private String name;
    private final List<DraftCube> cubes = new ArrayList<>();
    private final List<DraftDimension> sharedDimensions = new ArrayList<>();

    public DraftSchema(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<DraftCube> cubes() {
        return cubes;
    }

    /** Shared dimensions defined at schema scope (e.g. a shared Time dimension). */
    public List<DraftDimension> sharedDimensions() {
        return sharedDimensions;
    }
}
