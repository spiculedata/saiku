/*
 *   Copyright 2015 OSBI Ltd
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
package org.saiku.audit;

import java.util.UUID;

/**
 * Created by bugg on 22/09/15.
 */
public interface SaikuAuditHelper {

    UUID startAudit(
            String processId,
            String actionName,
            String objectName,
            String userSession,
            String sessionId,
            String message,
            Object logger);

    void endAudit(
            String processId,
            String actionName,
            String objectName,
            String userSession,
            String sessionId,
            String message,
            Object logger,
            long start,
            UUID uuid,
            long end);
}
