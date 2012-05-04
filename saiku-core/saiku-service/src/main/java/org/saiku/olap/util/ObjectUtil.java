/*
 * Copyright (C) 2011 OSBI Ltd
 *
 * This program is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU General Public License as published by the Free 
 * Software Foundation; either version 2 of the License, or (at your option) 
 * any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 *
 */
package org.saiku.olap.util;

import java.util.ArrayList;
import java.util.List;

import org.olap4j.Axis;
import org.olap4j.OlapException;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Level;
import org.olap4j.metadata.Member;
import org.olap4j.query.QueryAxis;
import org.olap4j.query.QueryDimension;
import org.olap4j.query.Selection;
import org.saiku.olap.dto.SaikuAxis;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuDimensionSelection;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SaikuQuery;
import org.saiku.olap.dto.SaikuSelection;
import org.saiku.olap.dto.SaikuSelection.Type;
import org.saiku.olap.query.IQuery;

public class ObjectUtil {


	public static SaikuDimension convert(Dimension dim) {
		SaikuDimension sDim = new SaikuDimension(dim.getName(), dim.getUniqueName(), dim.getCaption(), dim.getDescription(), convertHierarchies(dim.getHierarchies()));
		return sDim;
	}

	public static SaikuDimension convert(QueryDimension dim) {
		return convert(dim.getDimension());
	}

	public static List<SaikuDimension> convertQueryDimensions(List<QueryDimension> dims) {
		List<SaikuDimension> dimList = new ArrayList<SaikuDimension>();
		for (QueryDimension d : dims) {
			dimList.add(convert(d));
		}
		return dimList;
	}
	
	public static List<SaikuDimension> convertDimensions(List<Dimension> dims) {
		List<SaikuDimension> dimList = new ArrayList<SaikuDimension>();
		for (Dimension d : dims) {
			dimList.add(convert(d));
		}
		return dimList;
	}

	public static List<SaikuHierarchy> convertHierarchies(List<Hierarchy> hierarchies) {
		List<SaikuHierarchy> hierarchyList= new ArrayList<SaikuHierarchy>();
		for (Hierarchy h : hierarchies) {
			hierarchyList.add(convert(h));
		}
		return hierarchyList;

	}

	public static SaikuHierarchy convert(Hierarchy hierarchy) {
		try {
			return new SaikuHierarchy(hierarchy.getName(), hierarchy.getUniqueName(), hierarchy.getCaption(), hierarchy.getDimension().getUniqueName(), convertLevels(hierarchy.getLevels()), convertMembers(hierarchy.getRootMembers()));
		} catch (OlapException e) {
			throw new RuntimeException("Cannot get root members",e);
		}
	}

	public static List<SaikuLevel> convertLevels(List<Level> levels) {
		List<SaikuLevel> levelList= new ArrayList<SaikuLevel>();
		for (Level l : levels) {
			levelList.add(convert(l));
		}
		return levelList;

	}

	public static SaikuLevel convert(Level level) {
		try {
//			List<SaikuMember> members = convertMembers(level.getMembers());
			return new SaikuLevel(
					level.getName(), 
					level.getUniqueName(), 
					level.getCaption(), 
					level.getDimension().getUniqueName(), 
					level.getHierarchy().getUniqueName());
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static List<SaikuMember> convertMembers(List<Member> members) {
		List<SaikuMember> memberList= new ArrayList<SaikuMember>();
		for (Member l : members) {
			memberList.add(convert(l));
		}
		return memberList;

	}
	
	public static List<SaikuSelection> convertSelections(List<Selection> selections) {
		List<SaikuSelection> selectionList= new ArrayList<SaikuSelection>();
		for (Selection sel : selections) {
			selectionList.add(convert(sel));
		}
		return selectionList;
	}

	private static SaikuSelection convert(Selection sel) {
		Type type;
		String hierarchyUniqueName;
		String levelUniqueName;
		if (Level.class.isAssignableFrom(sel.getRootElement().getClass())) {
			type = SaikuSelection.Type.LEVEL;
			hierarchyUniqueName = ((Level) sel.getRootElement()).getHierarchy().getUniqueName();
			levelUniqueName = sel.getUniqueName();
		} else {
			type = SaikuSelection.Type.MEMBER;
			hierarchyUniqueName = ((Member) sel.getRootElement()).getHierarchy().getUniqueName();
			levelUniqueName = ((Member) sel.getRootElement()).getLevel().getUniqueName();
		}
		return new SaikuSelection(
				sel.getRootElement().getName(),
				sel.getUniqueName(),
				sel.getRootElement().getCaption(),
				sel.getRootElement().getDescription(),
				sel.getDimension().getName(),
				hierarchyUniqueName,
				levelUniqueName,
				type);

	}

	public static SaikuMember convert(Member m) {
		return new SaikuMember(
				m.getName(), 
				m.getUniqueName(), 
				m.getCaption(), 
				m.getDescription(),
				m.getDimension().getUniqueName(),
				m.getHierarchy().getUniqueName(),
				m.getLevel().getUniqueName());
	}
	
	public static SaikuDimensionSelection convertDimensionSelection(QueryDimension dim) {
		List<SaikuSelection> selections = ObjectUtil.convertSelections(dim.getInclusions());
		return new SaikuDimensionSelection(
				dim.getName(),
				dim.getDimension().getUniqueName(),
				dim.getDimension().getCaption(),
				dim.getDimension().getDescription(),
				selections);
	}
	
	public static List<SaikuDimensionSelection> convertDimensionSelections(List<QueryDimension> dimensions) {
		List<SaikuDimensionSelection> dims = new ArrayList<SaikuDimensionSelection>();
		for (QueryDimension dim : dimensions) {
			dims.add(convertDimensionSelection(dim));
		}
		return dims;
	}
	
	public static SaikuAxis convertQueryAxis(QueryAxis axis) {
		List<SaikuDimensionSelection> dims = ObjectUtil.convertDimensionSelections(axis.getDimensions());
		Axis location = axis.getLocation();
		String so = axis.getSortOrder() == null? null : axis.getSortOrder().name();
		return new SaikuAxis(
				location.name(),
				location.axisOrdinal(),
				axis.getName(),
				dims,
				so,
				axis.getSortIdentifierNodeName());
	}
	
	public static SaikuQuery convert(IQuery q) {
		List<SaikuAxis> axes = new ArrayList<SaikuAxis>();
		if (q.getType().equals(IQuery.QueryType.QM)) {
			for (Axis axis : q.getAxes().keySet()) {
				if (axis != null) {
					axes.add(convertQueryAxis(q.getAxis(axis)));
				}
			}
		}
		return new SaikuQuery(q.getName(), q.getSaikuCube(), axes, q.getMdx(), q.getType().toString());
		
	}

}
