package com.ledgerline.repository;

import com.ledgerline.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Query("SELECT DISTINCT ap.account FROM AccountPermission ap WHERE ap.user.id = :userId")
    List<Account> findAccountsByUserId(@Param("userId") Long userId);
}
