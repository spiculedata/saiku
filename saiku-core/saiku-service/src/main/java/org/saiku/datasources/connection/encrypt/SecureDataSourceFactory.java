// *****************************************************************************
// %name: SecureDataSourceFactory.java %
// Desc :
//    
// Copyright (�) 1994-2006 All Rights Reserved, Unauthorized Duplication
// Prohibited
// Program Property of Embarcadero Technologies, Inc.
// *****************************************************************************
package org.saiku.datasources.connection.encrypt;

import java.util.Hashtable;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.RefAddr;
import javax.naming.Reference;

import org.apache.commons.dbcp.BasicDataSourceFactory;

class SecureDataSourceFactory extends BasicDataSourceFactory
{

    private final static String PROP_DEFAULTAUTOCOMMIT = "defaultAutoCommit";
    private final static String PROP_DEFAULTREADONLY = "defaultReadOnly";
    private final static String PROP_DEFAULTTRANSACTIONISOLATION = "defaultTransactionIsolation";
    private final static String PROP_DEFAULTCATALOG = "defaultCatalog";
    private final static String PROP_DRIVERCLASSNAME = "driverClassName";
    private final static String PROP_MAXACTIVE = "maxActive";
    private final static String PROP_MAXIDLE = "maxIdle";
    private final static String PROP_MINIDLE = "minIdle";
    private final static String PROP_INITIALSIZE = "initialSize";
    private final static String PROP_MAXWAIT = "maxWait";
    private final static String PROP_TESTONBORROW = "testOnBorrow";
    private final static String PROP_TESTONRETURN = "testOnReturn";
    private final static String PROP_TIMEBETWEENEVICTIONRUNSMILLIS = "timeBetweenEvictionRunsMillis";
    private final static String PROP_NUMTESTSPEREVICTIONRUN = "numTestsPerEvictionRun";
    private final static String PROP_MINEVICTABLEIDLETIMEMILLIS = "minEvictableIdleTimeMillis";
    private final static String PROP_TESTWHILEIDLE = "testWhileIdle";
    private final static String PROP_PASSWORD = "password";
    private final static String PROP_URL = "url";
    private final static String PROP_USERNAME = "username";
    private final static String PROP_VALIDATIONQUERY = "validationQuery";
    private final static String PROP_ACCESSTOUNDERLYINGCONNECTIONALLOWED = "accessToUnderlyingConnectionAllowed";
    private final static String PROP_REMOVEABANDONED = "removeAbandoned";
    private final static String PROP_REMOVEABANDONEDTIMEOUT = "removeAbandonedTimeout";
    private final static String PROP_LOGABANDONED = "logAbandoned";
    private final static String PROP_POOLPREPAREDSTATEMENTS = "poolPreparedStatements";
    private final static String PROP_MAXOPENPREPAREDSTATEMENTS = "maxOpenPreparedStatements";
    private final static String PROP_CONNECTIONPROPERTIES = "connectionProperties";

    private final static String [] ALL_PROPERTIES = { PROP_DEFAULTAUTOCOMMIT,
                                                     PROP_DEFAULTREADONLY,
                                                     PROP_DEFAULTTRANSACTIONISOLATION,
                                                     PROP_DEFAULTCATALOG,
                                                     PROP_DRIVERCLASSNAME,
                                                     PROP_MAXACTIVE,
                                                     PROP_MAXIDLE,
                                                     PROP_MINIDLE,
                                                     PROP_INITIALSIZE,
                                                     PROP_MAXWAIT,
                                                     PROP_TESTONBORROW,
                                                     PROP_TESTONRETURN,
                                                     PROP_TIMEBETWEENEVICTIONRUNSMILLIS,
                                                     PROP_NUMTESTSPEREVICTIONRUN,
                                                     PROP_MINEVICTABLEIDLETIMEMILLIS,
                                                     PROP_TESTWHILEIDLE,
                                                     PROP_PASSWORD,
                                                     PROP_URL,
                                                     PROP_USERNAME,
                                                     PROP_VALIDATIONQUERY,
                                                     PROP_ACCESSTOUNDERLYINGCONNECTIONALLOWED,
                                                     PROP_REMOVEABANDONED,
                                                     PROP_REMOVEABANDONEDTIMEOUT,
                                                     PROP_LOGABANDONED,
                                                     PROP_POOLPREPAREDSTATEMENTS,
                                                     PROP_MAXOPENPREPAREDSTATEMENTS,
                                                     PROP_CONNECTIONPROPERTIES };

    // -------------------------------------------------- ObjectFactory Methods

    /**
     * <p>
     * Create and return a new <code>BasicDataSource</code> instance. If no instance can be created, return <code>null</code> instead.
     * </p>
     * 
     * @param obj The possibly null object containing location or reference information that can be used in creating an object
     * @param name The name of this object relative to <code>nameCtx</code>
     * @param environment The possibly null environment that is used in creating this object
     * @exception Exception if an exception occurs creating the instance
     */
    @SuppressWarnings ( "unchecked" )
    public Object getObjectInstance( Object obj, Name name, Context nameCtx, Hashtable environment ) throws Exception
    {

        // We only know how to deal with <code>javax.naming.Reference</code>s
        // that specify a class name of "javax.sql.DataSource"
        if ( ( obj == null ) || ! ( obj instanceof Reference ) )
        {
            return null;
        }
        Reference ref = ( Reference ) obj;
        if ( !"javax.sql.DataSource".equals( ref.getClassName() ) )
        {
            return null;
        }

        Properties properties = new Properties();
      for (String propertyName : ALL_PROPERTIES) {
        RefAddr ra = ref.get(propertyName);
        if (ra != null) {
          String propertyValue = ra.getContent().toString();
          properties.setProperty(propertyName, propertyValue);
        }
      }

        decryptPassword( properties );

        return createDataSource( properties );
    }

    private void decryptPassword( Properties properties )
    {
        String pwd = properties.getProperty( PROP_PASSWORD );
        if ( pwd != null )
        {
            String newPwd = CryptoUtil.decrypt( pwd );
            properties.setProperty( PROP_PASSWORD, newPwd );
        }
    }

}
