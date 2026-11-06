package com.ledgerline.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "account_permissions", uniqueConstraints = {
    @UniqueConstraint(name = "uq_user_acc_perm", columnNames = {"user_id", "account_id", "permission"})
})
public class AccountPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PermissionType permission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public AccountPermission() {}

    public AccountPermission(User user, Account account, PermissionType permission) {
        this.user = user;
        this.account = account;
        this.permission = permission;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }

    public PermissionType getPermission() { return permission; }
    public void setPermission(PermissionType permission) { this.permission = permission; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
