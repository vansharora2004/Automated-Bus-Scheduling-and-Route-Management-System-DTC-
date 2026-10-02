package com.dtc.transit.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes every live token for a user, used on logout and on detecting a replay. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);

    /**
     * Revokes every live token for a user in a transaction of its own.
     *
     * <p>Used when a replayed token is detected. That path throws immediately afterwards, so a
     * revocation joined to the caller's transaction would be rolled back by the very exception it
     * accompanies, leaving the leaked token family fully usable.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllForUserNow(@Param("userId") Long userId);

    @Query("select count(t) from RefreshToken t where t.userId = :userId and t.revoked = false")
    long countLiveForUser(@Param("userId") Long userId);
}
