package com.dtc.transit.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the single {@link Clock} the application reads time from.
 *
 * <p>Domain code never calls {@code Instant.now()} or {@code LocalDateTime.now()} directly. Every
 * time-dependent decision takes an injected clock, which is what makes scheduling runs reproducible
 * and lets tests pin an exact instant (edge case EC-TIME-12).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
