/* Copyright (C) OSBI Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by OSBI LTD, 2014
 */

package org.saiku.web.rest.resources;

import com.qmino.miredot.annotations.ReturnType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import org.saiku.database.Database;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.objects.UserList;
import org.springframework.stereotype.Component;

/**
 * Saiku license information resource.
 *
 * @since 3.0
 * @author tbarber
 */
@Component
@Path("/saiku/api/license")
public class License {

    private UserService userService;

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
     * Validate the license installed on the server.
     * @summary License validation
     * @return A response indicating whether the operation was successful.
     */
    @GET
    @Path("/validate")
    @Produces({"text/plain"})
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
    @Path("/usercount")
    @Produces({"application/json"})
    @ReturnType("java.util.ArrayList<UserList>")
    public Response getUserCount() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            List<String> l = getAuthUsers();
            if (l != null) {
                List<UserList> ul = new ArrayList();
                int i = 0;
                for (String l2 : l) {
                    ul.add(new UserList(l2, i));
                    i++;
                }
                return Response.ok().entity(ul.size()).build();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return Response.ok().entity(0).build();
        }
        return Response.ok().entity(0).build();
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
    public Response getUserlist() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            List<String> l = getAuthUsers();
            if (l != null) {
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
     * Upload a user list to the server.
     * @summary Upload user list
     * @param l A List of UserList objects
     * @return A response indicating whether the operation was successful.
     */
    @POST
    @Path("/users")
    @Produces({"text/plain"})
    @Consumes({"application/json"})
    @ReturnType("java.lang.String")
    public Response createUserList(List<UserList> l) {
        try {
            List<String> l3 = new ArrayList<>();
            for (UserList l2 : l) {
                l3.add(l2.getName());
            }
            addUsers(l3);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Response.ok().entity("List created").build();
    }

    /**
     * Update the list of users with new users.
     * @summary Update user list
     * @param l A list of UserList objects
     * @return A response indicating whether the operation was successful.
     */
    @PUT
    @Path("/users")
    @Produces({"text/plain"})
    @Consumes({"application/json"})
    @ReturnType("java.lang.String")
    public Response updateUserList(List<UserList> l) {
        try {
            List<String> l3 = new ArrayList<>();
            for (UserList l2 : l) {
                l3.add(l2.getName());
            }
            updateUsers(l3);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Response.ok().entity("List updated").build();
    }

    /**
     * Delete the user list from the server.
     * @summary Delete user list.
     * @return A response indicating whether the operation was successful.
     */
    @DELETE
    @Path("/users")
    @Produces({"application/json"})
    @ReturnType("java.lang.String")
    public Response deleteUserlist() {

        try {
            List<String> l = getAuthUsers();
            List<UserList> ul = new ArrayList<>();
            int i = 0;
            for (String l2 : l) {
                ul.add(new UserList(l2, i));
                i++;
            }
            return Response.ok().entity(ul).build();
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
    public Response getUserQuota() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok().entity(100000000).build();
    }

    /**
     * Add users to the database.
     * @param l List of usernames
     * @throws SQLException
     */
    public void addUsers(List<String> l) throws SQLException {
        databaseManager.addUsers(l);
    }

    /**
     * Add users to the database.
     * @param l List of usernames
     * @throws SQLException
     */
    public void updateUsers(List<String> l) throws SQLException {
        databaseManager.addUsers(l);
    }
}
