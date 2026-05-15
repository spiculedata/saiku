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
package org.saiku.service.util.exception;

public class SaikuServiceException extends RuntimeException {

    private static final long serialVersionUID = 6079334291828346380L;

    /**
     * @see java.lang.Exception#Exception()
     */
    public SaikuServiceException() {
        super();
    }

    /**
     * @see java.lang.Exception#Exception(String))
     */
    public SaikuServiceException(String message) {
        super(message);
    }

    /**
     * @see java.lang.Exception#Exception(Throwable)
     */
    public SaikuServiceException(Throwable cause) {
        super(cause);
    }

    /**
     * @see java.lang.Exception#Exception(String, Throwable)
     */
    public SaikuServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Cause-first factory for use inside {@code catch} blocks. Always preserves
     * the inner cause chain. Prefer this over {@code new SaikuServiceException(msg)}
     * whenever a {@link Throwable} is in scope — dropping the cause turns a
     * 500-with-diagnostic into a 500-with-UUID, which is the failure mode
     * {@code AiQueryResource.describeDeepestCause} exists to work around.
     *
     * @param cause   the underlying throwable; must not be null
     * @param message human-readable summary, included verbatim in the surface
     * @return a new SaikuServiceException wrapping {@code cause}
     */
    public static SaikuServiceException wrap(Throwable cause, String message) {
        return new SaikuServiceException(message, cause);
    }
}
