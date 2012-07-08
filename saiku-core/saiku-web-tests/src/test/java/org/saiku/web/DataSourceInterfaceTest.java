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

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import org.junit.Ignore;
import org.junit.Test;
import org.saiku.service.olap.OlapDiscoverService;

import static org.junit.Assert.assertEquals;

//import org.saiku.service.olap.OlapDiscoverService;

public class DataSourceInterfaceTest extends AbstractServiceTest {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    protected OlapDiscoverService olapDiscoverService = null;
   

	@Test
    public void testCheckDefaultConnection()
    {
	
    }
    
    @Test
    public void testApplicationWadl() {
/*
    	Client client = Client.create();
        client.setFollowRedirects(false);

        WebResource webResource = client.resource("http://localhost:9999/saiku");

        String applicationWadl = webResource.path("application.wadl").get(String.class);
        System.out.println(applicationWadl);
        assertTrue("Something wrong. Returned wadl length is not > 0",
                applicationWadl.length() > 0);*/
    }
    
    @Test
    public void testConvertDataSourcesToJson(){
    	Client client = Client.create();
        client.setFollowRedirects(false);

        WebResource webResource = client.resource("http://localhost:9999/saiku");
        String applicationWadl = webResource.path("/rest/saiku/bugg/datasources").accept("application/json").get(String.class);
        System.out.println(applicationWadl);
        assertEquals("[{\"connection\":\"TestConnection1\",\"cube\":\"Quadrant Analysis\",\"catalog\":\"SampleData\",\"schema\":\"SampleData\"},{\"connection\":\"TestConnection1\",\"cube\":\"SteelWheelsSales\",\"catalog\":\"SteelWheels\",\"schema\":\"SteelWheels\"}]", applicationWadl);
        
    }
    

    @Ignore
    @Test
    public void testConvertDataSourcesToXML(){
    	Client client = Client.create();
        client.setFollowRedirects(false);

        WebResource webResource = client.resource("http://localhost:9999/saiku");
        String applicationWadl = webResource.path("/rest/saiku/bugg/datasources").accept("application/xml").get(String.class);
        System.out.println(applicationWadl);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><items><datasource connection=\"TestConnection1\" cube=\"Quadrant Analysis\" catalog=\"SampleData\" schema=\"SampleData\"/><datasource connection=\"TestConnection1\" cube=\"SteelWheelsSales\" catalog=\"SteelWheels\" schema=\"SteelWheels\"/></items>", applicationWadl);
        
    }


    

}
