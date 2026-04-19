/*
 * Copyright 2014 OSBI Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.saiku.repository;

import javax.jcr.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import org.apache.jackrabbit.server.SessionProvider;

/**
 * Created by bugg on 01/06/15.
 */
class SaikuSessionProvider implements SessionProvider {
    public Session getSession(HttpServletRequest request, Repository repository, String workspace)
            throws ServletException, RepositoryException {
        Session s = null;

        SimpleCredentials c = new SimpleCredentials("anon", "anon".toCharArray());
        s = repository.login(c, workspace);

        return s;
    }

    public void releaseSession(Session session) {
        session.logout();
    }
}
