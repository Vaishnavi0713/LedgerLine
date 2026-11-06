package com.ledgerline.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an idempotency conflict occurs:
 * - The same idempotency key was previously used with a different request payload (HTTP 409).
 * - A concurrent request with the same idempotency key is already in progress and timed out (HTTP 409).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
