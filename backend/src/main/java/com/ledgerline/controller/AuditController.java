package com.ledgerline.controller;

import com.ledgerline.entity.AuditRecord;
import com.ledgerline.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final UserRepository userRepository;

    public AuditController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/records")
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMINISTRATOR')")
    public ResponseEntity<Map<String, Object>> getAuditRecords() {
        return ResponseEntity.ok(Map.of(
                "status", "ACTIVE",
                "auditorAccess", true,
                "readOnly", true,
                "timestamp", Instant.now().toString(),
                "recordsCount", 0
        ));
    }

    @PostMapping("/records")
    public ResponseEntity<Void> writeAuditRecord() {
        // Explicitly denied for auditors in security config
        return ResponseEntity.status(403).build();
    }
}
