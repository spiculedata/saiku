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

import java.util.List;

public class SaikuAxis extends AbstractSaikuObject {

	private List<SaikuDimensionSelection> dimensionSelections;
	private int ordinal;
	private String sortOrder;
	private String sortLiteral;

	public SaikuAxis() {		
		super(null,null);
		throw new RuntimeException("Unsupported Constructor. Serialization only");
	}
	
	public SaikuAxis(String name, int ordinal, String uniqueName, List<SaikuDimensionSelection> dimsel, String sortOrder, String sortLiteral) {
		super(uniqueName,name);
		this.dimensionSelections = dimsel;
		this.ordinal = ordinal;
		this.sortOrder = sortOrder;
		this.sortLiteral = sortLiteral;
	}
	
	public List<SaikuDimensionSelection> getDimensionSelections() {
		return dimensionSelections;
	}
	
	public int getOrdinal() {
		return ordinal;
	}

	public String getSortOrder() {
		return sortOrder;
	}

	public String getSortLiteral() {
		return sortLiteral;
	}


}
