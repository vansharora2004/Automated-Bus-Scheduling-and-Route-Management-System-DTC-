package com.dtc.transit.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The API's filter chain: stateless, bearer-token authenticated, and denying by default.
 *
 * <p>Authorization is enforced at three levels, because any single one can be defeated by a future
 * mistake. This class is the coarse URL level. Method-level {@code @PreAuthorize} on services is the
 * second, and it also covers entry points that never pass through a controller. Depot scoping applied
 * to queries is the third.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(
            HttpSecurity http, TokenVersionFilter tokenVersionFilter, SecurityProperties properties)
            throws Exception {
        return http
                // No cookies or sessions are used, so there is no CSRF surface to protect. Disabling
                // it here is safe only because authentication is a bearer token the browser does not
                // attach automatically.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh")
                        .permitAll()
                        // Liveness and readiness must answer before a token can be obtained.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // User administration is ADMIN-only at the URL level as well as on the
                        // service, so a missing annotation cannot silently open it up.
                        .requestMatchers("/api/v1/users/**")
                        .hasRole("ADMIN")
                        // Bulk import is guarded here rather than on the controller. Multipart parts are
                        // resolved before method security would run, so an unauthorised caller would
                        // otherwise get a parsing error instead of a refusal.
                        .requestMatchers(HttpMethod.POST, "/api/v1/buses/import", "/api/v1/crew/import")
                        .hasAnyRole("ADMIN", "MANAGER")
                        // Rebuilding the grid discards every cell, and refreshing the coverage view is
                        // an expensive maintenance action. Both are administrator-only at the URL level.
                        .requestMatchers(HttpMethod.POST, "/api/v1/coverage/grid", "/api/v1/coverage/refresh")
                        .hasRole("ADMIN")
                        // Metrics and API docs are not public. Phase 11 moves them to an internal
                        // management port.
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // Runs after the bearer token is parsed, so it can compare the token's version
                // against the account's current one.
                .addFilterAfter(tokenVersionFilter, BearerTokenAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.CLAIM_ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Password hashing.
     *
     * <p>{@code DelegatingPasswordEncoder} stores the algorithm as a prefix on the hash, so the
     * default can be changed later and existing hashes keep verifying.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        var configuration = new CorsConfiguration();
        // An explicit allow-list, never a wildcard. A wildcard with credentials is rejected by
        // browsers anyway, and silently permitting any origin would be worse than failing.
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("ETag", "Location", "X-Correlation-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
