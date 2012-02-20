package org.saiku.olap.discover;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystem;
import org.apache.commons.vfs.FileUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.olap4j.OlapConnection;
import org.saiku.AbstractServiceUtils;
import org.saiku.TConnectionManager;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.ClassPathResourceDatasourceManager;
import org.saiku.service.datasource.IDatasourceManager;


public class OlapMetaExplorerTest {

    private static OlapMetaExplorer olapMetaExplorer;
    private static Properties testProps = new Properties();
    
    /**
     * Test that you can fetch all available connections.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetAllConnections() throws SaikuOlapException  {
       List<SaikuConnection> output = olapMetaExplorer.getAllConnections();

       assertNotNull(output);
       
       assertEquals(1, output.size());
       assertEquals("test", output.get(0).getName());
       
    }
    
    /**
     * Test that you can get a single connection.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetConnectionSuccess() throws SaikuOlapException{
        SaikuConnection output = olapMetaExplorer.getConnection("test");
        
        assertNotNull(output);
        
        assertEquals("test", output.getName());
    }
    
    /**
     * Test what happens when you call an non existant connection.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetConnectionFailure() throws SaikuOlapException{
        /*
         * Connection Failure shouldn't throw an NPE it should throw a nicer error.
         */
        SaikuConnection output = null;
        try{
            output = olapMetaExplorer.getConnection("noname");
        }
        catch (Exception e){
            Exception outpu2t = e;
        }
        assertNull(output);

    }
    
    
    @Test
    public final void testGetConnections() throws SaikuOlapException{
        List<String> list = new ArrayList<String>();
        list.add("test");
        List<SaikuConnection> connections = olapMetaExplorer.getConnections(list);
        
        assertNotNull(connections);
        
        assertEquals("test", connections.get(0).getName());
    }
    
    @Test
    public final void testGetNativeConnection() throws SaikuOlapException{
        OlapConnection output = olapMetaExplorer.getNativeConnection("test");
        
        assertNotNull(output);
        
    }
    
    /**
     * Test to prove that non existant connection currently throws NPE.
     * @throws Exception
     */
    @Test(expected = NullPointerException.class)
    public void testForExpectedExceptionWithAnnotation()
            throws Exception {
        olapMetaExplorer.getConnection("noname");
    }
    
    
    public final void testGetMultipleConnections(){
        
    }
    /**
     * Make sure you can grab a cube from a specified connection.
     */
    @Test
    public final void testGetCubesSingleConnection(){
        List<SaikuCube> output = olapMetaExplorer.getCubes("test");

        assertNotNull(output);

        assertEquals("HR", output.get(0).getName());
    }
    
    /**
     * Make sure you can grab a cube from a specified connection.
     */
    @Test
    public final void testGetCubesMultipleConnections(){
        List<String> cubes = new ArrayList<String>();
        cubes.add("test");
        List<SaikuCube> output = olapMetaExplorer.getCubes(cubes);

        assertNotNull(output);

        assertEquals("HR", output.get(0).getName());
    }
    
    public final void testGetCubesMultipleConnectionsConnection(){
    }
    
    /**
     * Test to make sure you can retrieve all the cubes from a schema.
     */
    @Test
    public final void testGetAllCubes(){
        List<SaikuCube> output = olapMetaExplorer.getAllCubes();
        
        assertNotNull(output);
        
        assertEquals(8, output.size());
        
        for (int i = 0; i < output.size(); i++){
        	assertEquals("FoodMart", output.get(i).getCatalogName());
        	output.get(i).getName();
        	assertEquals("test", output.get(i).getConnectionName());
        	output.get(i).getCubeName();
        	assertEquals("FoodMart", output.get(i).getSchemaName());
        	output.get(i).getUniqueName();
        }
        
    }
    
    /**
     * Test to make sure that the cubes are returned in the same order.
     */
    @Test
    public final void testCubeReturnOrder(){
    	 List<SaikuCube> output = olapMetaExplorer.getAllCubes();
    	 
         assertNotNull(output);

         String[] names = { "HR","Sales 2","Sales Ragged","Sales Scenario","Sales","Store","Warehouse and Sales","Warehouse"};

         for (int i = 0; i < output.size(); i++){
         	assertEquals(names[i], output.get(i).getName());
         	assertEquals("["+names[i]+"]",output.get(i).getCubeName());
         }
    }
    public final void testGetNativeCube(){
        
        
    }
    
    /**
     * Test to make sure you can get all the dimensions in a cube.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetAllDimensions() throws SaikuOlapException{
    	List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();
    	
    	
        List<SaikuDimension> dims = olapMetaExplorer.getAllDimensions(cubes.get(0));
        
        assertNotNull(dims);
        assertEquals(7, dims.size());
    }
    
    /**
     * Test to make sure you can get a single dimension in a cube.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetDimension() throws SaikuOlapException{
        List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();
    	
    	SaikuDimension dim = olapMetaExplorer.getDimension(cubes.get(0), "Department");
    	
    	assertNotNull(dim);
    	
    	assertEquals("Department", dim.getName());
    }
    
    /**
     * Test to make sure you can get a single dimension in a cube.
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetDimensionNull() throws SaikuOlapException{
        List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();
        
        SaikuDimension dim = olapMetaExplorer.getDimension(cubes.get(0), "No dimension");
        
        assertNull(dim);
        }
    
    @Test
    public final void testGetAllHierarchies() throws SaikuOlapException{
        
    	List<SaikuHierarchy> hier = olapMetaExplorer.getAllHierarchies(olapMetaExplorer.getAllCubes().get(0));
    	
    	assertNotNull(hier);
    	
    	assertEquals(8, hier.size());
    }
    
    @Test
    public final void testGetHierarchy() throws SaikuOlapException{
        SaikuHierarchy hier = olapMetaExplorer.getHierarchy(olapMetaExplorer.getAllCubes().get(0), "Department");
        
        assertNotNull(hier);
        
        assertEquals("Department", hier.getName());
    }

//    @Test
    public final void testGetHierarchyRootMembers() throws SaikuOlapException{
//    	olapMetaExplorer.getHierarchyRootMembers(olapMetaExplorer.getAllCubes().get(0), null);
    }
    
    @Test
    public final void testGetAllLevels() throws SaikuOlapException{
        
    	List<SaikuLevel> levels = olapMetaExplorer.getAllLevels(olapMetaExplorer.getAllCubes().get(0), "Department", "Department");
    	
    	assertNotNull(levels);
    	
    	assertEquals(2, levels.size());
    }
    
    @Test
    public final void testGetAllLevelsUniqueNameHierarchy() throws SaikuOlapException{
        
        List<SaikuLevel> levels = olapMetaExplorer.getAllLevels(olapMetaExplorer.getAllCubes().get(0), "Department", "[Department]");
        
        assertNotNull(levels);
        
        assertEquals(2, levels.size());
    }
    
    @Test
    public final void testGetAllMembers() throws SaikuOlapException{
    	List<SaikuMember> members = olapMetaExplorer.getAllMembers(olapMetaExplorer.getAllCubes().get(0), "Department", "Department", "Department Description");
    	
    	assertNotNull(members);
    	
    	assertEquals(12, members.size());
    }
    
    @Test
    public final void testGetMemeberChildren() throws SaikuOlapException{

    	List<SaikuMember> members = olapMetaExplorer.getMemberChildren(olapMetaExplorer.getAllCubes().get(0), "[Department].[All Departments]");
    	
    	assertNotNull(members);
    	
    	assertEquals(12, members.size());
    }
    
    @Test
    public final void testGetAllMeasures() throws SaikuOlapException{
    	List<SaikuMember> members = olapMetaExplorer.getAllMeasures(olapMetaExplorer.getAllCubes().get(0));
    	
    	assertNotNull(members);
    	
    	assertEquals(4, members.size());
    }
    
    @Test
    public final void testGetMemeber() throws SaikuOlapException{
    	SaikuMember member = olapMetaExplorer.getMember(olapMetaExplorer.getAllCubes().get(0), "[Department].[All Departments]");
    	
    	assertNotNull(member);
    	assertEquals("[Department].[All Departments]", member.getUniqueName());
    }
    

    @Test
    public final void testGetAllMembersUniqueNameHierarchy() throws SaikuOlapException{
        List<SaikuMember> members = olapMetaExplorer.getAllMembers(olapMetaExplorer.getAllCubes().get(0), "Department", "[Department]", "Department Description");
        
        assertNotNull(members);
        
        assertEquals(12, members.size());
    }
    
    @Test
    public final void testGetAllMembersUniqueNameLevel() throws SaikuOlapException{
        List<SaikuMember> members = olapMetaExplorer.getAllMembers(olapMetaExplorer.getAllCubes().get(0), "Department", "Department", "[Department].[(All)]");
        
        assertNotNull(members);
        
        assertEquals(1, members.size());
    }
    

    
    
    @BeforeClass
public static void setup() throws IOException{
    AbstractServiceUtils ast = new AbstractServiceUtils();
    ast.initTestContext();
    IConnectionManager ic = new TConnectionManager();
    String returned = computeTestDataRoot(OlapMetaExplorerTest.class);
    File f = new File(System.getProperty("java.io.tmpdir")+"/files/");
    f.mkdir();
    IDatasourceManager ds = new ClassPathResourceDatasourceManager(System.getProperty("java.io.tmpdir")+"/files/");
    InputStream inputStream= OlapMetaExplorerTest.class.getResourceAsStream("connection.properties");
    try {
        testProps.load(inputStream);
    } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
    }
    ds.setDatasource(new SaikuDatasource("test", SaikuDatasource.Type.OLAP, testProps));
    ic.setDataSourceManager(ds);
    olapMetaExplorer = new OlapMetaExplorer(ic);

}
    public static String computeTestDataRoot(Class anyTestClass) throws IOException {
        
        //create a temp file
        File temp = File.createTempFile("temp-file-name", ".tmp"); 

        System.out.println("Temp file : " + temp.getAbsolutePath());

    //Get tempropary file path
        String absolutePath = temp.getAbsolutePath();
        String tempFilePath = absolutePath.
            substring(0,absolutePath.lastIndexOf(File.separator));
        return tempFilePath+"/";
      }
}
