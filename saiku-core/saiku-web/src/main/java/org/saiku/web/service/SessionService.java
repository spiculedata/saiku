/*
 * Copyright (C) 2011 OSBI Ltd
 *
 * This program is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU General Public License as published by the Free 
 * Software Foundation; either version 2 of the License, or (at your option) 
 * any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 
 * See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along 
 * with this program; if not, write to the Free Software Foundation, Inc., 
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA 
 *
 */

package org.saiku.web.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.saiku.service.ISessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;


public class SessionService implements ISessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private AuthenticationManager authenticationManager;

	Map<Object,Map<String,Object>> sessionHolder = new HashMap<Object,Map<String,Object>>();


	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#setAuthenticationManager(org.springframework.security.authentication.AuthenticationManager)
	 */
	public void setAuthenticationManager(AuthenticationManager auth) {
		this.authenticationManager = auth;
	}

	/* (non-Javadoc)
	 * @see org.saiku.web.service.ISessionService#login(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
	 */
	public Map<String, Object> login(HttpServletRequest req, String username, String password ) {
		if (authenticationManager != null) {
			authenticate(req, username, password);
		}
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {			
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			if (!sessionHolder.containsKey(p)) {
				sessionHolder.put(p, new HashMap<String,Object>());
			}
			sessionHolder.get(p).put("username", username);
			sessionHolder.get(p).put("password", password);
			sessionHolder.get(p).put("sessionid", UUID.randomUUID().toString());
			List<String> roles = new ArrayList<String>();
			for (GrantedAuthority ga : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
				roles.add(ga.getAuthority());
			}
			sessionHolder.get(p).put("roles", roles);
			return sessionHolder.get(p);
		}
		return new HashMap<String, Object>();
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
		SecurityContextHolder.clearContext(); 
		HttpSession session= req.getSession(true); 
		session.invalidate();
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
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			if (sessionHolder.containsKey(p)) {
				Map<String,Object> r = new HashMap<String,Object>();
				r.putAll(sessionHolder.get(p)); 
				r.remove("password");
				return r;
			}

		}
		return new HashMap<String,Object>();
	}
	
	public Map<String,Object> getAllSessionObjects() {
		if (SecurityContextHolder.getContext() != null && SecurityContextHolder.getContext().getAuthentication() != null) {			
			Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
			if (sessionHolder.containsKey(p)) {
				Map<String,Object> r = new HashMap<String,Object>();
				r.putAll(sessionHolder.get(p)); 
				return r;
			}

		}
		return new HashMap<String,Object>();
	}
}
