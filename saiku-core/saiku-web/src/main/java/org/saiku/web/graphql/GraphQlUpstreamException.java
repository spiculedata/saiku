/*
 *   Copyright 2026 Spicule Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package org.saiku.web.graphql;

import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import java.util.List;
import java.util.Map;

/**
 * Thrown from a data fetcher when the delegated REST resource returned a non-2xx response.
 *
 * <p>graphql-java catches the exception, records it as a {@link GraphQLError} at the current
 * execution path, and continues with a null value for that field. The upstream REST body is
 * preserved via the {@code extensions} map so a caller can still see e.g. the
 * {@code {field, available}} envelope that /ai/query emits on VALIDATION_ERROR.
 */
public class GraphQlUpstreamException extends RuntimeException implements GraphQLError {

    private final Map<String, Object> extensions;

    public GraphQlUpstreamException(String message, Map<String, Object> extensions) {
        super(message);
        this.extensions = extensions;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return List.of();
    }

    @Override
    public ErrorClassification getErrorType() {
        return ErrorType.DataFetchingException;
    }

    @Override
    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
