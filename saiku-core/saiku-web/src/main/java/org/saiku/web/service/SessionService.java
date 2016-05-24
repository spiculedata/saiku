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

import org.apache.commons.lang.StringUtils;

import org.saiku.repository.ScopedRepo;
import org.saiku.service.ISessionService;
import org.saiku.service.license.ILicenseUtils;

import bi.meteorite.license.LicenseException;
import bi.meteorite.license.SaikuLicense2;
import org.saiku.service.util.security.authorisation.AuthorisationPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;


public class SessionService implements ISessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private AuthenticationManager authenticationManager;
	private AuthorisationPredicate authorisationPredicate;

	private final Map<Object,Map<String,Object>> sessionHolder = new HashMap<>();

	private Boolean anonymous = false;
	private ScopedRepo sessionRepo;
	private Boolean orbisAuthEnabled = false;

	public void setAllowAnonymous(Boolean allow) {
		this.anonymous  = allow;
	}

	private ILicenseUtils l;

	public ILicenseUtils getL() {
		return l;
	}

	public void setL(ILicenseUtils l) {
		this.l = l;
	}

	/* (non-Javadoc)
         * @see org.saiku.web.service.ISessionService#setAuthenticationManager(org.springframework.security.authentication.AuthenticationManager)
         */
	public void setAuthenticationManager(AuthenticationManager auth) {
		this.authenticationManager = auth;
	}

	public void setAuthorisationPredicate(AuthorisationPredicate authorisationPredicate)
	{
		this.authorisationPredicate = authorisationPredicate;
	}

	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#login(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
	 */
	public Map<String, Object> login(HttpServletRequest req, String username, String password ) throws LicenseException {
		Object sl = null;
		String notice = null;
		HttpSession session = ((HttpServletRequest)req).getSession(true);
		session.getId();
		sessionRepo.setSession(session);
		try {
			sl = l.getLicense();
		} catch (Exception e) {
			log.debug("Could not process license", e);
			throw new LicenseException("Error fetching license. Get a free license from http://licensing.meteorite.bi. You can upload it at /upload.html");
		}

		if (sl != null) {

			try {
				l.validateLicense();
			} catch (RepositoryException | IOException | ClassNotFoundException e) {
				log.debug("Repository Exception, couldn't get license", e);
				throw new LicenseException("Error fetching license. Please check your logs.");
			}

			try {
				if (l.getLicense() instanceof SaikuLicense2) {

                    if (authenticationManager != null) {
                        authenticate(req, username, password);
                    }
                    if (SecurityContextHolder.getContext() != null
                        && SecurityContextHolder.getContext().getAuthentication() != null) {
                        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

                        if (authorisationPredicate.isAuthorised(auth)) {
                            Object p = auth.getPrincipal();
                            createSession(auth, username, password);
                            return sessionHolder.get(p);
                        } else {
                            log.info(username + " failed authorisation. Rejecting login");
                            throw new RuntimeException("Authorisation failed for: " + username);
                        }
                    }
                    return new HashMap<>();
                }
			} catch (IOException | ClassNotFoundException | RepositoryException e) {
				log.debug("Repository Exception, couldn't get license", e);
				throw new LicenseException("Error fetching license. Please check your logs.");
			}
		}
		return null;
	}

	private void createSession(Authentication auth, String username, String password) {

		if (auth ==  null || !auth.isAuthenticated()) {
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
			session.put("authid", RequestContextHolder.currentRequestAttributes().getSessionId());
			List<String> roles = new ArrayList<>();
			for (GrantedAuthority ga : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
				roles.add(ga.getAuthority());
			}
			session.put("roles", roles);
			
			sessionHolder.put(p, session);
		}

	}

	private String getUsername(Object p) {
		
		if (p instanceof UserDetails) {
			  return ((UserDetails)p).getUsername();
		} 
		return p.toString();
	}

	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#logout(javax.servlet.http.HttpServletRequest)
	 */
	public void logout(HttpServletRequest req) {
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
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
	 * @see org.saiku.web.service.ISessionService#authenticate(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
	 */
	public void authenticate(HttpServletRequest req, String username, String password) {
		try {
			UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(username, password);
			token.setDetails(new WebAuthenticationDetails(req));
			Authentication authentication = this.authenticationManager.authenticate(token);
			log.debug("Logging in with [{}]", authentication.getPrincipal());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		catch (BadCredentialsException bd) {
			throw new RuntimeException("Authentication failed for: " + username, bd);
		}

	}

	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#getSession(javax.servlet.http.HttpServletRequest)
	 */
	public Map<String,Object> getSession() {
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {			
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			Object p = auth.getPrincipal();
		  if (sessionHolder.containsKey(p)) {
			  Map<String, Object> r = new HashMap<>();
			  r.putAll(sessionHolder.get(p));
			  r.remove("password");
			  return r;
		  }

		}
		return new HashMap<>();
	}
	
	public Map<String,Object> getAllSessionObjects() {
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {			
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			Object p = auth.getPrincipal();
			//createSession(auth, null, null);
			if (sessionHolder.containsKey(p)) {
				Map<String,Object> r = new HashMap<>();
				r.putAll(sessionHolder.get(p)); 
				return r;
			}

		}
		return new HashMap<>();
	}

  public void clearSessions(HttpServletRequest req, String username, String password) throws Exception {
	if (authenticationManager != null) {
	  authenticate(req, username, password);
	}
	if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
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
