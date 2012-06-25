package org.saiku.web.rest.objects.repository;



public class RepositoryFileObject implements IRepositoryObject {

	private Type type;
	private String name;
	private String id;
	private String filetype;
	private String path;

	public RepositoryFileObject(String filename, String id, String filetype, String path) {
		this.type = Type.FILE;
		this.name = filename;
		this.id = id;
		this.filetype = filetype;
		this.path = path;
		
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
}
