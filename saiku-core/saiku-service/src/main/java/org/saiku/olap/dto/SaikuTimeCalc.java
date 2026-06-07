/*
 *   Copyright 2026 Spicule Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.olap.dto;

/**
 * Declarative time-intelligence directive parsed from the cube's
 * {@code <TimeCalcs>/<TimeCalc>} schema XML (mondrian-saiku#112; ships with
 * mondrian 4.8.1.31+). One instance per declared calculation — surfaced to the
 * SPA so the date-filter modal can offer them as one-click measures rather
 * than asking the user to author MDX.
 *
 * <p>Example XML the parser handles:
 *
 * <pre>{@code
 *   <TimeCalcs>
 *     <TimeCalc name="Revenue YoY"  type="yoy"     measure="Revenue"
 *               timeDimension="Calendar" formatString="0.0%"/>
 *     <TimeCalc name="Revenue R3"   type="rolling" measure="Revenue"
 *               timeDimension="Calendar" window="3" function="avg"
 *               formatString="#,###"/>
 *   </TimeCalcs>
 * }</pre>
 *
 * <p>This is a pure data carrier — translation into MDX (e.g. building the
 * {@code [Measures].[Revenue YoY]} reference, picking the right
 * {@code ParallelPeriod} anchor) happens client-side or in the AI-ask
 * provider; this DTO is just what the SPA sees over the wire.
 */
public class SaikuTimeCalc {

    private String name;
    private String type;
    private String measure;
    private String timeDimension;
    private Integer window;
    private String function;
    private String formatString;

    /** Jackson default. Real construction goes through the all-args ctor. */
    public SaikuTimeCalc() {}

    public SaikuTimeCalc(
            String name,
            String type,
            String measure,
            String timeDimension,
            Integer window,
            String function,
            String formatString) {
        this.name = name;
        this.type = type;
        this.measure = measure;
        this.timeDimension = timeDimension;
        this.window = window;
        this.function = function;
        this.formatString = formatString;
    }

    public String getName() {
        return name;
    }

    /** One of {@code yoy} / {@code pop} / {@code ytd} / {@code rolling}.
     *  Free-form per the schema; the SPA validates the value before rendering. */
    public String getType() {
        return type;
    }

    /** Name of the underlying measure the calc references — must resolve in the
     *  cube's MeasureGroups for the calc to be runnable. */
    public String getMeasure() {
        return measure;
    }

    /** Name of the dimension that supplies the time axis (e.g. {@code Calendar}). */
    public String getTimeDimension() {
        return timeDimension;
    }

    /** Window size for {@code type="rolling"}; {@code null} for non-rolling kinds. */
    public Integer getWindow() {
        return window;
    }

    /** Aggregator for {@code type="rolling"} (e.g. {@code avg} / {@code sum});
     *  {@code null} for non-rolling kinds. */
    public String getFunction() {
        return function;
    }

    /** Mondrian format string to apply to the resulting cell values (e.g.
     *  {@code 0.0%} for a YoY ratio). May be {@code null}. */
    public String getFormatString() {
        return formatString;
    }
}
