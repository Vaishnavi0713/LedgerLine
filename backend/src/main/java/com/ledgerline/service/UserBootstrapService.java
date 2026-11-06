package com.ledgerline.service;

import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import com.ledgerline.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ledgerline.demo.admin-username:admin@ledgerline.test}")
    private String adminUsername;

    @Value("${ledgerline.demo.admin-password:}")
    private String adminPassword;

    @Value("${ledgerline.demo.auditor-username:auditor@ledgerline.test}")
    private String auditorUsername;

    @Value("${ledgerline.demo.auditor-password:}")
    private String auditorPassword;

    @Value("${ledgerline.demo.holder-username:holder@ledgerline.test}")
    private String holderUsername;

    @Value("${ledgerline.demo.holder-password:}")
    private String holderPassword;

    public UserBootstrapService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        provisionUserIfConfigured("admin", adminUsername, adminPassword, UserRole.ADMINISTRATOR);
        provisionUserIfConfigured("auditor", auditorUsername, auditorPassword, UserRole.AUDITOR);
        provisionUserIfConfigured("holder", holderUsername, holderPassword, UserRole.ACCOUNT_HOLDER);
    }

    private void provisionUserIfConfigured(String usernamePrefix, String email, String password, UserRole role) {
        if (password == null || password.trim().isEmpty()) {
            log.info("Skipping bootstrap of role {} (no credentials provided in environment)", role);
            return;
        }

        if (userRepository.findByEmail(email).isEmpty()) {
            User user = new User(
                    usernamePrefix + "_user",
                    email,
                    passwordEncoder.encode(password),
                    role
            );
            userRepository.save(user);
            log.info("Successfully provisioned initial {} user: {}", role, email);
        }
    }
}
