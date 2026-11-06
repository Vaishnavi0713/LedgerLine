package com.ledgerline.exception;

/**
 * Thrown when a stored procedure raises a SQLSTATE 45000 business violation
 * (e.g., insufficient funds, inactive account, parameter forgery, duplicate posting).
 */
public class StoredProcedureViolationException extends StoredProcedureException {

    public StoredProcedureViolationException(String message, String sqlState, int errorCode, Throwable cause) {
        super(message, sqlState, errorCode, cause);
    }
}
