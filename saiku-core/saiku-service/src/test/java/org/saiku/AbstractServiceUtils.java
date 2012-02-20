package org.saiku;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.hsqldb.jdbc.jdbcDataSource;
import org.junit.Assert;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.service.datasource.DatasourceService;

public class AbstractServiceUtils {

    private static boolean IS_INIT_DONE = false;
    
    private static String connectionId;
    
    private static DataSource datasource = null;



    private static Properties testProps = new Properties();

    public AbstractServiceUtils(){
        
    }
    public void initTestContext() {
        if (!IS_INIT_DONE) {
            try {
               
                InputStream inputStream= getClass().getResourceAsStream("connection.properties");

                /*
                 * Step 1. Create a datasource for Mondrian tests.
                 */
                // Load test context properties.
                testProps.load(inputStream); //$NON-NLS-1$
                
                
                // Create the mondrian datasource
                jdbcDataSource ds = new jdbcDataSource();
                ds.setDatabase(getTestProperty("name")); //$NON-NLS-1$
                ds.setUser(getTestProperty("username")); //$NON-NLS-1$
                ds.setPassword(getTestProperty("password")); //$NON-NLS-1$
                

                try {
                    Class.forName("org.hsqldb.jdbcDriver");
                
                Connection c = DriverManager.getConnection("jdbc:hsqldb:file:target/test/myunittests", "SA", "");

                // Create the mondrian schema
                //Connection c = ds.getConnection();
                Statement stm = c.createStatement();
                
                try {
                ResultSet rs = stm.executeQuery("SELECT count(*) FROM \"account\""); 
                
                dump(rs);
                stm.clearBatch();
                stm.close();
                c.commit();
                c.close();
                }
                catch (Exception e) {
                stm.clearBatch();
                stm.close();
                stm = c.createStatement();
                
                slurp(stm, AbstractServiceUtils.class
                        .getResourceAsStream("foodmart_hsql.script")); //$NON-NLS-1$
                stm.executeBatch();
                stm.clearBatch();
                stm.close();
                c.commit();
                c.close();
                
                }
            }
                catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
                IS_INIT_DONE = true;
            }   
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
}
    

    protected String getTestProperty(String key) {
        return testProps.getProperty(key);
    }


    protected String createConnection(DatasourceService service, String userId,
            String sessionId) {
        if (!IS_INIT_DONE)
            throw new RuntimeException(
                    "You can't use the context properties unless you initialize a test context first."); //$NON-NLS-1$

        service.addDatasource(new SaikuDatasource("test", SaikuDatasource.Type.OLAP, testProps)); //$NON-NLS-1$
        return connectionId;
    }
    protected String[][] runOnDatasource(String sql) {
        try 
        {
            Connection c = datasource.getConnection();
            Statement stm = c.createStatement();
            ResultSet rst = stm.executeQuery(sql);
            
            int nbCols=rst.getMetaData().getColumnCount();
            List<String[]> rows = new ArrayList<String[]>();
            while(rst.next())
            {
                String[] currentRow = new String[nbCols];
                for (int colPos = 1; colPos<=nbCols;colPos++)
                    currentRow[colPos-1]=rst.getString(colPos);
                rows.add(currentRow);
            }
            
            String[][] resultArray = new String[rows.size()][nbCols];
            for(int rowPos = 0; rowPos < rows.size(); rowPos++) {
                resultArray[rowPos] = rows.get(rowPos);
            }
            
            rst.close();
            stm.close();
            c.close();
            
            return resultArray;
            
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void assertTwoDimensionArrayEquals(String[][] expected, String[][] actual)
    {
        Assert.assertEquals(expected.length, actual.length);
        for(int rowPos = 0; rowPos < expected.length; rowPos++) {
            Assert.assertArrayEquals(expected[rowPos], actual[rowPos]);
        }
    }

    private void slurp(Statement stm, InputStream stream) throws Exception {
        if (stream == null) {
            throw new FileNotFoundException("Load data: File " + "foodmart_hsql.script"
                                            + " does not exist");
        }

        DataInputStream in = new DataInputStream(stream);
        BufferedReader br = new BufferedReader(new InputStreamReader(in));

        String strLine;

        while ((strLine = br.readLine()) != null) {
            // stm.addBatch(strLine);
            stm.execute(strLine);
        }

        in.close();
    }

    
    public static void dump(ResultSet rs) throws SQLException {

        // the order of the rows in a cursor
        // are implementation dependent unless you use the SQL ORDER statement
        ResultSetMetaData meta   = rs.getMetaData();
        int               colmax = meta.getColumnCount();
        int               i;
        Object            o = null;

        // the result set is a cursor into the data.  You can only
        // point to one row at a time
        // assume we are pointing to BEFORE the first row
        // rs.next() points to next row and returns true
        // or false if there is no next row, which breaks the loop
        for (; rs.next(); ) {
            for (i = 0; i < colmax; ++i) {
                o = rs.getObject(i + 1);    // Is SQL the first column is indexed

                // with 1 not 0
                System.out.print(o.toString() + " ");
            }

            System.out.println(" ");
        }
    }                            
}
