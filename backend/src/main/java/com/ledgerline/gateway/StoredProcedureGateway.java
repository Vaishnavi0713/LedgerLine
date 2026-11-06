package com.ledgerline.gateway;

import com.ledgerline.entity.StatementEventType;
import com.ledgerline.exception.StoredProcedureException;
import com.ledgerline.exception.StoredProcedureViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Dedicated gateway for invoking MySQL write-boundary stored procedures:
 * 1. sp_post_ledger_pair: Atomically validates invariants, orders row locks,
 *    updates account balances, inserts balanced double-entry ledger records,
 *    and transitions transaction status to POSTED.
 * 2. sp_record_statement_event: Atomically appends monotonic statement history
 *    for posted transactions and records refused transaction attempts.
 *
 * Guarantees:
 * - Parameterized JDBC calls preventing SQL injection.
 * - Participates in the active Spring-managed transaction via JdbcTemplate.
 * - Leaves transaction lifecycle (commit/rollback) strictly to Spring.
 * - Never executes direct balance updates or direct ledger/statement inserts.
 * - Maps SQLSTATE 45000 signals to StoredProcedureViolationException.
 */
@Component
public class StoredProcedureGateway {

    private static final Logger log = LoggerFactory.getLogger(StoredProcedureGateway.class);

    private final JdbcTemplate jdbcTemplate;

    public StoredProcedureGateway(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Invokes sp_post_ledger_pair with full parameter specification.
     * Optional parameters (balances, sequences) can be null to let the procedure derive them.
     */
    public void postLedgerPair(
            String transactionId,
            Long initiatingUserId,
            Long sourceAccountId,
            Long destAccountId,
            BigDecimal amount,
            BigDecimal sourceBalanceAfter,
            BigDecimal destBalanceAfter,
            Long sourceSequence,
            Long destSequence) {

        String callSql = "{CALL sp_post_ledger_pair(?, ?, ?, ?, ?, ?, ?, ?, ?)}";

        executeCall(callSql, cs -> {
            cs.setString(1, transactionId);
            setLongOrNull(cs, 2, initiatingUserId);
            setLongOrNull(cs, 3, sourceAccountId);
            setLongOrNull(cs, 4, destAccountId);
            setBigDecimalOrNull(cs, 5, amount);
            setBigDecimalOrNull(cs, 6, sourceBalanceAfter);
            setBigDecimalOrNull(cs, 7, destBalanceAfter);
            setLongOrNull(cs, 8, sourceSequence);
            setLongOrNull(cs, 9, destSequence);
        });
    }

    /**
     * Convenience overload for postLedgerPair where the stored procedure derives
     * the resulting balances and monotonic statement sequences.
     */
    public void postLedgerPair(
            String transactionId,
            Long initiatingUserId,
            Long sourceAccountId,
            Long destAccountId,
            BigDecimal amount) {
        postLedgerPair(transactionId, initiatingUserId, sourceAccountId, destAccountId, amount, null, null, null, null);
    }

    /**
     * Invokes sp_record_statement_event with full parameter specification.
     */
    public void recordStatementEvent(
            Long accountId,
            Long sequenceNumber,
            Long initiatingUserId,
            String transactionId,
            String eventType,
            BigDecimal amount,
            BigDecimal runningBalance,
            String status,
            String reason) {

        String callSql = "{CALL sp_record_statement_event(?, ?, ?, ?, ?, ?, ?, ?, ?)}";

        executeCall(callSql, cs -> {
            setLongOrNull(cs, 1, accountId);
            setLongOrNull(cs, 2, sequenceNumber);
            setLongOrNull(cs, 3, initiatingUserId);
            setStringOrNull(cs, 4, transactionId);
            cs.setString(5, eventType);
            setBigDecimalOrNull(cs, 6, amount);
            setBigDecimalOrNull(cs, 7, runningBalance);
            cs.setString(8, status);
            setStringOrNull(cs, 9, reason);
        });
    }

    /**
     * Convenience method to record a statement event for a successfully posted transaction leg.
     */
    public void recordPostedStatementEvent(
            Long accountId,
            String transactionId,
            Long initiatingUserId,
            StatementEventType eventType,
            BigDecimal amount) {
        recordStatementEvent(
                accountId,
                null,
                initiatingUserId,
                transactionId,
                eventType.name(),
                amount,
                null,
                "POSTED",
                null
        );
    }

    /**
     * Convenience method to record a statement event for a refused transaction attempt.
     * The stored procedure increments the account sequence and records the refusal reason,
     * while leaving the account balance untouched.
     */
    public void recordRefusedStatementEvent(
            Long accountId,
            String transactionId,
            Long initiatingUserId,
            StatementEventType eventType,
            BigDecimal amount,
            String refusalReason) {
        recordStatementEvent(
                accountId,
                null,
                initiatingUserId,
                transactionId,
                eventType.name(),
                amount,
                null,
                "REFUSED",
                refusalReason
        );
    }

    // =========================================================================
    // Internal Helper Methods
    // =========================================================================

    @FunctionalInterface
    private interface ParameterSetter {
        void setParameters(CallableStatement cs) throws SQLException;
    }

    private void executeCall(String callSql, ParameterSetter setter) {
        try {
            jdbcTemplate.execute(
                    (CallableStatementCreator) con -> {
                        CallableStatement cs = con.prepareCall(callSql);
                        setter.setParameters(cs);
                        return cs;
                    },
                    (CallableStatementCallback<Void>) cs -> {
                        cs.execute();
                        return null;
                    }
            );
        } catch (DataAccessException dae) {
            handleDataAccessException(dae);
        }
    }

    private void handleDataAccessException(DataAccessException dae) {
        SQLException sqlEx = extractSqlException(dae);
        if (sqlEx != null) {
            String sqlState = sqlEx.getSQLState();
            int errorCode = sqlEx.getErrorCode();
            String message = sqlEx.getMessage();

            if ("45000".equals(sqlState)) {
                log.warn("Stored procedure business violation (SQLSTATE 45000, error {}): {}", errorCode, message);
                throw new StoredProcedureViolationException(message, sqlState, errorCode, dae);
            }

            log.error("Database error during stored procedure execution (SQLSTATE {}, error {}): {}", sqlState, errorCode, message);
            throw new StoredProcedureException(message, sqlState, errorCode, dae);
        }
        throw new StoredProcedureException("Data access error during stored procedure execution", "UNKNOWN", 0, dae);
    }

    private SQLException extractSqlException(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof SQLException sqlEx) {
                return sqlEx;
            }
            curr = curr.getCause();
        }
        return null;
    }

    private void setLongOrNull(CallableStatement cs, int index, Long value) throws SQLException {
        if (value != null) {
            cs.setLong(index, value);
        } else {
            cs.setNull(index, Types.BIGINT);
        }
    }

    private void setBigDecimalOrNull(CallableStatement cs, int index, BigDecimal value) throws SQLException {
        if (value != null) {
            cs.setBigDecimal(index, value);
        } else {
            cs.setNull(index, Types.DECIMAL);
        }
    }

    private void setStringOrNull(CallableStatement cs, int index, String value) throws SQLException {
        if (value != null) {
            cs.setString(index, value);
        } else {
            cs.setNull(index, Types.VARCHAR);
        }
    }
}
