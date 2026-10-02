package com.dtc.transit.security;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dtc.transit.user.AppUserRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

/**
 * Rejects access tokens that no longer reflect the account they were issued for.
 *
 * <p>JWTs are self-contained, so disabling a user or changing their roles cannot by itself stop a
 * token already in circulation. Each token carries the user's token version at issue time; raising
 * that version in the database invalidates every token holding the older value, without a blacklist.
 *
 * <p>The lookup is cached briefly, which bounds the cost at one query per user per TTL. The TTL is
 * therefore the worst-case delay before a revocation bites, and it is always shorter than the access
 * token lifetime, so the change can never take longer than the token would have lived anyway.
 */
@Component
public class TokenVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionFilter.class);

    private final LoadingCache<Long, Integer> currentVersions;

    public TokenVersionFilter(AppUserRepository users, SecurityProperties properties) {
        this.currentVersions = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.jwt().tokenVersionCacheTtl().toMillis(), TimeUnit.MILLISECONDS)
                // A disabled or deleted user yields -1, which can never equal a token's version,
                // so their tokens stop working without a special case here.
                .build(userId -> users.findTokenVersionOfEnabledUser(userId).orElse(-1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Long userId = parseUserId(jwt);
            Integer tokenVersion = tokenVersionOf(jwt);

            if (userId == null || tokenVersion == null || !tokenVersion.equals(currentVersions.get(userId))) {
                log.debug("rejecting token for user {}: stale token version", userId);
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token is no longer valid");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /** Clears a user's cached version so a revocation takes effect immediately in-process. */
    public void evict(Long userId) {
        currentVersions.invalidate(userId);
    }

    /**
     * Reads the token-version claim.
     *
     * <p>Typed as {@link Number} rather than {@code Integer} on purpose: a JSON number arrives from the
     * JWT parser as a {@code Long}, so casting straight to {@code Integer} throws
     * {@link ClassCastException} on every authenticated request.
     */
    private static Integer tokenVersionOf(Jwt jwt) {
        Object claim = jwt.getClaim(TokenService.CLAIM_TOKEN_VERSION);
        return claim instanceof Number number ? number.intValue() : null;
    }

    private static Long parseUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
