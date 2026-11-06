package com.ledgerline.repository;

import com.ledgerline.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("Task 2: Financial Repositories Spring Data JPA Query Tests")
class FinancialRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private StatementEventRepository statementEventRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    private User userAlice;
    private User userBob;
    private Account accountA;
    private Account accountB;
    private Account clearingAccount;

    @BeforeEach
    void setUp() {
        userAlice = new User("alice_repo", "alice_repo@ledgerline.test", "hash1", UserRole.ACCOUNT_HOLDER);
        userBob = new User("bob_repo", "bob_repo@ledgerline.test", "hash2", UserRole.ACCOUNT_HOLDER);
        entityManager.persist(userAlice);
        entityManager.persist(userBob);

        clearingAccount = new Account("ACC-CLEARING-TEST", "System Clearing", AccountType.SYSTEM_CLEARING, "INR");
        accountA = new Account("ACC-CUSTOMER-A", "Alice Checking", AccountType.CUSTOMER, "INR");
        accountB = new Account("ACC-CUSTOMER-B", "Bob Savings", AccountType.CUSTOMER, "INR");

        entityManager.persist(clearingAccount);
        entityManager.persist(accountA);
        entityManager.persist(accountB);
        entityManager.flush();
    }

    // =========================================================================
    // 1. TransactionRepository Tests
    // =========================================================================
    @Test
    @DisplayName("TransactionRepository finds by referenceId, status, and account participation")
    void testTransactionRepositoryQueries() {
        Transaction tx1 = new Transaction();
        tx1.setId("tx-test-001");
        tx1.setReferenceId("ref-test-001");
        tx1.setInitiatingUser(userAlice);
        tx1.setSourceAccount(accountA);
        tx1.setDestinationAccount(accountB);
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setCurrency("INR");
        tx1.setType(TransactionType.TRANSFER);
        tx1.setStatus(TransactionStatus.POSTED);
        entityManager.persist(tx1);

        Transaction tx2 = new Transaction();
        tx2.setId("tx-test-002");
        tx2.setReferenceId("ref-test-002");
        tx2.setInitiatingUser(userAlice);
        tx2.setSourceAccount(clearingAccount);
        tx2.setDestinationAccount(accountA);
        tx2.setAmount(new BigDecimal("500.00"));
        tx2.setCurrency("INR");
        tx2.setType(TransactionType.DEPOSIT);
        tx2.setStatus(TransactionStatus.PENDING);
        entityManager.persist(tx2);

        entityManager.flush();

        // 1. findByReferenceId & existsByReferenceId
        assertTrue(transactionRepository.existsByReferenceId("ref-test-001"));
        assertFalse(transactionRepository.existsByReferenceId("non-existent-ref"));

        Optional<Transaction> foundRef = transactionRepository.findByReferenceId("ref-test-001");
        assertTrue(foundRef.isPresent());
        assertEquals("tx-test-001", foundRef.get().getId());

        // 2. findByStatus
        List<Transaction> pending = transactionRepository.findByStatus(TransactionStatus.PENDING);
        assertEquals(1, pending.size());
        assertEquals("tx-test-002", pending.get(0).getId());

        // 3. findByAccountIdOrderByCreatedAtDesc (participates as source or destination)
        List<Transaction> accATx = transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountA.getId());
        assertEquals(2, accATx.size(), "Account A participated in both transactions");

        List<Transaction> accBTx = transactionRepository.findByAccountIdOrderByCreatedAtDesc(accountB.getId());
        assertEquals(1, accBTx.size(), "Account B participated in only one transaction");

        // 4. findByAccountId with pagination
        Page<Transaction> page = transactionRepository.findByAccountId(accountA.getId(), PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements());

        // 5. findByAccountIdAndInitiatingUserId
        List<Transaction> aliceTxOnA = transactionRepository.findByAccountIdAndInitiatingUserId(accountA.getId(), userAlice.getId());
        assertEquals(2, aliceTxOnA.size());

        List<Transaction> bobTxOnA = transactionRepository.findByAccountIdAndInitiatingUserId(accountA.getId(), userBob.getId());
        assertTrue(bobTxOnA.isEmpty(), "Bob initiated no transactions on Account A");
    }

    // =========================================================================
    // 2. LedgerEntryRepository Tests
    // =========================================================================
    @Test
    @DisplayName("LedgerEntryRepository queries ledger entries in sequence and by transaction")
    void testLedgerEntryRepositoryQueries() {
        LedgerEntry entry1 = new LedgerEntry();
        entry1.setTransactionId("tx-ledger-1");
        entry1.setAccount(accountA);
        entry1.setInitiatingUserId(userAlice.getId());
        entry1.setEntryType(EntryType.DEBIT);
        entry1.setAmount(new BigDecimal("200.00"));
        entry1.setBalanceAfter(new BigDecimal("800.00"));
        entry1.setSequenceNumber(1L);
        entityManager.persist(entry1);

        LedgerEntry entry2 = new LedgerEntry();
        entry2.setTransactionId("tx-ledger-1");
        entry2.setAccount(accountB);
        entry2.setInitiatingUserId(userAlice.getId());
        entry2.setEntryType(EntryType.CREDIT);
        entry2.setAmount(new BigDecimal("200.00"));
        entry2.setBalanceAfter(new BigDecimal("200.00"));
        entry2.setSequenceNumber(1L);
        entityManager.persist(entry2);

        entityManager.flush();

        // 1. findByTransactionId
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId("tx-ledger-1");
        assertEquals(2, entries.size(), "Must find exactly 2 balanced ledger entries for transaction");
        assertEquals(2, ledgerEntryRepository.countByTransactionId("tx-ledger-1"));

        // 2. findByAccountIdOrderBySequenceNumberAsc
        List<LedgerEntry> accountAEntries = ledgerEntryRepository.findByAccountIdOrderBySequenceNumberAsc(accountA.getId());
        assertEquals(1, accountAEntries.size());
        assertEquals(EntryType.DEBIT, accountAEntries.get(0).getEntryType());

        // 3. findByAccountIdAndSequenceNumber
        Optional<LedgerEntry> entryOpt = ledgerEntryRepository.findByAccountIdAndSequenceNumber(accountA.getId(), 1L);
        assertTrue(entryOpt.isPresent());
        assertEquals(new BigDecimal("800.00"), entryOpt.get().getBalanceAfter());
    }

    // =========================================================================
    // 3. StatementEventRepository Tests
    // =========================================================================
    @Test
    @DisplayName("StatementEventRepository queries statement events with monotonic sequences and ranges")
    void testStatementEventRepositoryQueries() {
        StatementEvent ev1 = new StatementEvent();
        ev1.setAccount(accountA);
        ev1.setSequenceNumber(1L);
        ev1.setInitiatingUserId(userAlice.getId());
        ev1.setTransactionId("tx-stmt-1");
        ev1.setEventType(StatementEventType.POSTED_CREDIT);
        ev1.setAmount(new BigDecimal("1000.00"));
        ev1.setRunningBalance(new BigDecimal("1000.00"));
        ev1.setStatus("POSTED");
        entityManager.persist(ev1);

        StatementEvent ev2 = new StatementEvent();
        ev2.setAccount(accountA);
        ev2.setSequenceNumber(2L);
        ev2.setInitiatingUserId(userAlice.getId());
        ev2.setTransactionId("tx-stmt-2");
        ev2.setEventType(StatementEventType.POSTED_DEBIT);
        ev2.setAmount(new BigDecimal("300.00"));
        ev2.setRunningBalance(new BigDecimal("700.00"));
        ev2.setStatus("POSTED");
        entityManager.persist(ev2);

        StatementEvent ev3 = new StatementEvent();
        ev3.setAccount(accountA);
        ev3.setSequenceNumber(3L);
        ev3.setInitiatingUserId(userAlice.getId());
        ev3.setTransactionId("tx-stmt-3");
        ev3.setEventType(StatementEventType.REFUSED_WITHDRAWAL);
        ev3.setAmount(new BigDecimal("5000.00"));
        ev3.setRunningBalance(new BigDecimal("700.00"));
        ev3.setStatus("REFUSED");
        ev3.setReason("INSUFFICIENT_FUNDS");
        entityManager.persist(ev3);

        entityManager.flush();

        // 1. Sequence order
        List<StatementEvent> allEvents = statementEventRepository.findByAccountIdOrderBySequenceNumberAsc(accountA.getId());
        assertEquals(3, allEvents.size());
        assertEquals(1L, allEvents.get(0).getSequenceNumber());
        assertEquals(2L, allEvents.get(1).getSequenceNumber());
        assertEquals(3L, allEvents.get(2).getSequenceNumber());
        assertEquals(3L, statementEventRepository.countByAccountId(accountA.getId()));

        // 2. Range query
        List<StatementEvent> delta = statementEventRepository.findByAccountIdAndSequenceNumberBetweenOrderBySequenceNumberAsc(
                accountA.getId(), 2L, 3L);
        assertEquals(2, delta.size());
        assertEquals(2L, delta.get(0).getSequenceNumber());
        assertEquals(3L, delta.get(1).getSequenceNumber());

        // 3. Find latest sequence
        Optional<StatementEvent> latest = statementEventRepository.findTopByAccountIdOrderBySequenceNumberDesc(accountA.getId());
        assertTrue(latest.isPresent());
        assertEquals(3L, latest.get().getSequenceNumber());
        assertEquals("REFUSED", latest.get().getStatus());

        // 4. Find by account and transaction
        Optional<StatementEvent> byTx = statementEventRepository.findByAccountIdAndTransactionId(accountA.getId(), "tx-stmt-2");
        assertTrue(byTx.isPresent());
        assertEquals(StatementEventType.POSTED_DEBIT, byTx.get().getEventType());
    }

    // =========================================================================
    // 4. AuditRecordRepository Tests
    // =========================================================================
    @Test
    @DisplayName("AuditRecordRepository records and retrieves audit logs")
    void testAuditRecordRepositoryQueries() {
        AuditRecord record = new AuditRecord();
        record.setUser(userAlice);
        record.setAction("FINANCIAL_TRANSFER");
        record.setResourceType("TRANSACTION");
        record.setResourceId("tx-audit-999");
        record.setIpAddress("127.0.0.1");
        record.setUserAgent("TestClient/1.0");
        record.setStatus(AuditStatus.SUCCESS);
        record.setMetadata("{\"amount\":\"500.00\"}");
        entityManager.persist(record);
        entityManager.flush();

        List<AuditRecord> userRecords = auditRecordRepository.findByUserIdOrderByCreatedAtDesc(userAlice.getId());
        assertEquals(1, userRecords.size());
        assertEquals("FINANCIAL_TRANSFER", userRecords.get(0).getAction());

        List<AuditRecord> resourceRecords = auditRecordRepository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
                "TRANSACTION", "tx-audit-999");
        assertEquals(1, resourceRecords.size());
        assertEquals(AuditStatus.SUCCESS, resourceRecords.get(0).getStatus());
    }
}
