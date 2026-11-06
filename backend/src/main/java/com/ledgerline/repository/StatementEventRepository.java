package com.ledgerline.repository;

import com.ledgerline.entity.StatementEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for customer statement event timelines.
 * The application user has strictly SELECT permissions on statement_events.
 * Inserting statement events is restricted exclusively to stored procedure sp_record_statement_event.
 */
@Repository
public interface StatementEventRepository extends JpaRepository<StatementEvent, Long> {

    List<StatementEvent> findByAccountIdOrderBySequenceNumberAsc(Long accountId);

    Page<StatementEvent> findByAccountIdOrderBySequenceNumberAsc(Long accountId, Pageable pageable);

    List<StatementEvent> findByAccountIdAndSequenceNumberBetweenOrderBySequenceNumberAsc(
            Long accountId, Long fromSequence, Long toSequence);

    Optional<StatementEvent> findByAccountIdAndTransactionId(Long accountId, String transactionId);

    Optional<StatementEvent> findTopByAccountIdOrderBySequenceNumberDesc(Long accountId);

    long countByAccountId(Long accountId);
}
