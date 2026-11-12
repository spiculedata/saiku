/*
 *   Copyright 2012 OSBI Ltd
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
package org.saiku.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public interface ISessionService {

    Map<String, Object> login(HttpServletRequest req, String username, String password) throws Exception;

    void logout(HttpServletRequest req);

    void authenticate(HttpServletRequest req, String username, String password);

    Map<String, Object> getSession() throws Exception;

    Map<String, Object> getAllSessionObjects();

    void clearSessions(HttpServletRequest req, String username, String password) throws Exception;

    /**
     * The current user's login password, for datasources with {@code security.type=passthrough} only
     * (saiku#2341). It is deliberately not part of {@link #getSession()} / {@link #getAllSessionObjects()}.
     * The array is the live copy and is zeroed when the session ends: callers must not retain it.
     *
     * @return the credential, or {@code null} when none is held
     */
    default char[] passthroughCredential() {
        return null;
    }
}
