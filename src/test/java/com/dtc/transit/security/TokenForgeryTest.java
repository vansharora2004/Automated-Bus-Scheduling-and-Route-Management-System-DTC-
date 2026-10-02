package com.dtc.transit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Forged and malformed tokens must all be rejected.
 *
 * <p>These are the attacks that a JWT implementation gets wrong: an unsigned token claiming
 * {@code alg=none}, and a token signed with HMAC using the RSA public key as the shared secret. Both
 * succeed against a verifier that trusts the header's algorithm instead of pinning its own.
 */
class TokenForgeryTest extends SecurityWebTest {

    @Autowired
    private RSAKey rsaKey;

    @Autowired
    private SecurityProperties properties;

    @Test
    @DisplayName("a correctly signed token is accepted, proving the negative cases mean something")
    void genuineTokenIsAccepted() {
        support.createUser("legit-admin", null, Role.ADMIN);
        String token = support.accessTokenFor(rest, "legit-admin");

        assertThat(call(token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unsigned alg=none token is rejected")
    void algNoneRejected() throws Exception {
        var user = support.createUser("victim-1", null, Role.ADMIN);
        var plain = new PlainJWT(claims(user.getId(), 0));

        assertThat(call(plain.serialize()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an HS256 token signed with the RSA public key is rejected")
    void hmacConfusionRejected() throws Exception {
        var user = support.createUser("victim-2", null, Role.ADMIN);

        // The classic algorithm-confusion attack: the public key is not secret, so if the verifier
        // honours the header's alg it will happily validate this with a key the attacker also has.
        byte[] secret = rsaKey.toRSAPublicKey().getEncoded();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims(user.getId(), 0));
        signed.sign(new MACSigner(java.util.Arrays.copyOf(secret, Math.max(32, secret.length))));

        assertThat(call(signed.serialize()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenRejected() throws Exception {
        var user = support.createUser("victim-3", null, Role.ADMIN);
        var past = Instant.now().minusSeconds(7200);
        var claims = new JWTClaimsSet.Builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(String.valueOf(user.getId()))
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(60)))
                .claim(TokenService.CLAIM_ROLES, List.of("ADMIN"))
                .claim(TokenService.CLAIM_TOKEN_VERSION, 0)
                .build();

        assertThat(call(signWithRealKey(claims)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void wrongIssuerRejected() throws Exception {
        var user = support.createUser("victim-4", null, Role.ADMIN);
        var claims = new JWTClaimsSet.Builder()
                .issuer("https://attacker.example")
                .audience(List.of(properties.jwt().audience()))
                .subject(String.valueOf(user.getId()))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim(TokenService.CLAIM_ROLES, List.of("ADMIN"))
                .claim(TokenService.CLAIM_TOKEN_VERSION, 0)
                .build();

        assertThat(call(signWithRealKey(claims)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token whose roles claim was edited does not gain those roles")
    void tamperedRolesRejected() {
        support.createUser("low-privilege", 1L, Role.SCHEDULER);
        String token = support.accessTokenFor(rest, "low-privilege");

        // Rewrite the payload without re-signing: the signature no longer matches.
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"sub\":\"1\",\"roles\":[\"ADMIN\"],\"tv\":0}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(call(forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a garbage bearer value is rejected")
    void malformedTokenRejected() {
        assertThat(call("this.is.not.a.jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JWTClaimsSet claims(Long userId, int tokenVersion) {
        return new JWTClaimsSet.Builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(String.valueOf(userId))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim(TokenService.CLAIM_ROLES, List.of("ADMIN"))
                .claim(TokenService.CLAIM_TOKEN_VERSION, tokenVersion)
                .build();
    }

    private String signWithRealKey(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return signed.serialize();
    }

    /** Calls an ADMIN-only endpoint. A genuine admin token yields 404 here, never 401 or 403. */
    private org.springframework.http.ResponseEntity<String> call(String token) {
        return support.call(rest, HttpMethod.GET, "/api/v1/users/999999", token, null);
    }
}
