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
package org.saiku.olap.dto;

import java.util.ArrayList;
import java.util.List;

public class SaikuTag extends AbstractSaikuObject {

	private List<SaikuTuple> saikuTuples;
	private String name;
	private List<SaikuTupleDimension> saikuTupleDimensions;
	private List<SaikuDimensionSelection> saikuDimensionSelections;
	
	public SaikuTag() {};
	
	public SaikuTag(String name, List<SaikuTupleDimension> saikuTupleDimensions, List<SaikuTuple> saikuTuples, List<SaikuDimensionSelection> filterSelections) {
		super(name,name);
		this.saikuTuples = saikuTuples;
		this.name = name;
		this.saikuTupleDimensions = saikuTupleDimensions;
		this.saikuDimensionSelections = filterSelections;
	}
	
	public List<SaikuMember> getSaikuMembers(String dimensionUniqueName) {
		List<SaikuMember> members = new ArrayList<SaikuMember>();
		for (SaikuTuple t : saikuTuples) {
			for (SaikuMember m : t.getSaikuMembers()) {
				if (m.getDimensionUniqueName().equals(dimensionUniqueName)) {
					members.add(m);
				}
			}
		}
		return members;
	}
		
	public List<SaikuTuple> getSaikuTuples() {
		return saikuTuples;
	}
	
	public List<SaikuTupleDimension> getSaikuTupleDimensions() {
		return saikuTupleDimensions;
	}
	
	public List<SaikuDimensionSelection> getSaikuDimensionSelections() {
		return saikuDimensionSelections;
	}
	
	public String getName() {
		return name;
	}
	
}
