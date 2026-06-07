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

package org.saiku.web.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.saiku.repository.ScopedRepo;
import org.saiku.service.ISessionService;
import org.saiku.service.util.security.authorisation.AuthorisationPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.context.request.RequestContextHolder;

public class SessionService implements ISessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private AuthenticationManager authenticationManager;
    private AuthorisationPredicate authorisationPredicate;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final Map<Object, Map<String, Object>> sessionHolder = new ConcurrentHashMap<>();

    private Boolean anonymous = false;
    private ScopedRepo sessionRepo;
    private Boolean orbisAuthEnabled = false;

    public void setAllowAnonymous(Boolean allow) {
        this.anonymous = allow;
    }

    /* (non-Javadoc)
     * @see org.saiku.web.service.ISessionService#setAuthenticationManager(org.springframework.security.authentication.AuthenticationManager)
     */
    public void setAuthenticationManager(AuthenticationManager auth) {
        this.authenticationManager = auth;
    }

    public void setAuthorisationPredicate(AuthorisationPredicate authorisationPredicate) {
        this.authorisationPredicate = authorisationPredicate;
    }

    /* (non-Javadoc)
     * @see org.saiku.web.service.ISessionService#login(jakarta.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
     */
    public Map<String, Object> login(HttpServletRequest req, String username, String password) {
        HttpSession session = ((HttpServletRequest) req).getSession(true);
        session.getId();
        sessionRepo.setSession(session);

        if (authenticationManager != null) {
            authenticate(req, username, password);
        }
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (authorisationPredicate.isAuthorised(auth)) {
                Object p = auth.getPrincipal();
                // saiku#1165: defeat session fixation. Now that authentication has
                // succeeded, rotate the HTTP session id so any pre-auth id an
                // attacker may have planted in the victim's browser is discarded.
                // changeSessionId() keeps the session's attributes (including the
                // SecurityContext saved during authenticate()) and reissues the
                // JSESSIONID cookie with the new id. This manual login flow bypasses
                // Spring Security's SessionAuthenticationStrategy, so we rotate here.
                try {
                    req.changeSessionId();
                } catch (IllegalStateException noSession) {
                    // No active session to rotate (shouldn't happen after getSession
                    // above) — nothing to fix; continue.
                    log.debug("Session id rotation skipped: no active session", noSession);
                }
                createSession(auth, username, password);
                return sessionHolder.get(p);
            } else {
                log.info(username + " failed authorisation. Rejecting login");
                throw new RuntimeException("Authorisation failed for: " + username);
            }
        }
        return new HashMap<>();
    }

    private void createSession(Authentication auth, String username, String password) {

        if (auth == null || !auth.isAuthenticated()) {
            return;
        }

        boolean isAnonymousUser = (auth instanceof AnonymousAuthenticationToken);
        Object p = auth.getPrincipal();
        String authUser = getUsername(p);
        boolean isAnonymous = (isAnonymousUser || StringUtils.equals("anonymousUser", authUser));
        boolean isAnonOk = (!isAnonymous || (isAnonymous && anonymous));

        if (isAnonOk && auth.isAuthenticated() && p != null && !sessionHolder.containsKey(p)) {
            Map<String, Object> session = new HashMap<>();

            if (isAnonymous) {
                log.debug("Creating Session for Anonymous User");
            }

            if (StringUtils.isNotBlank(username)) {
                session.put("username", username);
            } else {
                session.put("username", authUser);
            }
            if (StringUtils.isNotBlank(password)) {
                session.put("password", password);
            }
            session.put("sessionid", UUID.randomUUID().toString());
            session.put(
                    "authid", RequestContextHolder.currentRequestAttributes().getSessionId());
            List<String> roles = new ArrayList<>();
            for (GrantedAuthority ga :
                    SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
                roles.add(ga.getAuthority());
            }
            session.put("roles", roles);

            sessionHolder.put(p, session);
        }
    }

    private String getUsername(Object p) {

        if (p instanceof UserDetails) {
            return ((UserDetails) p).getUsername();
        }
        return p.toString();
    }

    /* (non-Javadoc)
     * @see org.saiku.web.service.ISessionService#logout(jakarta.servlet.http.HttpServletRequest)
     */
    public void logout(HttpServletRequest req) {
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (sessionHolder.containsKey(p)) {
                sessionHolder.remove(p);
            }
        }

        SecurityContextHolder.getContext().setAuthentication(null);
        SecurityContextHolder.clearContext();

        HttpSession session = req.getSession(false);

        if (session != null && !orbisAuthEnabled) { // Just invalidate if not under orbis authentication workflow
            session.invalidate();
        }
    }

    /* (non-Javadoc)
     * @see org.saiku.web.service.ISessionService#authenticate(jakarta.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
     */
    public void authenticate(HttpServletRequest req, String username, String password) {
        try {
            UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, password);
            token.setDetails(new WebAuthenticationDetails(req));
            Authentication authentication = this.authenticationManager.authenticate(token);
            log.debug("Logging in with [{}]", authentication.getPrincipal());
            SecurityContext context = SecurityContextHolder.getContext();
            context.setAuthentication(authentication);
            jakarta.servlet.http.HttpServletResponse res =
                    ((org.springframework.web.context.request.ServletRequestAttributes)
                                    RequestContextHolder.currentRequestAttributes())
                            .getResponse();
            securityContextRepository.saveContext(context, req, res);
        } catch (BadCredentialsException bd) {
            throw new RuntimeException("Authentication failed for: " + username, bd);
        }
    }

    /* (non-Javadoc)
     * @see org.saiku.web.service.ISessionService#getSession(jakarta.servlet.http.HttpServletRequest)
     */
    public Map<String, Object> getSession() {
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object p = auth.getPrincipal();

            if (sessionHolder.containsKey(p)) {
                Map<String, Object> r = new HashMap<>();
                r.putAll(sessionHolder.get(p));
                r.remove("password");

                if (!r.containsKey("sessionid")) {
                    r.put("sessionid", UUID.randomUUID().toString());
                }

                return r;
            }
        }

        return new HashMap<>();
    }

    public Map<String, Object> getAllSessionObjects() {
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object p = auth.getPrincipal();
            // createSession(auth, null, null);
            if (sessionHolder.containsKey(p)) {
                Map<String, Object> r = new HashMap<>();
                r.putAll(sessionHolder.get(p));
                return r;
            }
        }
        return new HashMap<>();
    }

    /**
     * Execute {@code action} as if {@code username} (granted {@code roles}) were
     * the authenticated principal, restoring the prior state in a {@code finally}.
     *
     * <p>For server-initiated <b>delegated</b> execution ONLY — issue #941 share
     * links run an account-free guest's tile query under the share owner's data
     * scope, which spans two mechanisms: the Mondrian connection role (read from
     * {@link SecurityContextHolder}) and the JCR file ACL (read from the session
     * map via {@link #getAllSessionObjects()}). This sets both for the duration
     * and removes them after, so no impersonated identity leaks past the call.
     * Never call this from a path driven by untrusted input that picks the
     * username/roles — the share-link path derives them from a server-held token
     * minted by someone who already had GRANT.
     */
    public <T> T runAs(String username, List<String> roles, java.util.function.Supplier<T> action) {
        Authentication prior = SecurityContextHolder.getContext() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication();
        List<GrantedAuthority> auths = new ArrayList<>();
        if (roles != null) {
            for (String r : roles) {
                auths.add(new SimpleGrantedAuthority(r));
            }
        }
        PreAuthenticatedAuthenticationToken owner = new PreAuthenticatedAuthenticationToken(username, null, auths);
        Object principal = owner.getPrincipal();
        boolean addedSession = false;
        try {
            SecurityContextHolder.getContext().setAuthentication(owner);
            if (principal != null && !sessionHolder.containsKey(principal)) {
                Map<String, Object> sess = new HashMap<>();
                sess.put("username", username);
                sess.put("roles", roles == null ? new ArrayList<>() : new ArrayList<>(roles));
                sessionHolder.put(principal, sess);
                addedSession = true;
            }
            return action.get();
        } finally {
            if (addedSession) {
                sessionHolder.remove(principal);
            }
            SecurityContextHolder.getContext().setAuthentication(prior);
        }
    }

    public void clearSessions(HttpServletRequest req, String username, String password) throws Exception {
        if (authenticationManager != null) {
            authenticate(req, username, password);
        }
        if (SecurityContextHolder.getContext() != null
                && SecurityContextHolder.getContext().getAuthentication() != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Object p = auth.getPrincipal();
            if (sessionHolder.containsKey(p)) {
                sessionHolder.remove(p);
            }
        }
    }

    public void setSessionRepo(org.saiku.repository.ScopedRepo sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public Boolean isOrbisAuthEnabled() {
        return orbisAuthEnabled;
    }

    public void setOrbisAuthEnabled(Boolean orbisAuthEnabled) {
        this.orbisAuthEnabled = orbisAuthEnabled;
    }
}
