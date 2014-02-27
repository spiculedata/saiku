/*  
 *   Copyright 2012 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.olap.dto;


public class SaikuSelection extends AbstractSaikuObject {
	
	private String caption;
	private String description;
	private String dimensionUniqueName;
	private String hierarchyUniqueName;
	private String levelUniqueName;
	
	public static enum Type {
		MEMBER,
		LEVEL
	}
	
	private Type type;
	private String showTotals;
	private boolean disableTotals;
	
	public SaikuSelection() {}

	public SaikuSelection(String name, String uniqueName, String caption, String description, String dimensionUniqueName, String hierarchyUniqueName, String levelUniqueName, Type type, String showTotals, boolean disableTotals) {
		super(uniqueName,name);
		this.caption = caption;
		this.description = description;
		this.dimensionUniqueName = dimensionUniqueName;
		this.hierarchyUniqueName = hierarchyUniqueName;
		this.levelUniqueName = levelUniqueName;
		this.type = type;
		this.showTotals = showTotals;
		this.disableTotals = disableTotals;
	}

	public String getHierarchyUniqueName() {
		return hierarchyUniqueName;
	}

	public String getLevelUniqueName() {
		return levelUniqueName;
	}

	public String getCaption() {
		return caption;
	}

	public String getDescription() {
		return description;
	}

	public String getDimensionUniqueName() {
		return dimensionUniqueName;
	}
	
	public Type getType() {
		return type;
	}

	public String getShowTotals() {
		return showTotals;
	}
	
	public boolean getDisableTotals() {
		return disableTotals;
	}	
	
}
