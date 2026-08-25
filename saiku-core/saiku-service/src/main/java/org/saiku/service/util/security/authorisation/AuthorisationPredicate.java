/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.service.util.security.authorisation;

import org.springframework.security.core.Authentication;

public interface AuthorisationPredicate {
    boolean isAuthorised(Authentication authentication);
}
