package org.saiku.service.util.security.authorisation;

import org.springframework.security.core.Authentication;

public interface AuthorisationPredicate {
    boolean isAuthorised(Authentication authentication);
}
