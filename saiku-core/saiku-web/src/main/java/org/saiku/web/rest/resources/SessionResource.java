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
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.saiku.service.ISessionService;
import org.saiku.service.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saiku Session Endpoints
 */
@Component
@RestController
@RequestMapping("/saiku/session")
public class SessionResource {

    private static final Logger log = LoggerFactory.getLogger(SessionResource.class);

    private ISessionService sessionService;
    private UserService userService;

    public ISessionService getSessionService() {
        return sessionService;
    }

    public void setSessionService(ISessionService ss) {
        this.sessionService = ss;
    }

    public void setUserService(UserService us) {
        userService = us;
    }

    /**
     * Login to Saiku
     */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> login(
            HttpServletRequest req,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password) {
        try {
            sessionService.login(req, username, password);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.debug("Error logging in:" + username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getLocalizedMessage());
        }
    }

    /**
     * Clear logged in users session.
     */
    @PostMapping(path = "/clear", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> clearSession(
            HttpServletRequest req,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password) {
        try {
            sessionService.clearSessions(req, username, password);
            return ResponseEntity.ok("Session cleared");
        } catch (Exception e) {
            log.debug("Error clearing sessions for:" + username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getLocalizedMessage());
        }
    }

    /**
     * Get the session in the request
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ReturnType("java.util.Map<String, Object>")
    public ResponseEntity<?> getSession(HttpServletRequest req) {

        Map<String, Object> sess = null;
        try {
            sess = sessionService.getSession();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getLocalizedMessage());
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
            // TODO detect if plugin or not.
        }

        return ResponseEntity.ok(sess);
    }

    /**
     * Logout of the Session
     */
    @DeleteMapping
    public ResponseEntity<?> logout(HttpServletRequest req) {
        sessionService.logout(req);
        return ResponseEntity.ok().build();
    }
}
