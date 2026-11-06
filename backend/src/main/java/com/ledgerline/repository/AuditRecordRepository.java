package com.ledgerline.repository;

import com.ledgerline.entity.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditRecordRepository extends JpaRepository<AuditRecord, Long> {

    List<AuditRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<AuditRecord> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, String resourceId);

    Page<AuditRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
