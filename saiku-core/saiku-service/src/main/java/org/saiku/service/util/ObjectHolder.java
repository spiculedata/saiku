package org.saiku.service.util;

import java.util.HashMap;
import java.util.Map;

import org.saiku.olap.query.IQuery;
import org.saiku.service.util.exception.SaikuServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectHolder {
	
	ThreadLocal<Map<String, IQuery>> threadQueries = new ThreadLocal<Map<String,IQuery>>();
	
	private static final Logger LOG = LoggerFactory.getLogger(ObjectHolder.class);
	
	public void putIQuery(String queryName, IQuery query) {
		getIQueryMap().put(queryName, query);
	}
	
	public void removeIQuery(String queryName) {
		getIQueryMap().remove(queryName);
		}
	
	
	public IQuery getIQuery(String queryName) {
		IQuery query = getIQueryMap().get(queryName);
		if (query == null) {
			throw new SaikuServiceException("No query with name ("+queryName+") found");
		}
		return query;
	}
	
	public Map<String, IQuery> getIQueryMap() {
		LOG.trace("ObjectHoler.getIQueryMap : Thread ID " + Thread.currentThread().getId() + " Name: " + Thread.currentThread().getName());
		if (threadQueries.get() == null) {
			threadQueries.set(new HashMap<String,IQuery>());
		}
		return threadQueries.get();
	}
	

}
