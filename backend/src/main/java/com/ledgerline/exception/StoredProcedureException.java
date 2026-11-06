package com.ledgerline.exception;

/**
 * Base exception for failures during stored procedure execution.
 * Preserves the underlying SQLState, MySQL error code, and root cause.
 */
public class StoredProcedureException extends RuntimeException {

    private final String sqlState;
    private final int errorCode;

    public StoredProcedureException(String message, String sqlState, int errorCode, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
        this.errorCode = errorCode;
    }

    public String getSqlState() {
        return sqlState;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public boolean isBusinessViolation() {
        return "45000".equals(sqlState);
    }
}
