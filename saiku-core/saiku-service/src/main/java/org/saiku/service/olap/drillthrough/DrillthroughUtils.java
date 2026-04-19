package org.saiku.service.olap.drillthrough;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;

public class DrillthroughUtils {

    public static SaikuMember findMeasure(List<SaikuMember> measures, final String measureName) {
        return Iterables.find(measures, new Predicate<SaikuMember>() {
            @Override
            public boolean apply(SaikuMember input) {
                return input.getName().equals(measureName);
            }
        });
    }

    public static SaikuHierarchy findHierarchy(List<SaikuHierarchy> hierarchies, final String hierarchyName) {
        return Iterables.find(hierarchies, new Predicate<SaikuHierarchy>() {
            @Override
            public boolean apply(SaikuHierarchy input) {
                return input.getName().equals(hierarchyName);
            }
        });
    }

    public static SaikuLevel findLevel(List<SaikuLevel> levels, final String levelName) {
        return Iterables.find(levels, new Predicate<SaikuLevel>() {
            @Override
            public boolean apply(SaikuLevel input) {
                return input.getName().equals(levelName);
            }
        });
    }

    public static List<ResultInfo> extractResultInfo(String returns) {
        String[] split = returns.split(",");
        List<ResultInfo> results = new ArrayList<>();
        for (String column : split) {
            String[] split2 = column.trim().split("\\.");
            ArrayList<String> els = new ArrayList<>();
            for (int i = 0; i < split2.length; i++) {
                String token = split2[i];
                els.add(token.trim().replaceAll("[\\[\\]]", ""));
            }
            if (els.get(0).equalsIgnoreCase("Measures")) {
                results.add(new MeasureResultInfo(els.get(1)));
            } else {
                if (els.size() >= 3) {
                    results.add(new DimensionResultInfo(els.get(0), els.get(1), els.get(2)));
                } else {
                    results.add(new DimensionResultInfo(els.get(0), els.get(0), els.get(1)));
                }
            }
        }
        return results;
    }
}
