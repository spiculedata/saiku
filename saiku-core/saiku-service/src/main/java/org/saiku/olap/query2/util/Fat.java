package org.saiku.olap.query2.util;

import org.apache.commons.lang.StringUtils;

import org.olap4j.mdx.ParseTreeNode;
import org.olap4j.metadata.*;
import org.saiku.olap.query2.ThinAxis;
import org.saiku.olap.query2.ThinCalculatedMeasure;
import org.saiku.olap.query2.ThinCalculatedMember;
import org.saiku.olap.query2.ThinDetails;
import org.saiku.olap.query2.ThinHierarchy;
import org.saiku.olap.query2.ThinLevel;
import org.saiku.olap.query2.ThinMeasure;
import org.saiku.olap.query2.ThinMeasure.Type;
import org.saiku.olap.query2.ThinMember;
import org.saiku.olap.query2.ThinQuery;
import org.saiku.olap.query2.ThinQueryModel;
import org.saiku.olap.query2.ThinQueryModel.AxisLocation;
import org.saiku.olap.query2.common.ThinQuerySet;
import org.saiku.olap.query2.common.ThinSortableQuerySet;
import org.saiku.olap.query2.filter.ThinFilter;
import org.saiku.query.IQuerySet;
import org.saiku.query.ISortableQuerySet;
import org.saiku.query.Query;
import org.saiku.query.QueryAxis;
import org.saiku.query.QueryDetails.Location;
import org.saiku.query.QueryHierarchy;
import org.saiku.query.QueryLevel;
import org.saiku.query.mdx.GenericFilter;
import org.saiku.query.mdx.IFilterFunction;
import org.saiku.query.mdx.IFilterFunction.MdxFunctionType;
import org.saiku.query.mdx.NFilter;
import org.saiku.query.mdx.NameFilter;
import org.saiku.query.mdx.NameLikeFilter;
import org.saiku.query.metadata.CalculatedMeasure;
import org.saiku.query.metadata.CalculatedMember;

import org.olap4j.Axis;
import org.olap4j.OlapException;
import org.olap4j.impl.IdentifierParser;
import org.olap4j.mdx.IdentifierNode;
import org.olap4j.mdx.IdentifierSegment;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mondrian.olap4j.SaikuMondrianHelper;
import mondrian.rolap.RolapCubeLevel;

public class Fat {
	
	public static Query convert(ThinQuery tq, Cube cube) throws SQLException {
		
		Query q = new Query(tq.getName(), cube);
		if (tq.getParameters() != null) {
			q.setParameters(tq.getParameters());
		}
		
		if (tq.getQueryModel() == null)
			return q;

		ThinQueryModel model = tq.getQueryModel();
		convertCalculatedMembers(q, model.getCalculatedMembers());
		convertAxes(q, tq.getQueryModel().getAxes(), tq);
		convertCalculatedMeasures(q, model.getCalculatedMeasures());
		convertDetails(q, model.getDetails());
		q.setVisualTotals(model.isVisualTotals());
		q.setVisualTotalsPattern(model.getVisualTotalsPattern());
		return q;
	}

  private static void convertCalculatedMembers(Query q, List<ThinCalculatedMember> thinCms) {
	  if (thinCms != null && thinCms.size() > 0) {
		  for (ThinCalculatedMember qcm : thinCms) {
			  // TODO improve this
			  String name = qcm.getHierarchyName();
			  boolean mondrian3=false;
			  if(SaikuMondrianHelper.getMondrianServer(q.getConnection()).getVersion().getMajorVersion()==3) {
				  name = qcm.getHierarchyName().replaceAll("\\[", "");
				  name = name.replaceAll("]", "");
				  mondrian3 = true;
			  }
			  //Hierarchy h = q.getCube().getHierarchies().get(name);
			  NamedList<Hierarchy> hs = q.getCube().getHierarchies();
			  Hierarchy h = null;
			  for(Hierarchy h2 : hs){
				  if(h2.getUniqueName().equals(qcm.getHierarchyName())){
					  h = h2;
				  }
				  else if(h2.getUniqueName().equals(name)){
					  h = h2;
					  break;
				  }
			  }
			  Member parent = null;
			  if(qcm.getParentMember()!=null){
				  try {
					  parent =
                              q.getCube().lookupMember(IdentifierParser.parseIdentifier(qcm.getParentMember()));
				  } catch (OlapException e) {
					  e.printStackTrace();
				  }

			  }

			  CalculatedMember cm =
					  new CalculatedMember(
                                                    h.getDimension(),
                                                    h,
							  qcm.getName(),
							  null,
							  parent,
							  Member.Type.FORMULA,
							  qcm.getFormula(),
							  qcm.getProperties(), mondrian3);

			  q.addCalculatedMember(q.getHierarchy(h),cm);
		  }
	  }
	/*Hierarchy h = q.getCube().getHierarchies().get("Products");
	CalculatedMember cm =
		new CalculatedMember(
			h.getDimension(),
			h,
			"Consumable",
			"Consumable",
			null,
			Member.Type.FORMULA,
			"Aggregate({Product.Drink, Product.Food})",
			null);
	q.addCalculatedMember(q.getHierarchy(h), cm);

	try {
	  q.getHierarchy(h).includeCalculatedMember(cm);
	} catch (OlapException e) {
	  e.printStackTrace();
	}*/
	/*if (thinCms != null && thinCms.size() > 0) {
	  for (ThinCalculatedMember qcm : thinCms) {
		NamedList<Hierarchy> h2 = q.getCube().getHierarchies();
		for(Hierarchy h: h2){
		  if(h.getUniqueName().equals(qcm.getHierarchyName())){
			CalculatedMember cm =
				new CalculatedMember(
					h.getDimension(),
					h,
					qcm.getName(),
					qcm.getName(),
					null,
					Member.Type.FORMULA,
					qcm.getFormula(),
					null);
			q.addCalculatedMember(q.getHierarchy(h), cm);
			break;
		  }
		}

	  }
	}*/
  }

  private static void convertCalculatedMeasures(Query q, List<ThinCalculatedMeasure> thinCms) {
		if (thinCms != null && thinCms.size() > 0) {
			for (ThinCalculatedMeasure qcm : thinCms) {
			  Hierarchy h = q.getCube().getHierarchies().get("Measures");
			  CalculatedMeasure cm =
					  new CalculatedMeasure(
						  h,
						  qcm.getName(),
						  null,
						  qcm.getFormula(),
						  qcm.getProperties());

				  q.addCalculatedMeasure(cm);

			}
		}
	}

	private static void convertDetails(Query query, ThinDetails details) {
		Location loc = Location.valueOf(details.getLocation().toString());
		query.getDetails().setLocation(loc);
		Axis ax = getLocation(details.getAxis());
		query.getDetails().setAxis(ax);

		if (details.getMeasures().size() > 0) {
			for (ThinMeasure m : details.getMeasures()) {
				if (Type.CALCULATED.equals(m.getType())) {
					Measure measure = query.getCalculatedMeasure(m.getName());
					query.getDetails().add(measure);
				} else if (Type.EXACT.equals(m.getType())) {
					Measure measure = new MeasureAdapter(query.getMeasure(m.getName()), m);
					query.getDetails().add(measure);
				}
			}
		}
	}

	private static void convertAxes(Query q, Map<AxisLocation, ThinAxis> axes, ThinQuery tq) throws OlapException {
		if (axes != null) {
			for (AxisLocation axis : sortAxes(axes.keySet())) {
				if (axis != null) {
					convertAxis(q, axes.get(axis), tq);
				}
			}
		}
	}
	
	private static List<AxisLocation> sortAxes(Set<AxisLocation> axes) {
		List<AxisLocation> ax = new ArrayList<>();
		for (AxisLocation a : AxisLocation.values()) {
			if (axes.contains(a)){
				ax.add(a);
			}
		}
		return ax;
	}
	
	

	private static void convertAxis(Query query, ThinAxis thinAxis, ThinQuery tq) throws OlapException {
		Axis loc = getLocation(thinAxis.getLocation());
		QueryAxis qaxis = query.getAxis(loc);
		for (ThinHierarchy hierarchy : thinAxis.getHierarchies()) {
			QueryHierarchy qh = query.getHierarchy(hierarchy.getName());
			if (qh != null) {
				convertHierarchy(query, qh, hierarchy, tq);
				qaxis.addHierarchy(qh);
			}
		}
		qaxis.setNonEmpty(thinAxis.isNonEmpty());
		List<String> aggs = thinAxis.getAggregators();
		qaxis.getQuery().setAggregators(qaxis.getLocation().toString(), aggs);
		extendSortableQuerySet(query, qaxis, thinAxis);
	}
	
	private static void convertHierarchy(Query q, QueryHierarchy qh, ThinHierarchy th, ThinQuery tq) throws
		OlapException {

		for (ThinLevel tl : th.getLevels().values()) {
		  QueryLevel ql = qh.includeLevel(tl.getName());


		  if (ql == null) {
			qh.includeMember(th.getName() + ".[" + tl.getName() + "]");
		  } else {
			List<String> aggs = tl.getAggregators();
			qh.getQuery().setAggregators(ql.getUniqueName(), aggs);

			if (tl.getSelection() != null) {
			  String parameter = tl.getSelection().getParameterName();
			  if (StringUtils.isNotBlank(parameter)) {
				ql.setParameterName(parameter);
				ql.setParameterSelectionType(org.saiku.query.Parameter.SelectionType.INCLUSION);
			  }
			  switch (tl.getSelection().getType()) {
			  case INCLUSION:
//					if (parameterValues != null) {
//						for (String m : parameterValues) {
//							qh.includeMember(m);
//						}
//
//					} else {
				for (ThinMember tm : tl.getSelection().getMembers()) {
					if(tm.getType() == null || !tm.getType().equals("calculatedmember")) {
						qh.includeMember(tm.getUniqueName());
					}
				}
				ql.setParameterSelectionType(org.saiku.query.Parameter.SelectionType.INCLUSION);
//					}
				break;

			  case EXCLUSION:
//					if (parameterValues != null) {
//						for (String m : parameterValues) {
//							qh.excludeMember(m);
//						}
//
//					} else {
				for (ThinMember tm : tl.getSelection().getMembers()) {
					if(tm.getType() == null || !tm.getType().equals("calculatedmember")) {
						qh.excludeMember(tm.getUniqueName());
					}
				}
				ql.setParameterSelectionType(org.saiku.query.Parameter.SelectionType.EXCLUSION);
//					}
				break;
			  case RANGE:
				int size = tl.getSelection().getMembers().size();
				int iterations = tl.getSelection().getMembers().size() / 2;
				if (size > 2 && size % 2 == 0) {
				  for (int i = 0; i < iterations; i++) {
					ThinMember start = tl.getSelection().getMembers().get(iterations * 2 + i);
					ThinMember end = tl.getSelection().getMembers().get(iterations * 2 + i + 1);
					qh.includeRange(start.getUniqueName(), end.getUniqueName());
				  }
				}
				break;
			  default:
				break;

			  }
			}

			extendQuerySet(qh.getQuery(), ql, tl);
		  }
		  extendSortableQuerySet(qh.getQuery(), qh, th);
		}

		for (Object o : th.getCmembers().entrySet()) {
			Map.Entry pair = (Map.Entry) o;

			ThinCalculatedMember cres = null;
			for (ThinCalculatedMember c : tq.getQueryModel().getCalculatedMembers()) {
				if (c.getUniqueName().equals(pair.getValue())) {
					cres = c;
					break;
				}
				//it.remove(); // avoids a ConcurrentModificationException
			}

			if(cres == null){
				for (ThinCalculatedMember c : tq.getQueryModel().getCalculatedMembers()) {
					String cname = c.getUniqueName();
					int ord = StringUtils.ordinalIndexOf(cname, "[", 2);
					cname = cname.substring(ord, cname.length());
					if (cname.equals(pair.getValue())) {
						cres = c;
						break;
					}
					//it.remove(); // avoids a ConcurrentModificationException
				}
			}

			if(cres == null){
				for (ThinCalculatedMember c : tq.getQueryModel().getCalculatedMembers()) {
					String cname = c.getUniqueName();
					int ord = StringUtils.ordinalIndexOf((String) pair.getValue(), "[", 2);
					String v = ((String)pair.getValue()).substring(ord, ((String)pair.getValue()).length());
					if (cname.equals(v)) {
						cres = c;
						break;
					}
					//it.remove(); // avoids a ConcurrentModificationException
				}
			}

			Hierarchy h2 = null;
			for (Hierarchy h : q.getCube().getHierarchies()) {
				if (h.getUniqueName().equals(cres.getHierarchyName())) {
					h2 = h;
					break;
				}
			}
			CalculatedMember cm;
			Member member = null;
			if(cres.getParentMember()!=null) {
				List<IdentifierSegment> nameParts = IdentifierParser.parseIdentifier(cres.getParentMember());

				member = q.getCube().lookupMember(nameParts);
			}
			boolean mondrian3 = false;
			if(SaikuMondrianHelper.getMondrianServer(q.getConnection()).getVersion().getMajorVersion()==3) {

				mondrian3 = true;
			}

			cm = new CalculatedMember(
					q.getCube().getDimensions().get(cres.getDimension()),
					h2,
					cres.getName(),
					cres.getName(),
					member,
					Member.Type.FORMULA,
					cres.getFormula(),
					null,cres.getAssignedLevel(), mondrian3);
			String level = null;
			if(cres.getAssignedLevel()!=null && !cres.getAssignedLevel().equals("")) {
				String[] split = cres.getAssignedLevel().split("\\.\\[");
				level = split[split.length - 1];
				level = level.substring(0, level.length() - 1);
			}
			else{
				level = h2.getLevels().get(0).getName();
			}

			for (ThinLevel tl : th.getLevels().values()) {
				if(tl.getName().equals(level) && (tl.getSelection()== null
												  || tl.getSelection().getMembers().size()==0)){
					qh.includeCalculatedMember(cm, false);
				}
				else if(!tl.getName().equals(level)){
					//Fudge
				}
				else{
					for (ThinMember tm : tl.getSelection().getMembers()) {
						if(tm.getType()!=null && tm.getType().equals("calculatedmember")&& tm.getUniqueName().equals(cm
								.getUniqueName())) {
							qh.includeCalculatedMember(cm, true);
						}
					}
				}


			}
			extendSortableQuerySet(qh.getQuery(), qh, th);

		}
		extendSortableQuerySet(qh.getQuery(), qh, th);

	}


	private static Axis getLocation(AxisLocation axis) {
		String ax = axis.toString();
		if (AxisLocation.ROWS.toString().equals(ax)) {
			return Axis.ROWS;
		} else if (AxisLocation.COLUMNS.toString().equals(ax)) {
			return Axis.COLUMNS;
		} else if (AxisLocation.FILTER.toString().equals(ax)) {
			return Axis.FILTER;
		} else if (AxisLocation.PAGES.toString().equals(ax)) {
			return Axis.PAGES;
		}
		return null;
	}

	private static void extendQuerySet(Query q, IQuerySet qs, ThinQuerySet ts) {
		qs.setMdxSetExpression(ts.getMdx());
		
		if (ts.getFilters() != null && ts.getFilters().size() > 0) {
			List<IFilterFunction> filters = convertFilters(q, ts.getFilters());
			qs.getFilters().addAll(filters);
		}
		
	}
	
	private static List<IFilterFunction> convertFilters(Query q, List<ThinFilter> filters) {
		List<IFilterFunction> qfs = new ArrayList<>();
		for (ThinFilter f : filters) {
			switch(f.getFlavour()) {
				case Name:
					List<String> exp = f.getExpressions();
					if (exp != null && exp.size() > 1) {
						String hierarchyName = exp.remove(0);
						QueryHierarchy qh = q.getHierarchy(hierarchyName);
						String op = null;
						if(f.getOperator()!=null){
							op = f.getOperator().toString();
						}

						NameFilter nf = new NameFilter(qh.getHierarchy(), exp, op);
						qfs.add(nf);
					}
					break;
				case NameLike:
					List<String> exp2 = f.getExpressions();
					if (exp2 != null && exp2.size() > 1) {
						String hierarchyName = exp2.remove(0);
						QueryHierarchy qh = q.getHierarchy(hierarchyName);
						String op = null;
						if(f.getOperator()!=null){
							op = f.getOperator().toString();
						}

						NameLikeFilter nf = new NameLikeFilter(qh.getHierarchy(), exp2, op);
						qfs.add(nf);
					}
					break;
				case Generic:
					List<String> gexp = f.getExpressions();
					if (gexp != null && gexp.size() == 1) {
						GenericFilter gf = new GenericFilter(gexp.get(0));
						qfs.add(gf);
					}
					break;
				case Measure:
					// TODO Implement this
					break;
				case N:
					List<String> nexp = f.getExpressions();
					if (nexp != null && nexp.size() > 0) {
						MdxFunctionType mf = MdxFunctionType.valueOf(f.getFunction().toString());
						int n = Integer.parseInt(nexp.get(0));
						String expression = null;
						if (nexp.size() > 1) {
							expression = nexp.get(1);
						}
						NFilter nf = new NFilter(mf, n, expression);
						qfs.add(nf);
					}
					break;
				default:
					break;
			}
		}
		return qfs;
	}

	private static void extendSortableQuerySet(Query q, ISortableQuerySet qs, ThinSortableQuerySet ts) {
		extendQuerySet(q, qs, ts);
		if (ts.getHierarchizeMode() != null) {
			qs.setHierarchizeMode(org.saiku.query.ISortableQuerySet.HierarchizeMode.valueOf(ts.getHierarchizeMode().toString()));
		}
		if (ts.getSortOrder() != null) {
			qs.sort(org.saiku.query.SortOrder.valueOf(ts.getSortOrder().toString()), ts.getSortEvaluationLiteral());
		}
		
		
	}
	
	public static class MeasureAdapter implements Measure {
		private Measure measure;
		private ThinMeasure thinMeasure;

		public MeasureAdapter(Measure measure, ThinMeasure thinMeasure) {
			this.measure = measure;
			this.thinMeasure = thinMeasure;
		}

		public ThinMeasure getThinMeasure() {
			return thinMeasure;
		}

		@Override
		public Aggregator getAggregator() {
			if (thinMeasure != null && thinMeasure.getAggregators() != null && !thinMeasure.getAggregators().isEmpty()) {
				String aggregator = thinMeasure.getAggregators().get(0);

				if (aggregator.indexOf("_") > 0) {
					aggregator = aggregator.substring(0, aggregator.indexOf("_"));
				}

				for (Aggregator agg : Aggregator.values()) {
					if (aggregator.equalsIgnoreCase(agg.name())) {
						return agg;
					}
				}
			}

			return measure.getAggregator();
		}

		@Override
		public Datatype getDatatype() {
			return measure.getDatatype();
		}

		@Override
		public boolean isVisible() {
			return measure.isVisible();
		}

		@Override
		public NamedList<? extends Member> getChildMembers() throws OlapException {
			return measure.getChildMembers();
		}

		@Override
		public int getChildMemberCount() throws OlapException {
			return measure.getChildMemberCount();
		}

		@Override
		public Member getParentMember() {
			return measure.getParentMember();
		}

		@Override
		public Level getLevel() {
			return measure.getLevel();
		}

		@Override
		public Hierarchy getHierarchy() {
			return measure.getHierarchy();
		}

		@Override
		public Dimension getDimension() {
			return measure.getDimension();
		}

		@Override
		public Type getMemberType() {
			return measure.getMemberType();
		}

		@Override
		public boolean isAll() {
			return measure.isAll();
		}

		@Override
		public boolean isChildOrEqualTo(Member member) {
			return measure.isChildOrEqualTo(member);
		}

		@Override
		public boolean isCalculated() {
			return measure.isCalculated();
		}

		@Override
		public int getSolveOrder() {
			return measure.getSolveOrder();
		}

		@Override
		public ParseTreeNode getExpression() {
			return measure.getExpression();
		}

		@Override
		public List<Member> getAncestorMembers() {
			return measure.getAncestorMembers();
		}

		@Override
		public boolean isCalculatedInQuery() {
			return measure.isCalculatedInQuery();
		}

		@Override
		public Object getPropertyValue(Property property) throws OlapException {
			return measure.getPropertyValue(property);
		}

		@Override
		public String getPropertyFormattedValue(Property property) throws OlapException {
			return measure.getPropertyFormattedValue(property);
		}

		@Override
		public void setProperty(Property property, Object o) throws OlapException {
			measure.setProperty(property, o);
		}

		@Override
		public NamedList<Property> getProperties() {
			return measure.getProperties();
		}

		@Override
		public int getOrdinal() {
			return measure.getOrdinal();
		}

		@Override
		public boolean isHidden() {
			return measure.isHidden();
		}

		@Override
		public int getDepth() {
			return measure.getDepth();
		}

		@Override
		public Member getDataMember() {
			return measure.getDataMember();
		}

		@Override
		public String getName() {
			return measure.getName();
		}

		@Override
		public String getUniqueName() {
			return measure.getUniqueName();
		}

		@Override
		public String getCaption() {
			return measure.getCaption();
		}

		@Override
		public String getDescription() {
			return measure.getDescription();
		}
	}
}
