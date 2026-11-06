package com.ledgerline.repository;

import com.ledgerline.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for immutable double-entry ledger entries.
 * The application user has strictly SELECT permissions on ledger_entries.
 * Inserting ledger pairs is restricted exclusively to stored procedure sp_post_ledger_pair.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(String transactionId);

    List<LedgerEntry> findByAccountIdOrderBySequenceNumberAsc(Long accountId);

    Optional<LedgerEntry> findByAccountIdAndSequenceNumber(Long accountId, Long sequenceNumber);

    long countByTransactionId(String transactionId);
}
