package com.finpay.gateway.auth;

import com.finpay.gateway.identity.UserRepository;
import com.finpay.gateway.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates a SUPER_ADMIN dashboard user on first boot so the system is usable out of the box. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public BootstrapRunner(UserRepository users, PasswordEncoder encoder,
                           @Value("${finpay.bootstrap.admin-email:admin@finpay.local}") String adminEmail,
                           @Value("${finpay.bootstrap.admin-password:admin12345}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByEmail(adminEmail).isPresent()) {
            return;
        }
        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPasswordHash(encoder.encode(adminPassword));
        admin.setRole("SUPER_ADMIN");
        users.save(admin);
        log.warn("Bootstrapped SUPER_ADMIN user '{}' (change the password for any non-local use)", adminEmail);
    }
}
