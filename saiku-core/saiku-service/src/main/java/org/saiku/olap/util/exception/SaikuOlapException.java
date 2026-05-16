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
package org.saiku.olap.util.exception;

public class SaikuOlapException extends Exception {

    private static final long serialVersionUID = 6079334291828346380L;

    /**
     * @see java.lang.Exception#Exception()
     */
    public SaikuOlapException() {
        super();
    }

    /**
     * @see java.lang.Exception#Exception(String))
     */
    public SaikuOlapException(String message) {
        super(message);
    }

    /**
     * @see java.lang.Exception#Exception(Throwable)
     */
    public SaikuOlapException(Throwable cause) {
        super(cause);
    }

    /**
     * @see java.lang.Exception#Exception(String, Throwable)
     */
    public SaikuOlapException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Cause-first factory for use inside {@code catch} blocks. Always preserves
     * the inner cause chain. Prefer this over {@code new SaikuOlapException(msg)}
     * whenever a {@link Throwable} is in scope.
     *
     * @param cause   the underlying throwable; must not be null
     * @param message human-readable summary, included verbatim in the surface
     * @return a new SaikuOlapException wrapping {@code cause}
     */
    public static SaikuOlapException wrap(Throwable cause, String message) {
        return new SaikuOlapException(message, cause);
    }
}
