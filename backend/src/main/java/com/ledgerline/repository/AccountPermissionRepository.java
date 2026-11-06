package com.ledgerline.repository;

import com.ledgerline.entity.AccountPermission;
import com.ledgerline.entity.PermissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountPermissionRepository extends JpaRepository<AccountPermission, Long> {

    List<AccountPermission> findByUserIdAndAccountId(Long userId, Long accountId);

    boolean existsByUserIdAndAccountIdAndPermission(Long userId, Long accountId, PermissionType permission);

    List<AccountPermission> findByUserId(Long userId);
}
