/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security.authorisation;

import org.springframework.security.core.Authentication;

public class MustBeAuthenticatedAuthorisation implements AuthorisationPredicate {
    public boolean isAuthorised(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated();
    }
}
