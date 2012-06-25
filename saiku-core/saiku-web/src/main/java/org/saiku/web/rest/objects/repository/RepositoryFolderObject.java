package org.saiku.web.rest.objects.repository;

import java.util.List;


public class RepositoryFolderObject implements IRepositoryObject {

	private Type type;
	private String name;
	private String id;
	private String path;
	private List<IRepositoryObject> repoObjects;

	public RepositoryFolderObject(String name, String id, String path,  List<IRepositoryObject> repoObjects) {
		this.type = Type.FOLDER;
		this.name = name;
		this.id = id;
		this.path = path;
		this.repoObjects = repoObjects;
		
	}
	public Type getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public String getId() {
		return id;
	}
	
	public String getPath() {
		return path;
	}
	
	public List<IRepositoryObject> getRepoObjects() {
		return repoObjects;
	}

}
