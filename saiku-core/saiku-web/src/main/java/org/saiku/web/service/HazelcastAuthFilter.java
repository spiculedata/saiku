/*
 *   Copyright 2016 OSBI Ltd
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

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class HazelcastAuthFilter implements Filter {
    private static final String SAIKU_AUTH_PRINCIPAL = "SAIKU_AUTH_PRINCIPAL";
    private static final int FIVE_MINUTES = 300; // in miliseconds
    private static final String ORBIS_WORKSPACE_DIR = "workspace";

    private boolean enabled;
    private String orbisAuthCookie;
    private String hazelcastMapName;
    private String baseWorkspaceDir;
    private String casAuthHeader;

    private FilterConfig filterConfig;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        setFilterConfig(filterConfig);

        enabled = Boolean.parseBoolean(initParameter(filterConfig, "enabled", "true"));
        orbisAuthCookie = initParameter(filterConfig, "orbisAuthCookie", "ORBIS_WORKSPACE_USER");
        hazelcastMapName = initParameter(filterConfig, "hazelcastMapName", "my-sessions");
        baseWorkspaceDir = initParameter(filterConfig, "baseWorkspaceDir", "../../repository/data");
        casAuthHeader = initParameter(filterConfig, "casAuthHeader", "MOD_AUTH_CAS_USER");
    }

    private String initParameter(FilterConfig filterConfig, String paramName, String defaultValue) {
        if (filterConfig.getInitParameter(paramName) != null) {
            return filterConfig.getInitParameter(paramName);
        }
        return defaultValue;
    }

    public String getBaseWorkspaceDir() {
        return baseWorkspaceDir;
    }

    public String getHazelcastMapName() {
        return hazelcastMapName;
    }

    public String getOrbisAuthCookie() {
        return orbisAuthCookie;
    }

    @Override
    public void destroy() {}

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        System.err.println("doFilter");
        // If the filter is enabled
        if (enabled) {
            // We fetch a session
            HttpSession session = ((HttpServletRequest) req).getSession();

            if (session != null) { // If there's already a session
                HttpServletRequest httpReq = (HttpServletRequest) req;

                String casHeader = httpReq.getHeader(casAuthHeader);
                if (casHeader != null) {
                    // Setting up the user id as a local cookie (so JavaScript/Client layer may access)
                    setCookieValue(res, SAIKU_AUTH_PRINCIPAL, casHeader);
                    // Setting up the workspace directory (so repository manager can create the workspace)
                    session.setAttribute(ORBIS_WORKSPACE_DIR, "workspace_" + casHeader);
                    session.setAttribute(SAIKU_AUTH_PRINCIPAL, casHeader);
                }

                // Retrieve the Aardvark cookie value
                for (Cookie cookie : httpReq.getCookies()) {
                    // Found Orbis Cookie (Aardvark)
                    if (cookie.getName().equals(orbisAuthCookie)) {
                        String cookieVal = cookie.getValue();

                        // Setting up the user id as a local cookie (so JavaScript/Client layer may access)
                        setCookieValue(res, SAIKU_AUTH_PRINCIPAL, cookieVal);
                        // Setting up the workspace directory (so repository manager can create the workspace)
                        session.setAttribute(ORBIS_WORKSPACE_DIR, "workspace_" + cookieVal);
                        session.setAttribute(SAIKU_AUTH_PRINCIPAL, cookieVal);

                        break;
                    }
                }
            }
        }

        chain.doFilter(req, res);
    }

    private void setCookieValue(ServletResponse res, String cookieName, String cookieVal) {
        HttpServletResponse response = (HttpServletResponse) res;
        Cookie orbisCookie = new Cookie(cookieName, cookieVal);
        orbisCookie.setMaxAge(FIVE_MINUTES);
        orbisCookie.setPath("/");
        response.addCookie(orbisCookie);
    }

    public FilterConfig getFilterConfig() {
        return filterConfig;
    }

    public void setFilterConfig(FilterConfig filterConfig) {
        this.filterConfig = filterConfig;
    }
}
