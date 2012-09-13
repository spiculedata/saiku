package org.saiku.web.rest.objects.repository;

import java.util.List;

import org.saiku.web.rest.objects.acl.enumeration.AclMethod;



public class RepositoryFileObject implements IRepositoryObject {

	private Type type;
	private String name;
	private String id;
	private String filetype;
	private String path;
	private List<AclMethod> acl;

	public RepositoryFileObject(String filename, String id, String filetype, String path, List<AclMethod> acl) {
		this.type = Type.FILE;
		this.name = filename;
		this.id = id;
		this.filetype = filetype;
		this.path = path;
		this.acl = acl;
		
	}
	public Type getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public String getFileType() {
		return filetype;
	}
	
	public String getPath() {
		return path;
	}
	
	public String getId() {
		return id;
	}
	public List<AclMethod> getAcl() {
		return acl;
	}
}
