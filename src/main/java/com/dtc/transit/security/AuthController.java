package com.dtc.transit.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints. Login and refresh are public; logout needs a valid access token. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        var pair = authService.login(request.username(), request.password(), clientAddress(http));
        return TokenResponse.from(pair);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logout(Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    /**
     * The address used for rate limiting.
     *
     * <p>{@code X-Forwarded-For} is deliberately ignored. It is caller-supplied, so trusting it would
     * let an attacker defeat the per-address limit by varying the header. A reverse proxy in front of
     * this service must be configured to set the remote address itself.
     */
    private static String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    /**
     * @param tokenType always {@code Bearer}, so clients need not infer it
     */
    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType) {

        static TokenResponse from(AuthService.TokenPair pair) {
            return new TokenResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn(), "Bearer");
        }
    }
}
