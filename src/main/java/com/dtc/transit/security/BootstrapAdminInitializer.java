package com.dtc.transit.security;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.user.AppUser;
import com.dtc.transit.user.AppUserRepository;
import com.dtc.transit.user.Role;

/**
 * Creates the first administrator so a fresh database can be logged into.
 *
 * <p>Restricted to the local profile and driven entirely by configuration. A seeded account with a
 * known password is a backdoor in any shared environment, so there is no default password: if none is
 * supplied, nothing is created and the reason is logged. Production bootstraps an administrator
 * through a migration or an operator runbook instead.
 */
@Configuration
@Profile("local")
public class BootstrapAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    @Bean
    ApplicationRunner bootstrapAdmin(
            AppUserRepository users, PasswordEncoder passwordEncoder, SecurityProperties properties) {
        return args -> seed(users, passwordEncoder, properties);
    }

    @Transactional
    void seed(AppUserRepository users, PasswordEncoder passwordEncoder, SecurityProperties properties) {
        var bootstrap = properties.bootstrap();

        if (bootstrap.password() == null || bootstrap.password().isBlank()) {
            log.info("No bootstrap admin password configured, skipping admin creation. "
                    + "Set APP_SECURITY_BOOTSTRAP_PASSWORD to create one.");
            return;
        }
        if (users.count() > 0) {
            log.debug("Users already exist, skipping bootstrap admin creation.");
            return;
        }

        var admin = new AppUser(
                bootstrap.username(), passwordEncoder.encode(bootstrap.password()), Set.of(Role.ADMIN), null);
        users.save(admin);
        log.info("Created bootstrap admin '{}' (HQ scope, no depot).", bootstrap.username());
    }
}
