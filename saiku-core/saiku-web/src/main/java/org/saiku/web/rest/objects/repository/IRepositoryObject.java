package org.saiku.web.rest.objects.repository;

public interface IRepositoryObject {
	
	public enum Type {
		FOLDER, FILE
	}
	
	public Type getType();
	public String getName();
	public String getId();

}
