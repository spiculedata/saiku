package org.saiku.repository;

/**
 * Thrown when a repository path is requested that does not exist.
 */
public class PathNotFoundException extends RepositoryException {

    public PathNotFoundException() {
        super();
    }

    public PathNotFoundException(String message) {
        super(message);
    }

    public PathNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public PathNotFoundException(Throwable cause) {
        super(cause);
    }
}
