package org.saiku.web.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Created by brunogamacatao on 10/05/16.
 */
public class OrbisLogoutHandler implements LogoutHandler {
  @Override
  public void logout(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Authentication authentication) {
    // We will preserve session's attributes
    authentication.setAuthenticated(false);
  }
}
