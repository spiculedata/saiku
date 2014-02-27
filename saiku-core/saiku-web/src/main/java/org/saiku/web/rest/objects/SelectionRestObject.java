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
package org.saiku.web.rest.objects;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class SelectionRestObject {
	
	private String uniquename;
	private String hierarchy;
	private String type;
	private String action;
	private String showTotals;


	public SelectionRestObject() {
	}
	
	
	public String getUniquename() {
		return uniquename;
	}

	public String getType() {
		return type;
	}

	public String getHierarchy() {
		return hierarchy;
	}

	public String getAction() {
		return action;
	}

	public void setUniquename(String uniquename) {
		this.uniquename = uniquename;
	}

	public void setHierarchy(String hierarchy) {
		this.hierarchy = hierarchy;
	}

	public void setType(String type) {
		this.type = type;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getTotalsFunction() {
		return showTotals;
	}

	public void setTotalsFunction(String showTotals) {
		this.showTotals = showTotals;
	}
	
	
}
