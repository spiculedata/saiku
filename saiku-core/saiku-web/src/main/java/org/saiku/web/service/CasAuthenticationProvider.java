package org.saiku.web.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

public class CasAuthenticationProvider implements AuthenticationProvider {
    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static Map<String, UserDetails> userCache = new HashMap<>();

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        UserDetails user = createUserByUsername(authentication.getName());
        return new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> aClass) {
        return true;
    }

    private UserDetails createUserByUsername(String username) {
        if (userCache.containsKey(username)) {
            return userCache.get(username);
        }

        User user = new User(
                username, username, true, true, true, true, AuthorityUtils.createAuthorityList(ROLE_USER, ROLE_ADMIN));

        userCache.put(username, user);

        return user;
    }
}
