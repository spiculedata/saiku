/*
 *   Copyright 2015 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.web.rest.resources;

import com.qmino.miredot.annotations.ReturnType;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.saiku.database.dto.MondrianSchema;
import org.saiku.database.dto.SaikuUser;
import org.saiku.datasources.datasource.SaikuDatasource;
import org.saiku.log.LogExtractor;
import org.saiku.repository.RepositoryException;
import org.saiku.service.datasource.DatasourceService;
import org.saiku.service.datasource.IDatasourceManager;
import org.saiku.service.importer.JujuSource;
import org.saiku.service.olap.OlapDiscoverService;
import org.saiku.service.user.UserService;
import org.saiku.service.util.exception.SaikuDataSourceException;
import org.saiku.service.util.exception.SaikuServiceException;
import org.saiku.web.rest.objects.DataSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdminResource for the Saiku 3.0+ Admin console
 */
@Path("/saiku/admin")
// saiku#780 defence-in-depth: every endpoint under /saiku/admin requires the
// ADMIN role at the JAX-RS layer, not just the Spring URL filter. The
// RolesAllowedDynamicFeature in SaikuJerseyApplication is what wires this up.
@RolesAllowed("ROLE_ADMIN")
public class AdminResource {

    private DatasourceService datasourceService;

    private UserService userService;
    private static final Logger log = LoggerFactory.getLogger(DataSourceResource.class);
    private OlapDiscoverService olapDiscoverService;
    private LogExtractor logExtractor;

    /**
     * saiku#1514: why a password write here is refused rather than acknowledged.
     *
     * <p>Authentication resolves through {@code authenticationManager}, whose only providers are
     * file-backed {@code <security:user-service properties=.../>} beans
     * (applicationContext-spring-security-memory.xml:32,45,48). This resource writes to H2 via
     * {@link UserService} → {@code JdbcUserDAO}. No {@code AuthenticationProvider} reads that store,
     * so a password set here has never had any effect on who can log in — the endpoint simply
     * returned 200 and the superseded credential kept working.
     *
     * <p>The wording is deliberately the launcher's, taken from the boot-time FATAL banner it
     * prints on a default-credentials refusal ({@code SaikuLauncher.enforceDefaultCredentialPolicy})
     * and from dist/README's htpasswd recipe, so operators meet one vocabulary for rotation rather
     * than two. It cannot be shared as a constant: this module is {@code saiku-web}, and the reactor
     * runs saiku-core → saiku-webapp → saiku-launcher, so depending on the launcher would invert
     * that order. If the banner is ever reworded, reword this with it.
     */
    private static final String PASSWORD_WRITE_UNSUPPORTED = String.join(
            "\n",
            "Saiku's bundled authentication reads users.properties; this panel writes to a",
            "separate store that authentication never consults, so a password set here would",
            "have no effect and the superseded one would keep working. Set the password one of",
            "these ways instead, then restart:",
            "  * Set an admin password (no rebuild — recommended):",
            "      SAIKU_ADMIN_PASSWORD=<a-strong-password>",
            "  * Or rotate it in users.properties (bcrypt):",
            "      htpasswd -nbBC 12 <user> <newpassword>",
            "  * Or replace the in-memory auth with LDAP / OAuth / SAML",
            "    (applicationContext-spring-security-memory.xml).",
            "See https://github.com/spiculedata/saiku/issues/1514");

    public LogExtractor getLogExtractor() {
        return logExtractor;
    }

    public void setLogExtractor(LogExtractor logExtractor) {
        this.logExtractor = logExtractor;
    }

    public void setOlapDiscoverService(OlapDiscoverService olapDiscoverService) {
        this.olapDiscoverService = olapDiscoverService;
    }

    public void setDatasourceService(DatasourceService ds) {
        datasourceService = ds;
    }

    public void setUserService(UserService us) {
        userService = us;
    }

    private IDatasourceManager repositoryDatasourceManager;

    public IDatasourceManager getRepositoryDatasourceManager() {
        return repositoryDatasourceManager;
    }

    public void setRepositoryDatasourceManager(IDatasourceManager repositoryDatasourceManager) {
        this.repositoryDatasourceManager = repositoryDatasourceManager;
    }
    /**
     * Get all the available data sources on the platform.
     * @return A response containing a list of datasources.
     * @summary Get Saiku Datasources
     */
    @GET
    @Produces({"application/json"})
    @Path("/datasources")
    @ReturnType("java.lang.List<SaikuDatasource>")
    public Response getAvailableDataSources() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        List<DataSourceMapper> l = new ArrayList<>();

        try {
            for (SaikuDatasource d : datasourceService
                    .getDatasources(userService.getCurrentUserRoles())
                    .values()) {
                l.add(new DataSourceMapper(d));
            }
            return Response.ok().entity(l).build();
        } catch (SaikuServiceException e) {
            log.error(this.getClass().getName(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Update a specific Saiku data source.
     * @summary Update data source
     * @param json The Json data source object
     * @param id The datasource id.
     * @return A response containing the datasource.
     */
    @PUT
    @Produces({"application/json"})
    @Consumes({"application/json"})
    @Path("/datasources/{id}")
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public Response updateDatasource(DataSourceMapper json, @PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        try {
            datasourceService.addDatasource(json.toSaikuDataSource(), true, userService.getCurrentUserRoles());
            return Response.ok().type("application/json").entity(json).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Refresh a Saiku data source.
     * @summary Refresh data source
     * @param id The data source id.
     * @return A response containing the data source definition.
     */
    @GET
    @Produces({"application/json"})
    @Path("/datasources/{id}/refresh")
    @ReturnType("java.util.List<SaikuConnection>")
    public Response refreshDatasource(@PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        try {
            olapDiscoverService.refreshConnection(id);
            return Response.ok()
                    .entity(olapDiscoverService.getConnection(id))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            log.error(this.getClass().getName(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Create a data source on the Saiku server.
     * @summary Create data source
     * @param json The json data source object
     * @return A response containing the data source object
     */
    @POST
    @Produces({"application/json"})
    @Consumes({"application/json"})
    @Path("/datasources")
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public Response createDatasource(DataSourceMapper json) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        try {
            datasourceService.addDatasource(json.toSaikuDataSource(), false, userService.getCurrentUserRoles());
            return Response.ok().entity(json).type("application/json").build();
        } catch (Exception e) {
            log.error("Error adding data source", e);
            return Response.serverError()
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Delete data source from the Saiku server
     * @summary Delete data source
     * @param id The data source ID
     * @return A response containing a list of data sources remaining on the platform.
     */
    @DELETE
    @Path("/datasources/{id}")
    public Response deleteDatasource(@PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        datasourceService.removeDatasource(id);

        return Response.ok()
                .type("application/json")
                .entity(datasourceService.getDatasources(userService.getCurrentUserRoles()))
                .build();
    }

    /**
     * Get all the available schema.
     * @summary Get Saiku schema.
     * @return A list of schema
     */
    @GET
    @Produces({"application/json"})
    @Path("/schema")
    @ReturnType("java.util.List<MondrianSchema>")
    public Response getAvailableSchema() {

        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok().entity(datasourceService.getAvailableSchema()).build();
    }

    /**
     * Upload a new schema to the Saiku server.
     * @summary Upload schema
     * @param is Input stream (file form data param)
     * @param detail Detail (file form data param)
     * @param name Schema name
     * @param id Schema id
     * @return A response containing a list of available schema.
     */
    @PUT
    @Produces({"application/json"})
    @Consumes("multipart/form-data")
    @Path("/schema/{id}")
    @ReturnType("java.util.List<MondrianSchema>")
    public Response uploadSchemaPut(
            @FormDataParam("file") InputStream is,
            @FormDataParam("file") FormDataContentDisposition detail,
            @FormDataParam("name") String name,
            @PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        String path = "/datasources/" + name + ".xml";
        String schema = getStringFromInputStream(is);
        try {
            datasourceService.addSchema(schema, path, name);
            return Response.ok().entity(datasourceService.getAvailableSchema()).build();
        } catch (Exception e) {
            log.error("Error uploading schema: " + name, e);
            return Response.serverError()
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Upload new schema to the Saiku server
     * @summary Upload new schema
     * @summary Upload schema
     * @param is Input stream (file form data param)
     * @param detail Detail (file form data param)
     * @param name Schema name
     * @param id Schema id
     * @return A response containing a list of available schema.
     */
    @POST
    @Produces({"application/json"})
    @Consumes("multipart/form-data")
    @Path("/schema/{id}")
    @ReturnType("java.util.List<MondrianSchema>")
    public Response uploadSchema(
            @FormDataParam("file") InputStream is,
            @FormDataParam("file") FormDataContentDisposition detail,
            @FormDataParam("name") String name,
            @PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        String path = "/datasources/" + name + ".xml";
        String schema = getStringFromInputStream(is);
        try {
            datasourceService.addSchema(schema, path, name);
            return Response.ok().entity(datasourceService.getAvailableSchema()).build();
        } catch (Exception e) {
            log.error("Error uploading schema: " + name, e);
            return Response.serverError()
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Updates the locale parameter of the datasource
     * @param locale: the new locale for the data source
     * @param datasourceName: ID of the data source whose locale should be changed
     * @return: Response indicating success or fail
     */
    @PUT
    @Produces({"application/json"})
    @Consumes({"application/json"})
    @Path("/datasources/{datasourceName}/locale")
    @ReturnType("org.saiku.web.rest.objects.DataSourceMapper")
    public Response updateDatasourceLocale(String locale, @PathParam("datasourceName") String datasourceName) {
        try {
            boolean overwrite = true;
            SaikuDatasource saikuDatasource = datasourceService.getDatasource(datasourceName);
            datasourceService.setLocaleOfDataSource(saikuDatasource, locale);
            datasourceService.addDatasource(saikuDatasource, overwrite, userService.getCurrentUserRoles());
            return Response.ok()
                    .type("application/json")
                    .entity(new DataSourceMapper(saikuDatasource))
                    .build();
        } catch (SaikuDataSourceException e) {
            return Response.ok()
                    .type("application/json")
                    .entity(e.getLocalizedMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Get existing Saiku users from the Saiku server.
     * @summary Get Saiku users.
     * @return A list of available users.
     */
    @GET
    @Produces({"application/json"})
    @Path("/users")
    @ReturnType("java.util.List<SaikuUser>")
    public Response getExistingUsers() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok().entity(userService.getUsers()).build();
    }

    /**
     * Delete a schema from the Saiku server.
     * @summary Delete a schema.
     * @param id The schema ID.
     * @return A response containing available schema.
     */
    @DELETE
    @Path("/schema/{id}")
    @ReturnType("java.util.List<MondrianSchema>")
    public Response deleteSchema(@PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        datasourceService.removeSchema(id);
        return Response.status(Response.Status.NO_CONTENT)
                .entity(datasourceService.getAvailableSchema())
                .build();
    }

    /**
     * Get Saved Schema By ID
     * @param id
     * @return a schema file.
     */
    @GET
    @Path("/schema/{id}")
    @Produces("application/xml")
    @ReturnType("MondrianSchema")
    public Response getSavedSchema(@PathParam("id") String id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        String p = "";
        for (MondrianSchema s : datasourceService.getAvailableSchema()) {
            if (s.getName().equals(id)) {

                try {
                    p = repositoryDatasourceManager.getInternalFileData(s.getPath());
                } catch (RepositoryException e) {
                    Response.serverError().entity(e.getLocalizedMessage()).build();
                }
                break;
            }
        }

        return Response.ok(p.getBytes(), MediaType.APPLICATION_OCTET_STREAM)
                .header("content-disposition", "attachment; filename = " + id)
                .build();
    }

    /**
     * Import a legacy data source into the Saiku server.
     * @summary Import legacy datasource.
     * @return A status 200.
     */
    @GET
    @Path("/datasource/import")
    public Response importLegacyDatasources() {

        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        datasourceService.importLegacyDatasources();
        return Response.ok().build();
    }

    /**
     * Import legacy schema.
     * @summary Import legacy schema
     * @return A status 200
     */
    @GET
    @Path("/schema/import")
    public Response importLegacySchema() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        datasourceService.importLegacySchema();
        return Response.ok().build();
    }

    /**
     * Import legacy users into the Saiku server/
     * @summary Import legacy users.
     * @return A status 200.
     */
    @GET
    @Path("/users/import")
    public Response importLegacyUsers() {

        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        datasourceService.importLegacyUsers();
        return Response.ok().build();
    }

    /**
     * Get user details for a user in the Saiku server.
     * @summary Get user details.
     * @param id The user ID.
     * @return A response containing the user details object for the selected user.
     */
    @GET
    @Produces({"application/json"})
    @Path("/users/{id}")
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public Response getUserDetails(@PathParam("id") int id) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok().entity(userService.getUser(id)).build();
    }

    /**
     * Update a users user details on the Saiku server.
     *
     * <p>Email and roles are updated as before. A password change is refused with 501 — see
     * {@link #PASSWORD_WRITE_UNSUPPORTED}. The refusal is unconditional and applies to every
     * user, not just {@code admin}: no deployment mode exists in which a password written here
     * reaches the authenticator, so there is nothing to detect and nothing to condition on.
     *
     * @summary Update user details
     * @param jsonString SaikuUser object.
     * @param userName The username for the user to be updated.
     * @return A response containing a user object, or 501 when a password change is requested.
     */
    @PUT
    @Produces({"application/json"})
    @Consumes("application/json")
    @Path("/users/{username}")
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public Response updateUserDetails(SaikuUser jsonString, @PathParam("username") String userName) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            if (jsonString.getPassword() == null || jsonString.getPassword().equals("")) {
                return Response.ok()
                        .entity(userService.updateUser(jsonString, false))
                        .build();
            } else {
                // saiku#1514: this used to call updateUser(jsonString, true), which bcrypt-hashes
                // into H2 — a store no AuthenticationProvider reads — and return 200. Refuse
                // instead, and say how to rotate for real. 501, not 4xx: the request is
                // well-formed and the caller is a legitimate admin; the capability does not exist
                // in this server (RFC 9110 §15.6.2).
                log.warn(
                        "Refused a password change for user '{}' via /admin/users — the admin panel"
                                + " cannot rotate credentials (saiku#1514).",
                        userName);
                return Response.status(Response.Status.NOT_IMPLEMENTED)
                        .type(MediaType.TEXT_PLAIN)
                        .entity(PASSWORD_WRITE_UNSUPPORTED)
                        .build();
            }
        } catch (IllegalArgumentException policy) {
            // Password-policy violation — surface as 400 so the UI can display.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(policy.getMessage())
                    .build();
        }
    }

    /**
     * Create user details on the Saiku server.
     *
     * <p>saiku#1514: deliberately NOT refused, unlike the password change in
     * {@link #updateUserDetails}. The created row is inert for authentication — the new account
     * cannot log in until it exists in users.properties — but it is not useless: it is what
     * {@code /saiku/api/users} ({@code UsersResource}) lists, which is the @-mention directory for
     * dashboard comments. Refusing here would remove the only way to add someone to that
     * directory, and it would have to be a blanket refusal rather than a password-only one:
     * {@link org.saiku.service.user.UserService#addUser} validates the password policy
     * unconditionally, so a create carries a mandatory password and there is no password-free
     * create to fall back to. Creating a user is therefore honest about what it does; only the
     * implication that the account can sign in is not, and the UI carries that caveat.
     *
     * @summary Create user details
     * @param jsonString SaikuUser object
     * @return A response containing the user object.
     */
    @POST
    @Produces({"application/json"})
    @Consumes({"application/json"})
    @Path("/users")
    @ReturnType("org.saiku.database.dto.SaikuUser")
    public Response createUserDetails(SaikuUser jsonString) {

        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            return Response.ok().entity(userService.addUser(jsonString)).build();
        } catch (IllegalArgumentException policy) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(policy.getMessage())
                    .build();
        }
    }

    /**
     * Delete a user from the Saiku server.
     * @summary Delete user.
     * @param username The username to remove
     * @return A status 200.
     */
    @DELETE
    @Produces({"application/json"})
    @Path("/users/{username}")
    public Response removeUser(@PathParam("username") String username) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        userService.removeUser(username);
        return Response.ok().build();
    }

    /**
     * Get string from an input stream object.
     * @param is The input stream to convert.
     * @return A string representation of the input stream.
     */
    private static String getStringFromInputStream(InputStream is) {

        BufferedReader br = null;
        StringBuilder sb = new StringBuilder();

        String line;
        try {

            br = new BufferedReader(new InputStreamReader(is));
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }

        } catch (IOException e) {
            log.error("IO Exception when reading from input stream", e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    log.error("IO Exception closing input stream", e);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Get the Saiku server version.
     * @summary Get Saiku version.
     * @return A Response containing the Saiku server version.
     */
    @GET
    @Produces("text/plain")
    @Path("/version")
    @ReturnType("java.lang.String")
    // saiku#1004: per-method override of the class-level
    // @RolesAllowed("ROLE_ADMIN") so the bootstrap fetch from the
    // SvelteKit UI's root layout doesn't 403 for ROLE_USER members
    // (which the global 403 interceptor would turn into a misleading
    // "Session ended" modal before Studio renders). The endpoint just
    // reads a packaged version.properties — no auth-sensitive data.
    @PermitAll
    public Response getVersion() {
        Properties prop = new Properties();
        String version = "";
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        // try-with-resources so the version.properties stream is always closed (saiku#1191).
        try (InputStream is = classloader.getResourceAsStream("org/saiku/web/rest/resources/version.properties")) {
            prop.load(is);
            version = prop.getProperty("VERSION");
        } catch (IOException ex) {
            log.error("IO Exception when reading input stream", ex);
        }
        return Response.ok().entity(version).type("text/plain").build();
    }

    /**
     * Backup the Saiku server repository.
     * @summary Backup the repository.
     * @return A Zip file containing the backup.
     */
    @GET
    @Produces("application/zip")
    @Path("/backup")
    public StreamingOutput getBackup() {
        if (!userService.isAdmin()) {
            return null;
        }
        return new StreamingOutput() {
            public void write(OutputStream output) throws IOException, WebApplicationException {
                BufferedOutputStream bus = new BufferedOutputStream(output);
                bus.write(datasourceService.exportRepository());
            }
        };
    }

    /**
     * Restore the repository on a Saiku server.
     * @summary Restore a backup
     * @param is The input stream
     * @param detail The file detail
     * @return A status 200.
     */
    @POST
    @Produces("text/plain")
    @Consumes("multipart/form-data")
    @Path("/restore")
    public Response postRestore(
            @FormDataParam("file") InputStream is, @FormDataParam("file") FormDataContentDisposition detail) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            byte[] bytes = IOUtils.toByteArray(is);
            datasourceService.restoreRepository(bytes);
            return Response.ok().entity("Restore Ok").type("text/plain").build();
        } catch (IOException e) {
            log.error("Error reading restore file", e);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("Restore Ok")
                .type("text/plain")
                .build();
    }

    /**
     * Restore old legacy files on the Saiku server.
     * @param is Input stream
     * @param detail The file detail
     * @return A status 200
     */
    @POST
    @Produces("text/plain")
    @Consumes("multipart/form-data")
    @Path("/legacyfiles")
    public Response postRestoreFiles(
            @FormDataParam("file") InputStream is, @FormDataParam("file") FormDataContentDisposition detail) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            byte[] bytes = IOUtils.toByteArray(is);
            datasourceService.restoreLegacyFiles(bytes);
            return Response.ok().entity("Restore Ok").type("text/plain").build();
        } catch (IOException e) {
            log.error("Error reading restore file", e);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("Restore Ok")
                .type("text/plain")
                .build();
    }

    @GET
    @Produces("text/plain")
    @Path("/log/{logname}")
    public Response getLogFile(@PathParam("logname") String logname) {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            String body = logExtractor.readLog(logname);
            if (body == null) {
                // Missing log file → 404 rather than 500 + opaque text/plain
                // body. The SPA's Logs tab surfaces this as an empty pane
                // with a "no log yet" hint via the existing idle-state copy.
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Log file '" + logname + "' does not exist yet.")
                        .build();
            }
            return Response.status(Response.Status.OK).entity(body).build();
        } catch (IOException e) {
            log.error("Could not read log file", e);
            return Response.serverError().entity("Could not read log file").build();
        }
    }

    @GET
    @Produces("application/json")
    @Path("/datakeys")
    public Response getPropertiesKeys() {
        return Response.ok(repositoryDatasourceManager.getAvailablePropertiesKeys())
                .build();
    }

    @GET
    @Produces("application/json")
    @Path("/attacheddatasources")
    public Response getDataSources() {
        if (!userService.isAdmin()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        List<JujuSource> list = repositoryDatasourceManager.getJujuDatasources();

        return Response.ok(list).build();
    }
}
