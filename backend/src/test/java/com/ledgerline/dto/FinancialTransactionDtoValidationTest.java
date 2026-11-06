package com.ledgerline.dto;

import com.ledgerline.entity.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 1: Financial Transaction DTO and Monetary Validation Tests")
class FinancialTransactionDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // =========================================================================
    // 1. DepositRequest Tests
    // =========================================================================
    @Nested
    @DisplayName("DepositRequest Validation Tests")
    class DepositRequestTests {

        @Test
        @DisplayName("Valid INR amounts (0.01, 100.50, max DECIMAL(19,2)) pass validation")
        void testValidDepositAmounts() {
            // Minimum valid positive amount
            DepositRequest minReq = new DepositRequest(10L, new BigDecimal("0.01"), "INR", "Min deposit");
            Set<ConstraintViolation<DepositRequest>> minViolations = validator.validate(minReq);
            assertTrue(minViolations.isEmpty(), "0.01 INR must pass validation");
            assertDoesNotThrow(minReq::validate);

            // Typical valid amount
            DepositRequest typicalReq = new DepositRequest(10L, new BigDecimal("100.50"), "INR", "Standard deposit");
            Set<ConstraintViolation<DepositRequest>> typicalViolations = validator.validate(typicalReq);
            assertTrue(typicalViolations.isEmpty(), "100.50 INR must pass validation");
            assertDoesNotThrow(typicalReq::validate);

            // Maximum supported DECIMAL(19,2) value: 99,999,999,999,999,999.99 (17 integer digits + 2 fraction)
            DepositRequest maxReq = new DepositRequest(10L, new BigDecimal("99999999999999999.99"), "INR", "Max deposit");
            Set<ConstraintViolation<DepositRequest>> maxViolations = validator.validate(maxReq);
            assertTrue(maxViolations.isEmpty(), "Max DECIMAL(19,2) value must pass validation");
            assertDoesNotThrow(maxReq::validate);
        }

        @Test
        @DisplayName("Zero and negative amounts are strictly rejected")
        void testZeroAndNegativeAmountsRejected() {
            // Zero amount
            DepositRequest zeroReq = new DepositRequest(10L, new BigDecimal("0.00"), "INR");
            Set<ConstraintViolation<DepositRequest>> zeroViolations = validator.validate(zeroReq);
            assertFalse(zeroViolations.isEmpty(), "Zero amount must fail Bean Validation");
            IllegalArgumentException zeroEx = assertThrows(IllegalArgumentException.class, zeroReq::validate);
            assertTrue(zeroEx.getMessage().contains("must be strictly positive"));

            // Negative amount
            DepositRequest negReq = new DepositRequest(10L, new BigDecimal("-50.00"), "INR");
            Set<ConstraintViolation<DepositRequest>> negViolations = validator.validate(negReq);
            assertFalse(negViolations.isEmpty(), "Negative amount must fail Bean Validation");
            IllegalArgumentException negEx = assertThrows(IllegalArgumentException.class, negReq::validate);
            assertTrue(negEx.getMessage().contains("must be strictly positive"));
        }

        @Test
        @DisplayName("More than two decimal places (fractional digits) are rejected")
        void testMoreThanTwoDecimalPlacesRejected() {
            DepositRequest threeDecimals = new DepositRequest(10L, new BigDecimal("100.505"), "INR");
            Set<ConstraintViolation<DepositRequest>> violations3 = validator.validate(threeDecimals);
            assertFalse(violations3.isEmpty(), "100.505 has 3 decimal places and must fail Bean Validation");
            IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, threeDecimals::validate);
            assertTrue(ex3.getMessage().contains("at most 2 decimal places"));

            DepositRequest fourDecimals = new DepositRequest(10L, new BigDecimal("10.0001"), "INR");
            Set<ConstraintViolation<DepositRequest>> violations4 = validator.validate(fourDecimals);
            assertFalse(violations4.isEmpty(), "10.0001 has 4 decimal places and must fail Bean Validation");
            IllegalArgumentException ex4 = assertThrows(IllegalArgumentException.class, fourDecimals::validate);
            assertTrue(ex4.getMessage().contains("at most 2 decimal places"));
        }

        @Test
        @DisplayName("Amount exceeding DECIMAL(19,2) limit (overflow) is rejected")
        void testAmountOverflowRejected() {
            // 18 integer digits + 2 fraction digits exceeds DECIMAL(19,2)
            BigDecimal overflowAmount = new BigDecimal("100000000000000000.00");
            DepositRequest req = new DepositRequest(10L, overflowAmount, "INR");
            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "Overflow amount must fail Bean Validation");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, req::validate);
            assertTrue(ex.getMessage().contains("exceeds maximum supported DECIMAL(19,2) value"));
        }

        @Test
        @DisplayName("Null amount is strictly rejected")
        void testNullAmountRejected() {
            DepositRequest req = new DepositRequest(10L, null, "INR");
            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "Null amount must fail Bean Validation");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, req::validate);
            assertTrue(ex.getMessage().contains("Amount is required"));
        }

        @Test
        @DisplayName("Invalid or non-INR currency is rejected without silent conversion")
        void testInvalidCurrencyRejected() {
            DepositRequest usdReq = new DepositRequest(10L, new BigDecimal("100.00"), "USD");
            assertEquals("USD", usdReq.getCurrency(), "Must preserve input currency without silent conversion");
            Set<ConstraintViolation<DepositRequest>> usdViolations = validator.validate(usdReq);
            assertFalse(usdViolations.isEmpty(), "USD currency must fail Bean Validation");
            IllegalArgumentException usdEx = assertThrows(IllegalArgumentException.class, usdReq::validate);
            assertTrue(usdEx.getMessage().contains("Currency must be INR"));

            DepositRequest nullCurrencyReq = new DepositRequest(10L, new BigDecimal("100.00"), null);
            Set<ConstraintViolation<DepositRequest>> nullViolations = validator.validate(nullCurrencyReq);
            assertFalse(nullViolations.isEmpty(), "Null currency must fail Bean Validation");
            assertThrows(IllegalArgumentException.class, nullCurrencyReq::validate);
        }

        @Test
        @DisplayName("Missing or invalid account ID is rejected")
        void testMissingOrInvalidAccountIdRejected() {
            DepositRequest nullAccountReq = new DepositRequest(null, new BigDecimal("100.00"), "INR");
            Set<ConstraintViolation<DepositRequest>> nullViolations = validator.validate(nullAccountReq);
            assertFalse(nullViolations.isEmpty(), "Null account ID must fail validation");
            assertThrows(IllegalArgumentException.class, nullAccountReq::validate);

            DepositRequest zeroAccountReq = new DepositRequest(0L, new BigDecimal("100.00"), "INR");
            Set<ConstraintViolation<DepositRequest>> zeroViolations = validator.validate(zeroAccountReq);
            assertFalse(zeroViolations.isEmpty(), "Zero account ID must fail validation");
            assertThrows(IllegalArgumentException.class, zeroAccountReq::validate);

            DepositRequest negAccountReq = new DepositRequest(-5L, new BigDecimal("100.00"), "INR");
            Set<ConstraintViolation<DepositRequest>> negViolations = validator.validate(negAccountReq);
            assertFalse(negViolations.isEmpty(), "Negative account ID must fail validation");
            assertThrows(IllegalArgumentException.class, negAccountReq::validate);
        }

        @Test
        @DisplayName("Description exceeding 255 characters is rejected")
        void testDescriptionLengthValidation() {
            String maxDesc = "A".repeat(255);
            DepositRequest validReq = new DepositRequest(10L, new BigDecimal("100.00"), "INR", maxDesc);
            assertDoesNotThrow(validReq::validate);

            String tooLongDesc = "A".repeat(256);
            DepositRequest invalidReq = new DepositRequest(10L, new BigDecimal("100.00"), "INR", tooLongDesc);
            Set<ConstraintViolation<DepositRequest>> violations = validator.validate(invalidReq);
            assertFalse(violations.isEmpty());
            assertThrows(IllegalArgumentException.class, invalidReq::validate);
        }
    }

    // =========================================================================
    // 2. WithdrawalRequest Tests
    // =========================================================================
    @Nested
    @DisplayName("WithdrawalRequest Validation Tests")
    class WithdrawalRequestTests {

        @Test
        @DisplayName("Valid withdrawal amounts pass validation")
        void testValidWithdrawal() {
            WithdrawalRequest req = new WithdrawalRequest(5L, new BigDecimal("250.75"), "INR", "ATM withdrawal");
            Set<ConstraintViolation<WithdrawalRequest>> violations = validator.validate(req);
            assertTrue(violations.isEmpty());
            assertDoesNotThrow(req::validate);
        }

        @Test
        @DisplayName("Zero, negative, null, and overflow amounts are rejected for withdrawals")
        void testInvalidWithdrawalAmounts() {
            WithdrawalRequest zeroReq = new WithdrawalRequest(5L, new BigDecimal("0.00"));
            assertFalse(validator.validate(zeroReq).isEmpty());
            assertThrows(IllegalArgumentException.class, zeroReq::validate);

            WithdrawalRequest negReq = new WithdrawalRequest(5L, new BigDecimal("-10.00"));
            assertFalse(validator.validate(negReq).isEmpty());
            assertThrows(IllegalArgumentException.class, negReq::validate);

            WithdrawalRequest nullReq = new WithdrawalRequest(5L, null);
            assertFalse(validator.validate(nullReq).isEmpty());
            assertThrows(IllegalArgumentException.class, nullReq::validate);

            WithdrawalRequest threeDec = new WithdrawalRequest(5L, new BigDecimal("50.123"));
            assertFalse(validator.validate(threeDec).isEmpty());
            assertThrows(IllegalArgumentException.class, threeDec::validate);

            WithdrawalRequest overflow = new WithdrawalRequest(5L, new BigDecimal("100000000000000000.00"));
            assertFalse(validator.validate(overflow).isEmpty());
            assertThrows(IllegalArgumentException.class, overflow::validate);
        }

        @Test
        @DisplayName("Non-INR currency is rejected for withdrawals")
        void testNonInrCurrencyRejected() {
            WithdrawalRequest req = new WithdrawalRequest(5L, new BigDecimal("100.00"), "EUR");
            assertFalse(validator.validate(req).isEmpty());
            assertThrows(IllegalArgumentException.class, req::validate);
        }
    }

    // =========================================================================
    // 3. TransferRequest Tests
    // =========================================================================
    @Nested
    @DisplayName("TransferRequest Validation Tests")
    class TransferRequestTests {

        @Test
        @DisplayName("Valid transfer with distinct source and destination accounts passes validation")
        void testValidTransfer() {
            TransferRequest req = new TransferRequest(10L, 20L, new BigDecimal("500.00"), "INR", "Rent payment");
            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "Valid transfer must pass Bean Validation");
            assertDoesNotThrow(req::validate);
        }

        @Test
        @DisplayName("Transfer where source and destination accounts are identical is strictly rejected")
        void testSameSourceAndDestinationRejected() {
            TransferRequest sameAccountReq = new TransferRequest(15L, 15L, new BigDecimal("100.00"), "INR");
            Set<ConstraintViolation<TransferRequest>> violations = validator.validate(sameAccountReq);
            assertFalse(violations.isEmpty(), "Transfer to self must fail Bean Validation");
            assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("must be different")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, sameAccountReq::validate);
            assertTrue(ex.getMessage().contains("Source and destination accounts must be different"));
        }

        @Test
        @DisplayName("Missing required source or destination account ID is rejected")
        void testMissingAccountIdsRejected() {
            TransferRequest missingSource = new TransferRequest(null, 20L, new BigDecimal("100.00"), "INR");
            assertFalse(validator.validate(missingSource).isEmpty());
            assertThrows(IllegalArgumentException.class, missingSource::validate);

            TransferRequest missingDest = new TransferRequest(10L, null, new BigDecimal("100.00"), "INR");
            assertFalse(validator.validate(missingDest).isEmpty());
            assertThrows(IllegalArgumentException.class, missingDest::validate);
        }

        @Test
        @DisplayName("Invalid monetary values are rejected for transfers")
        void testInvalidTransferAmounts() {
            TransferRequest zeroReq = new TransferRequest(10L, 20L, new BigDecimal("0.00"));
            assertFalse(validator.validate(zeroReq).isEmpty());
            assertThrows(IllegalArgumentException.class, zeroReq::validate);

            TransferRequest negReq = new TransferRequest(10L, 20L, new BigDecimal("-200.00"));
            assertFalse(validator.validate(negReq).isEmpty());
            assertThrows(IllegalArgumentException.class, negReq::validate);

            TransferRequest decReq = new TransferRequest(10L, 20L, new BigDecimal("200.999"));
            assertFalse(validator.validate(decReq).isEmpty());
            assertThrows(IllegalArgumentException.class, decReq::validate);

            TransferRequest overflowReq = new TransferRequest(10L, 20L, new BigDecimal("100000000000000000.00"));
            assertFalse(validator.validate(overflowReq).isEmpty());
            assertThrows(IllegalArgumentException.class, overflowReq::validate);
        }

        @Test
        @DisplayName("Non-INR currency is rejected for transfers")
        void testInvalidCurrencyTransfer() {
            TransferRequest req = new TransferRequest(10L, 20L, new BigDecimal("100.00"), "GBP");
            assertFalse(validator.validate(req).isEmpty());
            assertThrows(IllegalArgumentException.class, req::validate);
        }
    }

    // =========================================================================
    // 4. Response DTO Tests & Security / Safe Serialization
    // =========================================================================
    @Nested
    @DisplayName("Response DTO Mapping and Security Tests")
    class ResponseDtoSecurityTests {

        @Test
        @DisplayName("TransactionResponse maps correctly from Transaction entity without exposing user password or credentials")
        void testTransactionResponseMappingSecurity() {
            User initiatingUser = new User("alice", "alice@ledgerline.test", "$2a$10$SecretPasswordHashThatMustNeverBeExposed", UserRole.ACCOUNT_HOLDER);
            initiatingUser.setId(42L);

            Account source = new Account("ACC-SRC-001", "Alice Checking", AccountType.CUSTOMER, "INR");
            source.setId(101L);

            Account dest = new Account("ACC-DEST-002", "Bob Savings", AccountType.CUSTOMER, "INR");
            dest.setId(202L);

            Transaction tx = new Transaction();
            tx.setId("tx-uuid-12345");
            tx.setReferenceId("ref-67890");
            tx.setInitiatingUser(initiatingUser);
            tx.setSourceAccount(source);
            tx.setDestinationAccount(dest);
            tx.setAmount(new BigDecimal("750.00"));
            tx.setCurrency("INR");
            tx.setType(TransactionType.TRANSFER);
            tx.setStatus(TransactionStatus.POSTED);
            tx.setDescription("Monthly allowance");
            tx.setCompletedAt(Instant.now());

            TransactionResponse resp = TransactionResponse.fromEntity(tx);

            assertNotNull(resp);
            assertEquals("tx-uuid-12345", resp.getTransactionId());
            assertEquals("ref-67890", resp.getReferenceId());
            assertEquals(42L, resp.getInitiatingUserId(), "Exposes scalar user ID only");
            assertEquals(101L, resp.getSourceAccountId());
            assertEquals("ACC-SRC-001", resp.getSourceAccountNumber());
            assertEquals(202L, resp.getDestinationAccountId());
            assertEquals("ACC-DEST-002", resp.getDestinationAccountNumber());
            assertEquals(new BigDecimal("750.00"), resp.getAmount());
            assertEquals("INR", resp.getCurrency());
            assertEquals(TransactionType.TRANSFER, resp.getType());
            assertEquals(TransactionStatus.POSTED, resp.getStatus());
            assertNull(resp.getRefusalReason());
            assertEquals("Monthly allowance", resp.getDescription());
            assertNotNull(resp.getCompletedAt());
        }

        @Test
        @DisplayName("StatementEventResponse maps correctly from StatementEvent entity")
        void testStatementEventResponseMapping() {
            Account account = new Account("ACC-101", "Alice", AccountType.CUSTOMER, "INR");
            account.setId(101L);

            StatementEvent event = new StatementEvent();
            event.setId(1L);
            event.setAccount(account);
            event.setSequenceNumber(7L);
            event.setTransactionId("tx-123");
            event.setEventType(StatementEventType.POSTED_CREDIT);
            event.setAmount(new BigDecimal("500.00"));
            event.setRunningBalance(new BigDecimal("1500.00"));
            event.setStatus("POSTED");
            event.setReason(null);

            StatementEventResponse resp = StatementEventResponse.fromEntity(event);

            assertNotNull(resp);
            assertEquals(1L, resp.getId());
            assertEquals(101L, resp.getAccountId());
            assertEquals("ACC-101", resp.getAccountNumber());
            assertEquals(7L, resp.getSequenceNumber());
            assertEquals("tx-123", resp.getTransactionId());
            assertEquals(StatementEventType.POSTED_CREDIT, resp.getEventType());
            assertEquals(new BigDecimal("500.00"), resp.getAmount());
            assertEquals(new BigDecimal("1500.00"), resp.getRunningBalance());
            assertEquals("POSTED", resp.getStatus());
            assertNull(resp.getReason());
        }

        @Test
        @DisplayName("AccountStatementResponse bundles account summary and statement events safely")
        void testAccountStatementResponse() {
            Account account = new Account("ACC-101", "Alice Checking", AccountType.CUSTOMER, "INR");
            account.setId(101L);
            account.setBalance(new BigDecimal("1500.00"));
            account.setStatus(AccountStatus.ACTIVE);

            StatementEvent event1 = new StatementEvent();
            event1.setId(1L);
            event1.setAccount(account);
            event1.setSequenceNumber(1L);
            event1.setEventType(StatementEventType.POSTED_CREDIT);
            event1.setAmount(new BigDecimal("2000.00"));
            event1.setRunningBalance(new BigDecimal("2000.00"));
            event1.setStatus("POSTED");

            StatementEvent event2 = new StatementEvent();
            event2.setId(2L);
            event2.setAccount(account);
            event2.setSequenceNumber(2L);
            event2.setEventType(StatementEventType.POSTED_DEBIT);
            event2.setAmount(new BigDecimal("500.00"));
            event2.setRunningBalance(new BigDecimal("1500.00"));
            event2.setStatus("POSTED");

            AccountStatementResponse statement = AccountStatementResponse.fromEntities(account, List.of(event1, event2));

            assertNotNull(statement);
            assertEquals(101L, statement.getAccountId());
            assertEquals("ACC-101", statement.getAccountNumber());
            assertEquals("Alice Checking", statement.getName());
            assertEquals(AccountType.CUSTOMER, statement.getAccountType());
            assertEquals("INR", statement.getCurrency());
            assertEquals(new BigDecimal("1500.00"), statement.getBalance());
            assertEquals(AccountStatus.ACTIVE, statement.getStatus());
            assertEquals(2, statement.getTotalEvents());
            assertEquals(2, statement.getEvents().size());
            assertEquals(1L, statement.getEvents().get(0).getSequenceNumber());
            assertEquals(2L, statement.getEvents().get(1).getSequenceNumber());
            assertNotNull(statement.getAsOf());
        }

        @Test
        @DisplayName("Null entity mapping returns null safely without throwing NullPointerException")
        void testNullEntityMappingSafety() {
            assertNull(TransactionResponse.fromEntity(null));
            assertNull(StatementEventResponse.fromEntity(null));
            assertNull(AccountStatementResponse.from(null, null));
            assertNull(AccountStatementResponse.fromEntities(null, null));
        }
    }
}
