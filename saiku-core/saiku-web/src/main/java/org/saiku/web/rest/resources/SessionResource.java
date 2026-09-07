/*
 *   Copyright 2012 OSBI Ltd
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.saiku.service.ISessionService;
import org.saiku.service.user.UserService;
import org.saiku.web.security.audit.AuditLogger;
import org.saiku.web.security.ratelimit.LoginRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saiku Session Endpoints
 */
@Path("/saiku/session")
public class SessionResource {

    private static final Logger log = LoggerFactory.getLogger(SessionResource.class);

    private ISessionService sessionService;
    private UserService userService;
    private LoginRateLimiter rateLimiter = new LoginRateLimiter();

    public ISessionService getSessionService() {
        return sessionService;
    }

    public void setSessionService(ISessionService ss) {
        this.sessionService = ss;
    }

    public void setUserService(UserService us) {
        userService = us;
    }

    public void setRateLimiter(LoginRateLimiter rateLimiter) {
        if (rateLimiter != null) this.rateLimiter = rateLimiter;
    }

    /**
     * Login to Saiku
     * @summary Login
     * @param req Servlet request
     * @param username Username
     * @param password Password
     * @return A 200 response
     */
    @POST
    @Consumes("application/x-www-form-urlencoded")
    public Response login(
            @Context HttpServletRequest req,
            @FormParam("username") String username,
            @FormParam("password") String password) {
        // Rate-limit BEFORE touching the auth manager so attackers can't hammer it.
        if (rateLimiter.isBlocked(req)) {
            AuditLogger.rateLimitTriggered(req, username);
            return Response.status(429)
                    .header("Retry-After", String.valueOf(rateLimiter.getWindowMs() / 1000))
                    .entity("Too many failed login attempts. Try again later.")
                    .build();
        }
        try {
            sessionService.login(req, username, password);
            rateLimiter.recordSuccess(req);
            AuditLogger.loginSuccess(req, username);
            return Response.ok().build();
        } catch (Exception e) {
            rateLimiter.recordFailure(req);
            AuditLogger.loginFailure(req, username, e.getClass().getSimpleName());
            log.debug("Error logging in:" + username, e);
            return Response.status(Status.UNAUTHORIZED)
                    .entity("Authentication failed")
                    .build();
        }
    }

    /**
     * Clear logged in users session.
     * @summary Login
     * @param req Servlet request
     * @param username Username
     * @param password Password
     * @return A 200 response
     */
    @POST
    @Path("/clear")
    @Consumes("application/x-www-form-urlencoded")
    public Response clearSession(
            @Context HttpServletRequest req,
            @FormParam("username") String username,
            @FormParam("password") String password) {
        try {
            sessionService.clearSessions(req, username, password);
            return Response.ok("Session cleared").build();
        } catch (Exception e) {
            log.debug("Error clearing sessions for:" + username, e);
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(e.getLocalizedMessage())
                    .build();
        }
    }

    /**
     * Get the session in the request
     * @summary Get session
     * @param req The servlet request
     * @return A reponse with a session map
     */
    @GET
    @Consumes("application/x-www-form-urlencoded")
    @Produces(MediaType.APPLICATION_JSON)
    @ReturnType("java.util.Map<String, Object>")
    public Response getSession(@Context HttpServletRequest req) {

        Map<String, Object> sess = null;
        try {
            sess = sessionService.getSession();
        } catch (Exception e) {
            return Response.serverError().entity(e.getLocalizedMessage()).build();
        }
        try {
            String acceptLanguage = req.getLocale().getLanguage();
            if (StringUtils.isNotBlank(acceptLanguage)) {
                sess.put("language", acceptLanguage);
            }
        } catch (Exception e) {
            log.debug("Cannot get language!", e);
        }

        try {
            sess.put("isadmin", userService.isAdmin());
        } catch (Exception e) {
            // throw new UnsupportedOperationException();
        }
        try {
            userService.checkFolders();
        } catch (Exception e) {
            // Best-effort: checkFolders must never block session creation (legacy
            // plugin-mode deployments have no user folders to check). saiku#1907 F5:
            // log at WARN instead of swallowing silently, so a home-provisioning problem
            // (e.g. a rename/permission failure) is visible to operators.
            log.warn("checkFolders failed during session creation", e);
        }

        return Response.ok().entity(sess).build();
    }

    /**
     * Logout of the Session
     * @summary Logout
     * @param req The servlet request
     * @return A 200 response.
     */
    @DELETE
    public Response logout(@Context HttpServletRequest req) {
        String username = null;
        try {
            Object u = sessionService.getSession().get("username");
            if (u != null) username = u.toString();
        } catch (Exception ignored) {
            /* no active session — fine */
        }
        sessionService.logout(req);
        AuditLogger.logout(req, username);
        //		NewCookie terminate = new NewCookie(TokenBasedRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY,
        // null);

        return Response.ok().build();
    }
}
