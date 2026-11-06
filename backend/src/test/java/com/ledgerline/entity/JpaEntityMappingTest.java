package com.ledgerline.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JpaEntityMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("JPA Entities: Verify persistence and mapping for all 8 entities")
    void testAllEntitiesPersistSuccessfully() {
        // 1. User
        User user = new User("testuser", "test@ledgerline.test", "hashed_pw", UserRole.ACCOUNT_HOLDER);
        entityManager.persist(user);
        assertNotNull(user.getId());

        // 2. Account (Customer)
        Account accountA = new Account("ACC-001", "Customer A", AccountType.CUSTOMER, "INR");
        accountA.setBalance(new BigDecimal("1000.00"));
        entityManager.persist(accountA);
        assertNotNull(accountA.getId());
        assertEquals(new BigDecimal("1000.00"), accountA.getBalance());

        // 2b. Account (SYSTEM_CLEARING)
        Account clearing = new Account("ACC-SYSTEM-CLEARING-001", "Clearing Pool", AccountType.SYSTEM_CLEARING, "INR");
        clearing.setBalance(new BigDecimal("1000.00"));
        entityManager.persist(clearing);
        assertNotNull(clearing.getId());

        // 3. AccountPermission
        AccountPermission permission = new AccountPermission(user, accountA, PermissionType.CAN_TRANSFER);
        entityManager.persist(permission);
        assertNotNull(permission.getId());

        // 4. Transaction
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID().toString());
        tx.setReferenceId("TXN-REF-001");
        tx.setInitiatingUser(user);
        tx.setSourceAccount(accountA);
        tx.setDestinationAccount(clearing);
        tx.setAmount(new BigDecimal("200.00"));
        tx.setCurrency("INR");
        tx.setType(TransactionType.WITHDRAWAL);
        tx.setStatus(TransactionStatus.POSTED);
        tx.setCompletedAt(Instant.now());
        entityManager.persist(tx);
        assertNotNull(tx.getId());

        // 5. LedgerEntry
        LedgerEntry debitEntry = new LedgerEntry();
        debitEntry.setTransactionId(tx.getId());
        debitEntry.setAccount(accountA);
        debitEntry.setInitiatingUserId(user.getId());
        debitEntry.setEntryType(EntryType.DEBIT);
        debitEntry.setAmount(new BigDecimal("200.00"));
        debitEntry.setBalanceAfter(new BigDecimal("800.00"));
        debitEntry.setSequenceNumber(1L);
        entityManager.persist(debitEntry);
        assertNotNull(debitEntry.getId());

        // 6. StatementEvent (including running balance and sequence number)
        StatementEvent statementEvent = new StatementEvent();
        statementEvent.setAccount(accountA);
        statementEvent.setSequenceNumber(1L);
        statementEvent.setInitiatingUserId(user.getId());
        statementEvent.setTransactionId(tx.getId());
        statementEvent.setEventType(StatementEventType.POSTED_DEBIT);
        statementEvent.setAmount(new BigDecimal("200.00"));
        statementEvent.setRunningBalance(new BigDecimal("800.00"));
        statementEvent.setStatus("POSTED");
        entityManager.persist(statementEvent);
        assertNotNull(statementEvent.getId());

        // 7. IdempotencyKey
        IdempotencyKey idemKey = new IdempotencyKey();
        idemKey.setUser(user);
        idemKey.setOperationType("WITHDRAWAL");
        idemKey.setIdempotencyKey("idem-uuid-001");
        idemKey.setRequestHash("sha256_dummy_hash");
        idemKey.setStatus(IdempotencyStatus.COMPLETED);
        idemKey.setResponseStatus(200);
        idemKey.setResponseBody("{\"status\":\"POSTED\"}");
        idemKey.setExpiresAt(Instant.now().plusSeconds(86400));
        entityManager.persist(idemKey);
        assertNotNull(idemKey.getId());

        // 8. AuditRecord
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setUser(user);
        auditRecord.setAction("WITHDRAWAL_INITIATED");
        auditRecord.setResourceType("ACCOUNT");
        auditRecord.setResourceId(accountA.getId().toString());
        auditRecord.setIpAddress("127.0.0.1");
        auditRecord.setStatus(AuditStatus.SUCCESS);
        entityManager.persist(auditRecord);
        assertNotNull(auditRecord.getId());

        entityManager.flush();
    }
}
