package org.saiku.service.olap.drillthrough;

import java.util.ArrayList;
import java.util.List;

import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

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
		for (String column: split) {
			String[] split2 = column.trim().split("\\.");
			String[] els = new String[split2.length];
			for (int i = 0; i < split2.length; i++) {
				String token = split2[i];
				els[i] = token.trim().replaceAll("[\\[\\]]", "");
			}
			if (els[0].equalsIgnoreCase("Measures")) {
				results.add(new MeasureResultInfo(els[1]));
			} else {
				results.add(new DimensionResultInfo(els[0], els[1], els[2]));
			}
		}
		return results;
	}
	
}
