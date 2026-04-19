package org.saiku.service.util.security.authorisation;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public class RoleBasedAuthorisation implements AuthorisationPredicate {
    private final String mandatoryRole;

    public RoleBasedAuthorisation(String mandatoryRole) {
        this.mandatoryRole = mandatoryRole;
    }

    public boolean isAuthorised(Authentication authentication) {
        if (authentication == null) {
            return false;
        }

        if (!authentication.isAuthenticated()) {
            return false;
        }

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().equals(mandatoryRole)) {
                return true;
            }
        }
        return false;
    }
}
