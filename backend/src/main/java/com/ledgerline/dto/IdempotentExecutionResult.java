package com.ledgerline.dto;

/**
 * Encapsulates the result of an idempotent operation execution.
 *
 * @param replayed whether this result was retrieved from an existing cached response
 * @param statusCode HTTP status code (e.g., 200, 201, 422)
 * @param body the response payload
 * @param <T> payload type
 */
public record IdempotentExecutionResult<T>(
        boolean replayed,
        int statusCode,
        T body
) {}
