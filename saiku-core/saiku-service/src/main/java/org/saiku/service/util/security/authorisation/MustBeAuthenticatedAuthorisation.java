package org.saiku.service.util.security.authorisation;

import org.springframework.security.core.Authentication;

public class MustBeAuthenticatedAuthorisation implements AuthorisationPredicate {
    public boolean isAuthorised(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
