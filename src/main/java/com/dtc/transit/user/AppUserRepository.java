package com.dtc.transit.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Usernames are matched case-insensitively, matching the unique index on lower(username). */
    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from AppUser u where lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    /**
     * Reads just the token version.
     *
     * <p>Called on the hot path by the token-version filter, so it deliberately avoids loading the
     * user and its eagerly fetched roles.
     */
    @Query("select u.tokenVersion from AppUser u where u.id = :id and u.enabled = true")
    Optional<Integer> findTokenVersionOfEnabledUser(@Param("id") Long id);
}
