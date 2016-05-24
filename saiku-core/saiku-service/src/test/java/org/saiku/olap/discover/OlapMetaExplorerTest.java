package org.saiku.olap.discover;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.BeforeClass;
import org.junit.Test;
import org.olap4j.OlapConnection;
import org.saiku.datasources.connection.IConnectionManager;
import org.saiku.datasources.connection.SimpleConnectionManager;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.olap.dto.SaikuConnection;
import org.saiku.olap.dto.SaikuCube;
import org.saiku.olap.dto.SaikuDimension;
import org.saiku.olap.dto.SaikuHierarchy;
import org.saiku.olap.dto.SaikuLevel;
import org.saiku.olap.dto.SaikuMember;
import org.saiku.olap.dto.SimpleCubeElement;
import org.saiku.olap.util.exception.SaikuOlapException;
import org.saiku.service.datasource.ClassPathResourceDatasourceManager;
import org.saiku.service.datasource.IDatasourceManager;
import static org.hamcrest.core.StringStartsWith.*;

import static org.junit.Assert.*;


public class OlapMetaExplorerTest {

    private static OlapMetaExplorer olapMetaExplorer;

    /**
     * Test that you can fetch all available connections.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetAllConnections() throws SaikuOlapException {
        List<SaikuConnection> output = olapMetaExplorer.getAllConnections();

        assertNotNull(output);

        assertEquals(1, output.size());
        assertEquals("test", output.get(0).getName());
    }

    /**
     * Test that you can get a single connection.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetConnectionSuccess() throws SaikuOlapException {
        SaikuConnection output = olapMetaExplorer.getConnection("test");

        assertNotNull(output);

        assertEquals("test", output.getName());
    }

    /**
     * Test what happens when you call an non existant connection.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetConnectionFailure() throws SaikuOlapException {
        SaikuConnection output = null;
        try {
            output = olapMetaExplorer.getConnection("noname");
        } catch (Exception e) {
            //Connection Failure shouldn't throw an NPE it should throw a nicer error.
            assertEquals("Cannot find connection: (noname)", e.getMessage());
        }
        assertNull(output);
    }

    @Test
    public final void testGetConnections() throws SaikuOlapException {
        List<String> list = new ArrayList<>();
        list.add("test");
        List<SaikuConnection> connections = olapMetaExplorer.getConnections(list);

        assertNotNull(connections);

        assertEquals("test", connections.get(0).getName());
    }

    @Test
    public final void testGetNativeConnection() throws SaikuOlapException {
        OlapConnection output = olapMetaExplorer.getNativeConnection("test");

        assertNotNull(output);
    }

    /**
     * Make sure you can grab a cube from a specified connection.
     */
    @Test
    public final void testGetCubesSingleConnection() throws SaikuOlapException {
        List<SaikuCube> output = olapMetaExplorer.getCubes("test");

        assertNotNull(output);

        assertEquals("HR", output.get(0).getName());
    }

    /**
     * Make sure you can grab a cube from a specified connection.
     */
    @Test
    public final void testGetCubesMultipleConnections() throws SaikuOlapException {
        List<String> cubes = new ArrayList<>();
        cubes.add("test");
        List<SaikuCube> output = olapMetaExplorer.getCubes(cubes);

        assertNotNull(output);

        assertEquals("HR", output.get(0).getName());
    }

    public final void testGetCubesMultipleConnectionsConnection() {
    }


    /**
     * Test to make sure you can retrieve all the cubes from a schema.
     */
    @Test
    public final void testGetAllCubes() throws SaikuOlapException {
        List<SaikuCube> output = olapMetaExplorer.getAllCubes();

        assertNotNull(output);

        assertEquals(6, output.size());

        for (SaikuCube anOutput : output) {
            assertEquals("FoodMart", anOutput.getCatalog());
            anOutput.getName();
            assertEquals("test", anOutput.getConnection());
            assertEquals("FoodMart", anOutput.getSchema());
            assertThat(anOutput.getUniqueName(), startsWith("[test].[FoodMart].[FoodMart]."));
        }
    }

    /**
     * Test to make sure that the cubes are returned in the same order.
     */
    @Test
    public final void testCubeReturnOrder() throws SaikuOlapException {
        List<SaikuCube> output = olapMetaExplorer.getAllCubes();

        assertNotNull(output);

        List<String> names = Arrays.asList("HR", "Sales", "Sales 2", "Store", "Warehouse", "Warehouse and Sales");

        List<String> actual = new ArrayList<>();
        for (SaikuCube cube:output) {
            actual.add(cube.getName());
        }
        assertEquals(names, actual);
    }

    public final void testGetNativeCube() {


    }

    /**
     * Test to make sure you can get all the dimensions in a cube.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetAllDimensions() throws SaikuOlapException {
        List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();

        List<SaikuDimension> dims = olapMetaExplorer.getAllDimensions(cubes.get(0));

        assertNotNull(dims);
        assertEquals(4, dims.size());
    }

    /**
     * Test to make sure you can get a single dimension in a cube.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetDimension() throws SaikuOlapException {

        List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();

        SaikuDimension dim = olapMetaExplorer.getDimension(cubes.get(0), "Department");

        assertNotNull(dim);

        assertEquals("Department", dim.getName());
    }


    /**
     * Test to make sure you can get a single dimension in a cube.
     *
     * @throws SaikuOlapException
     */
    @Test
    public final void testGetDimensionNull() throws SaikuOlapException {
        List<SaikuCube> cubes = olapMetaExplorer.getAllCubes();

        SaikuDimension dim = olapMetaExplorer.getDimension(cubes.get(0), "No dimension");

        assertNull(dim);
    }

    @Test
    public final void testGetAllHierarchies() throws SaikuOlapException {

        List<SaikuHierarchy> hier = olapMetaExplorer.getAllHierarchies(olapMetaExplorer.getAllCubes().get(0));

        assertNotNull(hier);

        assertEquals(21, hier.size());
    }

    @Test
    public final void testGetHierarchy() throws SaikuOlapException {
        SaikuHierarchy hier = olapMetaExplorer.getHierarchy(olapMetaExplorer.getAllCubes().get(0), "Department");

        assertNotNull(hier);

        assertEquals("Department", hier.getName());
    }

    @Test
    public final void testGetHierarchyRootMembers() throws SaikuOlapException {
        List<SaikuMember> rootMembers = olapMetaExplorer.getHierarchyRootMembers(olapMetaExplorer.getAllCubes().get(0), "Department");
        assertNotNull(rootMembers);
    }

    @Test
    public final void testGetAllLevels() throws SaikuOlapException {

        List<SaikuLevel> levels = olapMetaExplorer.getAllLevels(olapMetaExplorer.getAllCubes().get(0), "Department", "Department");

        assertNotNull(levels);

        assertEquals(2, levels.size());
    }

    @Test
    public final void testGetAllLevelsUniqueNameHierarchy() throws SaikuOlapException {

        List<SaikuLevel> levels = olapMetaExplorer.getAllLevels(olapMetaExplorer.getAllCubes().get(0), "Department", "[Department].[Department]");

        assertNotNull(levels);

        assertEquals(2, levels.size());
    }

    @Test
    public final void testGetAllMembers() throws SaikuOlapException {
        List<SimpleCubeElement> members = olapMetaExplorer.getAllMembers(olapMetaExplorer.getAllCubes().get(0), "Department", "Department Description");

        assertNotNull(members);

        assertEquals(12, members.size());
    }

    @Test
    public final void testGetMemberChildren() throws SaikuOlapException {

        List<SaikuMember> members = olapMetaExplorer.getMemberChildren(olapMetaExplorer.getAllCubes().get(0), "[Department].[All Departments]");

        assertNotNull(members);

        assertEquals(12, members.size());
    }

    @Test
    public final void testGetAllMeasures() throws SaikuOlapException {
        List<SaikuMember> members = olapMetaExplorer.getAllMeasures(olapMetaExplorer.getAllCubes().get(0));

        assertNotNull(members);

        assertEquals(5, members.size());
    }

    @Test
    public final void testGetMember() throws SaikuOlapException {
        SaikuMember member = olapMetaExplorer.getMember(olapMetaExplorer.getAllCubes().get(0), "[Department].[All Departments]");

        assertNotNull(member);
        assertEquals("[Department].[Department].[All Departments]", member.getUniqueName());
    }

    @Test
    public final void testGetAllMembersUniqueNameLevel() throws SaikuOlapException {
        List<SimpleCubeElement> members = olapMetaExplorer.getAllMembers(olapMetaExplorer.getAllCubes().get(0), "Department", "[Department].[Department].[(All)]");

        assertNotNull(members);

        assertEquals(1, members.size());
    }


    @BeforeClass
    public static void setup() throws Exception {
        /*File f = new File(System.getProperty("java.io.tmpdir") + "/files/");
        f.mkdir();
        IDatasourceManager ds = new ClassPathResourceDatasourceManager(System.getProperty("java.io.tmpdir") + "/files/");
        InputStream inputStream = OlapMetaExplorerTest.class.getResourceAsStream("../../connection.properties");
        Properties testProps = new Properties();
        testProps.load(inputStream);
        ds.setDatasource(new SaikuDatasource("test", SaikuDatasource.Type.OLAP, testProps));

        IConnectionManager ic = new SimpleConnectionManager();
        ic.setDataSourceManager(ds);

        olapMetaExplorer = new OlapMetaExplorer(ic);*/
    }
}
