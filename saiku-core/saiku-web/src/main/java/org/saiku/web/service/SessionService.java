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
import org.saiku.service.util.security.Usernames;
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

    /**
     * saiku#1859 — {@link HttpSession} attribute holding the Saiku session map (username,
     * roles, sessionid, ...) so it travels with the container session.
     *
     * <p>{@link #sessionHolder} is per-JVM state on a singleton bean, so a restart (or an
     * upgrade, or a container stop) empties it. The launcher's Jetty {@code
     * FileSessionDataStore} does persist the HTTP session — Spring Security's {@code
     * SPRING_SECURITY_CONTEXT} included — but Saiku read every "who is this request" answer
     * ({@link #getSession()}, {@link #getAllSessionObjects()}) from this in-memory map only, so
     * after a restart every authenticated request came back "anonymous" to the application even
     * though the container still considered it authenticated: the SPA's {@code GET
     * /rest/saiku/session} returned {@code {}}, which {@code saiku-ui}'s {@code
     * getCurrentSession()} treats as no session, and the user got the "Session ended" modal. The
     * password is deliberately NOT mirrored here — see {@link #persistSessionMap}.
     */
    static final String SESSION_MAP_ATTRIBUTE = "org.saiku.session.OBJECTS";

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

            // saiku#1907 (CWE-178): the account store matches usernames case-insensitively,
            // so a case-variant login ("Admin" vs "admin") is the same account. We keep TWO
            // identities in the session:
            //  - "username": the CANONICAL ACL/home identity (lower-cased via Usernames), so
            //    ownership and the /homes/<user> path resolve to ONE value regardless of the
            //    case typed. Previously the RAW submitted string was stored, so "Admin" got a
            //    distinct principal + home from "admin", desynchronising ownership.
            //  - "principal": the username AS THE USER TYPED IT (the submitted string, falling back
            //    to the authenticated principal), used verbatim for datasource pass-through
            //    warehouse credentials (F3). This is the correct back-compat choice: pass-through
            //    forwards exactly what the user entered, so a case-sensitive warehouse login
            //    (JSmith) authenticates — lower-casing it (jsmith) would break it. It intentionally
            //    matches the pre-fix behaviour (which stored the typed string in "username").
            // Anonymous keeps its exact well-known name (no home/ownership semantics attach).
            String principalSpelling = StringUtils.isNotBlank(username) ? username : authUser;
            String canonicalUser = StringUtils.isNotBlank(authUser) ? authUser : username;
            if (canonicalUser != null && !isAnonymous) {
                canonicalUser = Usernames.canonicalize(canonicalUser);
            }
            session.put("username", canonicalUser);
            if (principalSpelling != null) {
                session.put("principal", principalSpelling);
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
            persistSessionMap(session);
        }
    }

    /**
     * saiku#1859 — mirror the freshly created session map onto the {@link HttpSession} so the
     * container's session store carries it across a restart.
     *
     * <p>The {@code password} entry (datasource pass-through warehouse credentials, F3) is
     * stripped: this map is written to disk by Jetty's {@code FileSessionDataStore}, and a
     * warehouse password has no business being persisted at rest. After a restart a pass-through
     * datasource simply has no credential until the user signs in again — the same position they
     * are in today, since the in-memory map dies with the JVM.
     */
    private void persistSessionMap(Map<String, Object> session) {
        HttpSession httpSession = currentHttpSession();
        if (httpSession == null) {
            return;
        }
        try {
            Map<String, Object> persistable = new HashMap<>(session);
            persistable.remove("password");
            httpSession.setAttribute(SESSION_MAP_ATTRIBUTE, persistable);
        } catch (RuntimeException e) {
            // A container that refuses the attribute (e.g. a non-serializable value under a
            // passivation-capable store) must not fail the login — the in-memory map still
            // serves this JVM, we just lose restart-survival for it.
            log.warn("Could not persist the Saiku session map onto the HTTP session", e);
        }
    }

    /**
     * saiku#1859 — resolve the Saiku session map for the current authentication, rehydrating it
     * from the {@link HttpSession} when the in-memory {@link #sessionHolder} doesn't know the
     * principal.
     *
     * <p>This is the "after a restart" path: the container restored the session (and with it
     * Spring Security's authentication), but this JVM has never seen this principal. The stored
     * identity must match the authenticated principal or the map is discarded — a map whose
     * {@code username} disagrees with the {@code Authentication} would otherwise hand one
     * account another account's home/ACL scope.
     *
     * @return the session map, or {@code null} when there is no session for this principal
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveSessionMap(Authentication auth) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal == null) {
            return null;
        }
        Map<String, Object> cached = sessionHolder.get(principal);
        if (cached != null) {
            return cached;
        }

        HttpSession httpSession = currentHttpSession();
        if (httpSession == null) {
            return null;
        }
        Object stored;
        try {
            stored = httpSession.getAttribute(SESSION_MAP_ATTRIBUTE);
        } catch (RuntimeException e) {
            log.debug("Could not read the persisted Saiku session map", e);
            return null;
        }
        if (!(stored instanceof Map)) {
            return null;
        }

        Map<String, Object> restored = new HashMap<>((Map<String, Object>) stored);
        Object storedUsername = restored.get("username");
        String authenticatedUser = getUsername(principal);
        String canonicalStored = storedUsername == null ? null : Usernames.canonicalize(storedUsername.toString());
        String canonicalAuthenticated = authenticatedUser == null ? null : Usernames.canonicalize(authenticatedUser);
        if (canonicalStored == null || !canonicalStored.equals(canonicalAuthenticated)) {
            log.warn(
                    "Discarding restored Saiku session: stored identity '{}' does not match the authenticated principal '{}'",
                    storedUsername,
                    authenticatedUser);
            return null;
        }

        Map<String, Object> raced = sessionHolder.putIfAbsent(principal, restored);
        return raced != null ? raced : restored;
    }

    /** @return the current request's {@link HttpSession}, or {@code null} outside a request */
    private HttpSession currentHttpSession() {
        if (sessionRepo == null) {
            return null;
        }
        try {
            return sessionRepo.getSession();
        } catch (RuntimeException e) {
            log.debug("No HTTP session bound to the current request", e);
            return null;
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
            // saiku#1859: rehydrates from the HTTP session when this JVM has never seen the
            // principal (a restart / upgrade), which is what keeps a persisted, still-valid
            // JSESSIONID logged in instead of bouncing the user to the login screen.
            Map<String, Object> current = resolveSessionMap(auth);
            if (current != null) {
                Map<String, Object> r = new HashMap<>();
                r.putAll(current);
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
            // createSession(auth, null, null);
            // saiku#1859: same rehydration as getSession() — this is the map every REST
            // resource reads "username" from, so an empty one after a restart meant a
            // signed-in user resolved to no user at all.
            Map<String, Object> current = resolveSessionMap(auth);
            if (current != null) {
                Map<String, Object> r = new HashMap<>();
                r.putAll(current);
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
                // saiku#1907 F3: mirror the login session shape so pass-through (which reads
                // "principal") still resolves under delegated execution.
                sess.put("principal", username);
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
            // saiku#1859: drop the mirrored copy too, so a cleared session can't be rehydrated
            // back out of the container session by resolveSessionMap().
            HttpSession httpSession = currentHttpSession();
            if (httpSession != null) {
                try {
                    httpSession.removeAttribute(SESSION_MAP_ATTRIBUTE);
                } catch (RuntimeException e) {
                    log.debug("Could not clear the persisted Saiku session map", e);
                }
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
