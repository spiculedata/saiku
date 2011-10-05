/*
 * Copyright (C) 2011 Paul Stoellberger
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

public class SaikuDimension extends AbstractSaikuObject {
	
	private String caption;
	private String description;
	
	private List<SaikuHierarchy> hierarchies;
	
	public SaikuDimension() {
		super(null,null);
		throw new RuntimeException("Unsupported Constructor. Serialization only");
	};
	
	public SaikuDimension(String name, String uniqueName, String caption, String description, List<SaikuHierarchy> hierarchies) {
		super(uniqueName,name);
		this.caption = caption;
		this.description = description;
		this.hierarchies = hierarchies;
	}

	public String getCaption() {
		return caption;
	}

	public String getDescription() {
		return description;
	}

	public List<SaikuHierarchy> getHierarchies() {
		return hierarchies;
	}
	
	
	
	

}
