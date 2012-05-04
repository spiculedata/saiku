package org.saiku.service;

import org.junit.Test;
import org.saiku.olap.query.IQuery;
import org.saiku.service.util.ObjectHolder;
import static org.junit.Assert.*;

public class UtilTest {
	
	@Test
	public void testObjectHolder() {
		final ObjectHolder holder = new ObjectHolder();
		holder.putIQuery("thread-1", new MockQuery("thread-query-1"));
		System.out.println(holder.getIQuery("thread-1").toString());
		assertEquals("thread-query-1", holder.getIQuery("thread-1").getName());
		
		new Thread(new Runnable() {
			
			public void run() {
				try {
				IQuery q = holder.getIQuery("thread-1");
				fail();
				} catch (Exception e) {
				}
			}
		}).start();
		IQuery q = holder.getIQuery("thread-1");
		assertEquals("thread-query-1", q.getName());
		
	}

}
