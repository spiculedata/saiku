/* Copyright (C) OSBI Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by OSBI LTD, 2014
 */

package org.saiku.web.rest.resources;

import org.saiku.service.license.ILicenseUtils;
import org.saiku.database.Database;
import org.saiku.service.license.Base64Coder;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.objects.UserList;

import com.qmino.miredot.annotations.ReturnType;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;

/**
 * Saiku license information resource.
 *
 * @since 3.0
 * @author tbarber
 */
@Component
@Path("/saiku/api/license")
public class License {

  private ILicenseUtils licenseUtils;
  private UserService userService;

  public ILicenseUtils getLicenseUtils() {
    return licenseUtils;
  }

  public void setLicenseUtils(ILicenseUtils licenseUtils) {
    this.licenseUtils = licenseUtils;
  }

  private Database databaseManager;

  public Database getDatabaseManager() {
    return databaseManager;
  }

  public void setDatabaseManager(Database databaseManager) {
    this.databaseManager = databaseManager;
  }

  public void setUserService(UserService us) {
    userService = us;
  }

  /**
   * Get the saiku
   * @summary Get the Saiku License installed on the current server
   * @return A response containing a license object.
   */
  @GET
  @Produces({ "application/json" })
  public Response getLicense() {
    try {
      return Response.ok().entity(licenseUtils.getLicense()).build();
    } catch (IOException | RepositoryException | ClassNotFoundException e) {
      e.printStackTrace();
    }
    return Response.serverError().build();
  }

  private static final int SIZE = 2048;


  /**
   * Upload a new license to the Saiku server.
   * @summary Upload a new license
   * @param is A license encapsulated in an input stream
   * @return An acknowledgement as to whether the server installation was successful.
   */
  @POST
  @Consumes("application/x-java-serialized-object")
  @Produces("text/plain")
  @ReturnType("java.lang.String")
  public Response saveLicense(InputStream is) {
    ObjectInputStream si = null;
    byte[] sig;
    byte[] data = null;
    try {
      si = new ObjectInputStream(is);
    } catch (IOException e) {
      e.printStackTrace();
    }

    try {
      int sigLength = si.readInt();
      sig = new byte[sigLength];
      si.read(sig);

      ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
      byte[] buf = new byte[SIZE];
      int len;
      while ((len = si.read(buf)) != -1) {
        dataStream.write(buf, 0, len);
      }
      dataStream.flush();
      data = dataStream.toByteArray();
      dataStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      try {
        si.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }


    getLicenseUtils().setLicense(new String(Base64Coder.encode(data)));

    return Response.ok("License Upload Successful").build();
  }

  /**
   * Validate the license installed on the server.
   * @summary License validation
   * @return A response indicating whether the operation was successful.
   */
  @GET
  @Path("/validate")
  @Produces({ "text/plain" })
  @ReturnType("java.lang.String")
  public Response validateLicense() {
//    if(!userService.isAdmin()){
//      return Response.status(Response.Status.FORBIDDEN).build();
//    }
//    try {
//      licenseUtils.validateLicense();
//    } catch (IOException e) {
//      e.printStackTrace();
//      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
//                     .entity(e.getLocalizedMessage()).build();
//    } catch (ClassNotFoundException e) {
//      e.printStackTrace();
//      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
//                     .entity(e.getLocalizedMessage()).build();
//    } catch (LicenseException e) {
//      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
//                     .entity(e.getLocalizedMessage()).build();
//    } catch (RepositoryException e) {
//      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
//                     .entity("Could not find license file").build();
//    } catch (Exception e) {
//      e.printStackTrace();
//    }
    return Response.ok().entity("Valid License").build();


  }



  /**
   * Get the current user list from the server.
   * @summary Get the user list
   * @return A list of users.
   */
  @GET
  @Path("/users")
  @Produces({"application/json"})
  @ReturnType("java.util.ArrayList<UserList>")
  public Response getUserlist(){
    if(!userService.isAdmin()){
      return Response.status(Response.Status.FORBIDDEN).build();
    }
    try {
      List<String> l = getAuthUsers();
      if(l!=null) {
        List<UserList> ul = new ArrayList();
        int i = 0;
        for (String l2 : l) {
          ul.add(new UserList(l2, i));
          i++;
        }
        return Response.ok().entity(ul).build();
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return null;
  }


  /**
   * Get the valid users from the database.
   * @return a list of usernames
   * @throws SQLException
   */
  private List<String> getAuthUsers() throws SQLException {
    return databaseManager.getUsers();
  }

  /**
   * Get the user quota for existing users with no license
   * @return a list of user quota.
   */
  @GET
  @Produces("application/json")
  @Path("/quota")
  @ReturnType("java.util.List<UserQuota>")
  public Response getUserQuota(){
    if(!userService.isAdmin()){
      return Response.status(Response.Status.FORBIDDEN).build();
    }
    return Response.ok().entity(100000000).build();
  }
}
