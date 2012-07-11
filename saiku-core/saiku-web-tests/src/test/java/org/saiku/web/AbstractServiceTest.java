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
package org.saiku.web;

import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import org.hsqldb.jdbc.jdbcDataSource;
import org.olap4j.OlapConnection;
import org.olap4j.OlapException;
import org.olap4j.OlapWrapper;
import org.saiku.service.olap.OlapDiscoverService;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

public abstract class AbstractServiceTest extends JerseyTest {


	private static Properties testProps = new Properties();

	private static boolean IS_INIT_DONE = false;

	private static String connectionId;


	  
	protected AppDescriptor configure() {
		// initTest();

		WebAppDescriptor wd = new WebAppDescriptor.Builder(
				"org.saiku.web.rest.resources").contextPath("/")
				.contextParam("", "").clientConfig(new DefaultClientConfig()).build();

		return wd;
	}

	protected String createConnection() {
		if (!IS_INIT_DONE)
			throw new RuntimeException(
					"You can't use the context properties unless you initialize a test context first."); //$NON-NLS-1$

		try {
			connectionId = createConnection(null, null,
					getTestProperty("olap4j.driver"), //$NON-NLS-1$
					getTestProperty("mondrian.url"), null, null); //$NON-NLS-1$
			return connectionId;
		} catch (OlapException e) {
			throw new RuntimeException(e);
		}
	}

	public String createConnection(final String userId, final String sessionId,
			final String driverName, final String connectStr,
			final String username, final String password) throws OlapException {

		OlapConnection connection;
		final String connectionId = UUID.randomUUID().toString();

		try {
			Class.forName(driverName);

			if (username == null && password == null) {
				connection = (OlapConnection) DriverManager
						.getConnection(connectStr);
			} else {
				connection = (OlapConnection) DriverManager.getConnection(
						connectStr, username, password);
			}

			final OlapWrapper wrapper = connection;

			final OlapConnection olapConnection = wrapper
					.unwrap(OlapConnection.class);

			if (olapConnection == null) {
				throw new OlapException("Services.Session.NullConnection"); //$NON-NLS-1$
			} else {

				// sessions.get(userId).get(sessionId).putConnection(connectionId,
				// olapConnection);

				// Obtaining a connection object doesn't mean that the
				// credentials are ok or whatever. We'll test it.
				// this.discoveryService.getCubes(userId, sessionId,
				// connectionId);

				return connectionId;

			}

		} catch (ClassNotFoundException e) {
			// LOG.error(e);
			throw new OlapException(e.getMessage(), e);
		} catch (SQLException e) {
			// LOG.error(e);
			throw new OlapException(e.getMessage(), e);
		} catch (RuntimeException e) {
			// The XMLA driver wraps some exceptions in Runtime stuff.
			// That's on the FIX ME list but not fixed yet... c(T-T)b
			if (e.getCause() instanceof OlapException) {
				throw (OlapException) e.getCause();
			} else {
				throw e;
			}
		}
	}

	protected String getTestProperty(String key) {
		return testProps.getProperty(key);
	}

	private void initTest() {
		initTestContext();

	}

	protected void initTestContext() {
		if (!IS_INIT_DONE) {

			/*
			 * Step 1. Create a datasource for Mondrian tests.
			 */
			// Load test context properties.
			try {
				testProps.loadFromXML(AbstractServiceTest.class
						.getResourceAsStream("test.properties.xml"));

				// Create the mondrian datasource
				jdbcDataSource ds = new jdbcDataSource();
				ds.setDatabase(getTestProperty("context.database")); //$NON-NLS-1$
				ds.setUser(getTestProperty("context.username")); //$NON-NLS-1$
				ds.setPassword(getTestProperty("context.password")); //$NON-NLS-1$

				// Bind the datasource in the directory
				Context ctx = new InitialContext();
				ctx.bind(getTestProperty("context.jndi"), ds); //$NON-NLS-1$

				// Create the mondrian schema
				Connection c = ds.getConnection();
				Statement stm = c.createStatement();
				slurp(stm,
						AbstractServiceTest.class
								.getResourceAsStream("sampledata.sql")); //$NON-NLS-1$
				stm.executeBatch();
				stm.clearBatch();
				stm.close();
				c.commit();
				c.close();

				IS_INIT_DONE = true;

			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		// if (datasource!=null)
		// initDatabase();

	}

	//@Autowired
	public void setOlapDiscoverService(OlapDiscoverService olapds) {
		//olapDiscoverService = olapds;
	}

	private void slurp(Statement stm, InputStream stream) throws Exception {
		DataInputStream in = new DataInputStream(stream);
		BufferedReader br = new BufferedReader(new InputStreamReader(in));

		String strLine;

		while ((strLine = br.readLine()) != null) {
			// stm.addBatch(strLine);
			stm.execute(strLine);
		}

		in.close();
	}

}
