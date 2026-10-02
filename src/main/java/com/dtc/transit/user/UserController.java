package com.dtc.transit.user;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration, restricted to ADMIN.
 *
 * <p>Request and response bodies are separate records on purpose. Reusing the entity would expose the
 * password hash and let a caller set fields such as {@code tokenVersion} by including them in a body
 * (edge case EC-API-16). The list endpoint arrives in Phase 3 with the pagination framework.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public com.dtc.transit.common.paging.PageResponse<UserResponse> list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long depotId,
            @RequestParam(required = false) String q,
            @PageableDefault(sort = "username") Pageable pageable) {
        return com.dtc.transit.common.paging.PageResponse.of(
                userService.search(enabled, depotId, q, pageable), UserResponse::from);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = userService.create(
                request.username(), request.password(), request.roles(), request.depotId());
        return ResponseEntity.created(URI.create("/api/v1/users/" + user.getId()))
                .body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return UserResponse.from(userService.get(id));
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        AppUser user = userService.update(
                id, request.roles(), request.depotId(), request.enabled(), Boolean.TRUE.equals(request.clearDepot()));
        return UserResponse.from(user);
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotEmpty Set<Role> roles,
            Long depotId) {}

    /**
     * Every field is optional, so a caller can change one thing without restating the rest.
     *
     * @param clearDepot set to true to make the user an HQ user; a null {@code depotId} alone means
     *     "leave it alone", which is why removing a depot needs its own flag
     */
    public record UpdateUserRequest(Set<Role> roles, Long depotId, Boolean enabled, Boolean clearDepot) {}

    /** Response view. Carries no password material. */
    public record UserResponse(
            Long id,
            String username,
            boolean enabled,
            Long depotId,
            Set<Role> roles,
            int tokenVersion,
            Instant lockedUntil) {

        static UserResponse from(AppUser user) {
            return new UserResponse(
                    user.getId(),
                    user.getUsername(),
                    user.isEnabled(),
                    user.getDepotId(),
                    user.getRoles(),
                    user.getTokenVersion(),
                    user.getLockedUntil());
        }
    }
}
