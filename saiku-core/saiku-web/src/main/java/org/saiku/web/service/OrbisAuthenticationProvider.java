package org.saiku.web.service;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Created by brunogamacatao on 07/05/16.
 */
public class OrbisAuthenticationProvider implements AuthenticationProvider {
  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    UserDetails user = createUserByUsername(authentication.getName());
    return new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
  }

  @Override
  public boolean supports(Class<?> aClass) {
    return true;
  }

  private static UserDetails createUserByUsername(String username) {
    if(username != "admin") {
      return new User(username, username, true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN", "ROLE_ORBIS"));
    }
    else{
      return new User(username, username, true, true, true, true, AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN"));
    }
  }
}
