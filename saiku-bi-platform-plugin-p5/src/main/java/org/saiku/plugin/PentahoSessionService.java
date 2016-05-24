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
package org.saiku.plugin;

import org.pentaho.platform.api.engine.ILogoutListener;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.saiku.plugin.util.PentahoAuditHelper;
import org.saiku.service.ISessionService;

import bi.meteorite.license.LicenseVersionExpiredException;
import bi.meteorite.license.SaikuLicense2;
import org.saiku.service.user.UserService;

import org.pentaho.platform.util.logging.SimpleLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.Authentication;
import org.springframework.security.AuthenticationManager;
import org.springframework.security.BadCredentialsException;
import org.springframework.security.GrantedAuthority;
import org.springframework.security.context.SecurityContextHolder;
import org.springframework.security.providers.UsernamePasswordAuthenticationToken;
import org.springframework.security.ui.WebAuthenticationDetails;
import org.springframework.security.userdetails.User;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;


public class PentahoSessionService implements ISessionService {

	private LicenseUtils l;


	public LicenseUtils getL() {
		return l;
	}

	public void setL(LicenseUtils l) {
		this.l = l;
	}

	private static final Logger log = LoggerFactory.getLogger(PentahoSessionService.class);

	private AuthenticationManager authenticationManager;

	private final Map<Object,Map<String,Object>> sessionHolder = new HashMap<Object,Map<String,Object>>();
  private UserService userService;

  public void setUserService(UserService us) {
	userService = us;
  }

	private final PentahoAuditHelper pah = new PentahoAuditHelper();
	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#setAuthenticationManager(org.springframework.security.authentication.AuthenticationManager)
	 */
	public void setAuthenticationManager(AuthenticationManager auth) {
		this.authenticationManager = auth;
	}

	public PentahoSessionService() {
		PentahoSystem.addLogoutListener(new ILogoutListener() {

			@Override
			public void onLogout( IPentahoSession pentahoSession ) {
				System.out.println("processing pentaho logout");
				UUID uuid = pah.startAudit("Saiku", "Logout", this.getClass().getName(), null, null, "Attempted Logout", getLogger
						());
				if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
					Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
					if (sessionHolder.containsKey(p)) {
						sessionHolder.remove(p);
					}
				}
				try {
					pah.endAudit("Saiku", "Logout Successful", this.getClass().getName(), null, null,
							getLogger(), (long) 1, uuid, (long) 1);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} );
	}

	/* (non-Javadoc)
         * @see org.saiku.web.service.ISessionService#login(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
         */
	public Map<String, Object> login(HttpServletRequest req, String username, String password ) {
	  pah.startAudit("Saiku", "Login", this.getClass().getName(), this.toString(), this.toString(), null, new
		  SimpleLogger(
		  PentahoSessionService.class
			  .getName()));
		if (authenticationManager != null) {
			authenticate(req, username, password);
		}
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			populateSession(p, username, password);
			return sessionHolder.get(p);
		}


		return new HashMap<String, Object>();
	}

	private void populateSession(Object key) {

	  String username;
	  if (key instanceof User) {
		User u = (User) key;
		username = u.getUsername();
	  }
	  else {
		username = "existinguser";
	  }
	  String sessionId =UUID.randomUUID().toString();

	  UUID uuid = pah.startAudit("Saiku", "Login", this.getClass().getName(), username, sessionId, username +
																								 " Attempted "
																							+ "Login",
		  getLogger());
		if (!sessionHolder.containsKey(key)) {
			sessionHolder.put(key, new HashMap<String,Object>());
		}
		sessionHolder.get(key).put("sessionid", sessionId);
		List<String> roles = new ArrayList<String>();
		for (GrantedAuthority ga : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
			roles.add(ga.getAuthority());
		}
		sessionHolder.get(key).put("roles", roles);

		sessionHolder.get(key).put("isadmin", userService.isAdmin());
		sessionHolder.get(key).put("username", username);
	  	pah.endAudit("Saiku", "Login", this.getClass().getName(), username, sessionId, getLogger(),
			(long) 1, uuid, (long) 1);
	}

	private void populateSession(Object key, String username, String password) {
		populateSession(key);
		sessionHolder.get(key).put("username", username);
		sessionHolder.get(key).put("password", password);

	}

	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#logout(javax.servlet.http.HttpServletRequest)
	 */
	public void logout(HttpServletRequest req) {
	  UUID uuid = pah.startAudit("Saiku", "Logout", this.getClass().getName(), null, null, "Attempted Logout", getLogger
		  ());
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			if (sessionHolder.containsKey(p)) {
				sessionHolder.remove(p);
			}
		}
		SecurityContextHolder.clearContext(); 
		HttpSession session= req.getSession(true); 
		session.invalidate();
		try {
			pah.endAudit("Saiku", "Logout Successful", this.getClass().getName(), null, this.getSession().toString(),
                getLogger(), (long) 1, uuid, (long) 1);
		} catch (Exception e) {
			e.printStackTrace();
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
	public Map<String,Object> getSession() throws Exception {

		if (SecurityContextHolder.getContext() != null
			&& SecurityContextHolder.getContext().getAuthentication() != null) {

			try {


				SaikuLicense2 sl = (SaikuLicense2) l.getLicense();

				if (sl != null) try {
					l.validateLicense();
				} catch (RepositoryException | IOException | ClassNotFoundException e) {
					log.error("Exception thrown when validating license", e);
				}
				Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
				if (!sessionHolder.containsKey(p)) {
					populateSession(p);
				}
				Map<String, Object> r = new HashMap<>();
				r.putAll(sessionHolder.get(p));
				if (r.containsKey("password")) {
					r.remove("password");
				}
				return r;

			} catch (Exception e) {
				if(e instanceof LicenseVersionExpiredException){
					log.error("License is for wrong version. Please update your license http://licensing.meteorite.bi", e);
					throw new Exception("License for wrong version please update your free license(http://licensing"
										+ ".meteorite.bi)");
				}
				else {
					log.error("No license found, please fetch a free license from http://licensing.meteorite.bi", e);
					throw new Exception("No license found, please fetch a free license from http://licensing.meteorite"
										+ ".bi and move it to: pentaho-solutions/system/saiku/license.lic");
				}

			}

		}
		return null;
	}
	
	public Map<String,Object> getAllSessionObjects() {
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {			
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			if (!sessionHolder.containsKey(p)) {
				populateSession(p);
			}
			Map<String,Object> r = new HashMap<String,Object>();
			r.putAll(sessionHolder.get(p));
			if (r.containsKey("password")) {
				r.remove("password");
			}
			return r;
		}
		return new HashMap<>();
	}

  public void clearSessions(HttpServletRequest req, String username, String password) {
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

  private SimpleLogger getLogger(){
	return new SimpleLogger(
		PentahoSessionService.class
			.getName());
  }


}
