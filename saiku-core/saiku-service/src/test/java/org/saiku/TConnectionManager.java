package org.saiku;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.saiku.datasources.connection.AbstractConnectionManager;
import org.saiku.datasources.connection.ISaikuConnection;
import org.saiku.datasources.connection.SaikuConnectionFactory;
import org.saiku.datasources.datasource.SaikuDatasource;

public class TConnectionManager extends AbstractConnectionManager {
    private Map<String, ISaikuConnection> connections = new HashMap<String, ISaikuConnection>();
    private List<String> errorConnections = new ArrayList<String>();

 
    
    @Override
    public void init() {
        // TODO Auto-generated method stub
        
    }

    @Override
    protected ISaikuConnection getInternalConnection(String name, SaikuDatasource datasource) {

        ISaikuConnection con = null;


        String newName = name;
//        if (isDatasourceSecurityEnabled(datasource) && sessionService != null) {
//            Map<String, Object> session = sessionService.getAllSessionObjects();
//            String username = (String) session.get("username");
//            if (username != null) {
//                newName = name + "-" + username;
//            }
//        }



        if (!connections.containsKey(newName)) {
            con =  connect(name, datasource);
            if (con != null) {
                connections.put(newName, con);
            } else {
                if (!errorConnections.contains(newName)) {
                    errorConnections.add(newName);
                }
            }

        } else {
            con = connections.get(newName);
        }
//        if (con != null && !isDatasourceSecurity(datasource, ISaikuConnection.SECURITY_TYPE_PASSTHROUGH_VALUE)) {
//            con = applySecurity(con, datasource);
//        }
        return con;
    }

    @Override
    protected void refreshInternalConnection(String name, SaikuDatasource datasource) {
        // TODO Auto-generated method stub
        
    }
    private ISaikuConnection connect(String name, SaikuDatasource datasource) {
        try {
            ISaikuConnection con = SaikuConnectionFactory.getConnection(datasource);
            if (con.initialized()) {
                return con;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
