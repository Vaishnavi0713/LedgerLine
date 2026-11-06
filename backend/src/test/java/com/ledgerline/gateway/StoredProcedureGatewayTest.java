package com.ledgerline.gateway;

import com.ledgerline.entity.StatementEventType;
import com.ledgerline.exception.StoredProcedureException;
import com.ledgerline.exception.StoredProcedureViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Task 2: StoredProcedureGateway Unit Tests")
class StoredProcedureGatewayTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection connection;

    @Mock
    private CallableStatement callableStatement;

    private StoredProcedureGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new StoredProcedureGateway(jdbcTemplate);
    }

    @Test
    @DisplayName("postLedgerPair maps all parameters and executes callable statement")
    void testPostLedgerPairParameterMapping() throws SQLException {
        when(connection.prepareCall("{CALL sp_post_ledger_pair(?, ?, ?, ?, ?, ?, ?, ?, ?)}"))
                .thenReturn(callableStatement);

        doAnswer(invocation -> {
            CallableStatementCreator csc = invocation.getArgument(0);
            CallableStatementCallback<?> callback = invocation.getArgument(1);
            CallableStatement cs = csc.createCallableStatement(connection);
            return callback.doInCallableStatement(cs);
        }).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        gateway.postLedgerPair(
                "tx-100",
                1L,
                2L,
                3L,
                new BigDecimal("500.00"),
                new BigDecimal("1500.00"),
                new BigDecimal("2500.00"),
                10L,
                20L
        );

        verify(callableStatement).setString(1, "tx-100");
        verify(callableStatement).setLong(2, 1L);
        verify(callableStatement).setLong(3, 2L);
        verify(callableStatement).setLong(4, 3L);
        verify(callableStatement).setBigDecimal(5, new BigDecimal("500.00"));
        verify(callableStatement).setBigDecimal(6, new BigDecimal("1500.00"));
        verify(callableStatement).setBigDecimal(7, new BigDecimal("2500.00"));
        verify(callableStatement).setLong(8, 10L);
        verify(callableStatement).setLong(9, 20L);
        verify(callableStatement).execute();
    }

    @Test
    @DisplayName("postLedgerPair with null optional parameters sets NULL for derived balances and sequences")
    void testPostLedgerPairNullOptionalParameters() throws SQLException {
        when(connection.prepareCall("{CALL sp_post_ledger_pair(?, ?, ?, ?, ?, ?, ?, ?, ?)}"))
                .thenReturn(callableStatement);

        doAnswer(invocation -> {
            CallableStatementCreator csc = invocation.getArgument(0);
            CallableStatementCallback<?> callback = invocation.getArgument(1);
            CallableStatement cs = csc.createCallableStatement(connection);
            return callback.doInCallableStatement(cs);
        }).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        gateway.postLedgerPair("tx-200", 1L, 2L, 3L, new BigDecimal("100.00"));

        verify(callableStatement).setString(1, "tx-200");
        verify(callableStatement).setLong(2, 1L);
        verify(callableStatement).setLong(3, 2L);
        verify(callableStatement).setLong(4, 3L);
        verify(callableStatement).setBigDecimal(5, new BigDecimal("100.00"));
        verify(callableStatement).setNull(6, Types.DECIMAL);
        verify(callableStatement).setNull(7, Types.DECIMAL);
        verify(callableStatement).setNull(8, Types.BIGINT);
        verify(callableStatement).setNull(9, Types.BIGINT);
        verify(callableStatement).execute();
    }

    @Test
    @DisplayName("recordStatementEvent maps all parameters and executes callable statement")
    void testRecordStatementEventParameterMapping() throws SQLException {
        when(connection.prepareCall("{CALL sp_record_statement_event(?, ?, ?, ?, ?, ?, ?, ?, ?)}"))
                .thenReturn(callableStatement);

        doAnswer(invocation -> {
            CallableStatementCreator csc = invocation.getArgument(0);
            CallableStatementCallback<?> callback = invocation.getArgument(1);
            CallableStatement cs = csc.createCallableStatement(connection);
            return callback.doInCallableStatement(cs);
        }).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        gateway.recordStatementEvent(
                10L,
                5L,
                1L,
                "tx-300",
                "POSTED_DEBIT",
                new BigDecimal("250.00"),
                new BigDecimal("750.00"),
                "POSTED",
                "Rent"
        );

        verify(callableStatement).setLong(1, 10L);
        verify(callableStatement).setLong(2, 5L);
        verify(callableStatement).setLong(3, 1L);
        verify(callableStatement).setString(4, "tx-300");
        verify(callableStatement).setString(5, "POSTED_DEBIT");
        verify(callableStatement).setBigDecimal(6, new BigDecimal("250.00"));
        verify(callableStatement).setBigDecimal(7, new BigDecimal("750.00"));
        verify(callableStatement).setString(8, "POSTED");
        verify(callableStatement).setString(9, "Rent");
        verify(callableStatement).execute();
    }

    @Test
    @DisplayName("recordRefusedStatementEvent convenience method sets REFUSED status and refusal reason")
    void testRecordRefusedStatementEvent() throws SQLException {
        when(connection.prepareCall("{CALL sp_record_statement_event(?, ?, ?, ?, ?, ?, ?, ?, ?)}"))
                .thenReturn(callableStatement);

        doAnswer(invocation -> {
            CallableStatementCreator csc = invocation.getArgument(0);
            CallableStatementCallback<?> callback = invocation.getArgument(1);
            CallableStatement cs = csc.createCallableStatement(connection);
            return callback.doInCallableStatement(cs);
        }).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        gateway.recordRefusedStatementEvent(
                10L,
                "tx-400",
                1L,
                StatementEventType.REFUSED_WITHDRAWAL,
                new BigDecimal("5000.00"),
                "INSUFFICIENT_FUNDS"
        );

        verify(callableStatement).setLong(1, 10L);
        verify(callableStatement).setNull(2, Types.BIGINT);
        verify(callableStatement).setLong(3, 1L);
        verify(callableStatement).setString(4, "tx-400");
        verify(callableStatement).setString(5, "REFUSED_WITHDRAWAL");
        verify(callableStatement).setBigDecimal(6, new BigDecimal("5000.00"));
        verify(callableStatement).setNull(7, Types.DECIMAL);
        verify(callableStatement).setString(8, "REFUSED");
        verify(callableStatement).setString(9, "INSUFFICIENT_FUNDS");
        verify(callableStatement).execute();
    }

    @Test
    @DisplayName("SQLSTATE 45000 is mapped to StoredProcedureViolationException")
    void testSqlState45000MappedToViolationException() {
        SQLException sqlEx = new SQLException("VIOLATION: Insufficient balance in source customer account.", "45000", 1644);
        DataAccessException dae = new UncategorizedSQLException("Procedure call failed", "{CALL sp_post_ledger_pair(...)}", sqlEx);

        doThrow(dae).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        StoredProcedureViolationException ex = assertThrows(StoredProcedureViolationException.class, () ->
                gateway.postLedgerPair("tx-insufficient", 1L, 2L, 3L, new BigDecimal("10000.00")));

        assertEquals("45000", ex.getSqlState());
        assertEquals(1644, ex.getErrorCode());
        assertTrue(ex.isBusinessViolation());
        assertTrue(ex.getMessage().contains("Insufficient balance"));
    }

    @Test
    @DisplayName("Non-45000 SQL errors are mapped to StoredProcedureException")
    void testGenericSqlErrorMappedToStoredProcedureException() {
        SQLException sqlEx = new SQLException("Deadlock found when trying to get lock", "40001", 1213);
        DataAccessException dae = new UncategorizedSQLException("Deadlock", "{CALL sp_post_ledger_pair(...)}", sqlEx);

        doThrow(dae).when(jdbcTemplate).execute(any(CallableStatementCreator.class), any(CallableStatementCallback.class));

        StoredProcedureException ex = assertThrows(StoredProcedureException.class, () ->
                gateway.postLedgerPair("tx-deadlock", 1L, 2L, 3L, new BigDecimal("100.00")));

        assertFalse(ex instanceof StoredProcedureViolationException);
        assertEquals("40001", ex.getSqlState());
        assertEquals(1213, ex.getErrorCode());
        assertFalse(ex.isBusinessViolation());
        assertTrue(ex.getMessage().contains("Deadlock found"));
    }
}
