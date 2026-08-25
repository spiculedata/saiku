/*
 *   Copyright 2026 Spicule Ltd
 *   Apache License, Version 2.0.
 */
package org.saiku.repository;

/**
 * Saiku-native repository exception, replaces the former {@code javax.jcr.RepositoryException}
 * dependency. Thrown when a repository operation fails.
 */
public class RepositoryException extends Exception {

    public RepositoryException() {
        super();
    }

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public RepositoryException(Throwable cause) {
        super(cause);
    }
}
