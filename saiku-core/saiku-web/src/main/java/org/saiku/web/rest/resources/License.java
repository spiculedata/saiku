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
import org.saiku.database.Database;
import org.saiku.service.user.UserService;
import org.saiku.web.rest.objects.UserList;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saiku license information resource.
 *
 * @since 3.0
 * @author tbarber
 */
@Component
@RestController
@RequestMapping("/saiku/api/license")
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
    @GetMapping(path = "/validate", produces = MediaType.TEXT_PLAIN_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> validateLicense() {
        return ResponseEntity.ok("Valid License");
    }

    /**
     * Get the current user list from the server.
     * @summary Get the user list
     * @return A list of users.
     */
    @GetMapping(path = "/usercount", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.ArrayList<UserList>")
    public ResponseEntity<?> getUserCount() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            List<String> l = getAuthUsers();
            if (l != null) {
                List<UserList> ul = new ArrayList<>();
                int i = 0;
                for (String l2 : l) {
                    ul.add(new UserList(l2, i));
                    i++;
                }
                return ResponseEntity.ok(ul.size());
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.ok(0);
        }
        return ResponseEntity.ok(0);
    }

    /**
     * Get the current user list from the server.
     * @summary Get the user list
     * @return A list of users.
     */
    @GetMapping(path = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.ArrayList<UserList>")
    public ResponseEntity<?> getUserlist() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            List<String> l = getAuthUsers();
            if (l != null) {
                List<UserList> ul = new ArrayList<>();
                int i = 0;
                for (String l2 : l) {
                    ul.add(new UserList(l2, i));
                    i++;
                }
                return ResponseEntity.ok(ul);
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
    @PostMapping(path = "/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> createUserList(@RequestBody List<UserList> l) {
        try {
            List<String> l3 = new ArrayList<>();
            for (UserList l2 : l) {
                l3.add(l2.getName());
            }
            addUsers(l3);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ResponseEntity.ok("List created");
    }

    /**
     * Update the list of users with new users.
     * @summary Update user list
     * @param l A list of UserList objects
     * @return A response indicating whether the operation was successful.
     */
    @PutMapping(path = "/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> updateUserList(@RequestBody List<UserList> l) {
        try {
            List<String> l3 = new ArrayList<>();
            for (UserList l2 : l) {
                l3.add(l2.getName());
            }
            updateUsers(l3);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return ResponseEntity.ok("List updated");
    }

    /**
     * Delete the user list from the server.
     * @summary Delete user list.
     * @return A response indicating whether the operation was successful.
     */
    @DeleteMapping(path = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.lang.String")
    public ResponseEntity<?> deleteUserlist() {

        try {
            List<String> l = getAuthUsers();
            List<UserList> ul = new ArrayList<>();
            int i = 0;
            for (String l2 : l) {
                ul.add(new UserList(l2, i));
                i++;
            }
            return ResponseEntity.ok(ul);
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
    @GetMapping(path = "/quota", produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.List<UserQuota>")
    public ResponseEntity<?> getUserQuota() {
        if (!userService.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(100000000);
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
