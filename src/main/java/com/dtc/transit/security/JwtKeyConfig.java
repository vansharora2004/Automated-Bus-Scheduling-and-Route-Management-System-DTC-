package com.dtc.transit.security;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * RSA key material for signing and verifying access tokens.
 *
 * <p>Tokens are RS256. A symmetric algorithm would require every verifier to hold the signing secret,
 * and the decoder below is pinned to RS256 so a token presented as {@code alg=none} or HS256 signed
 * with the public key is rejected rather than trusted.
 *
 * <p>When no key pair is configured, one is generated at startup. That keeps secrets out of the
 * repository, at the cost of invalidating tokens on restart, which is correct for local work and
 * unacceptable in production. A configured pair is required there, and the warning says so.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    RSAKey rsaKey(SecurityProperties properties) {
        var jwt = properties.jwt();
        if (isBlank(jwt.privateKey()) || isBlank(jwt.publicKey())) {
            log.warn(
                    "No RSA key pair configured, generating an ephemeral one. Access tokens will not "
                            + "survive a restart. Set app.security.jwt.private-key and public-key "
                            + "before deploying.");
            return generate();
        }
        return fromPem(jwt.publicKey(), jwt.privateKey());
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey, SecurityProperties properties) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                // Pinning the algorithm is what defeats algorithm-confusion attacks.
                .signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(
                properties.jwt().issuer()));
        return decoder;
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("could not generate an RSA key pair", e);
        }
    }

    private static RSAKey fromPem(String publicPem, String privatePem) {
        try {
            var factory = KeyFactory.getInstance("RSA");
            var publicKey = (RSAPublicKey)
                    factory.generatePublic(new X509EncodedKeySpec(decode(publicPem, "PUBLIC KEY")));
            var privateKey = (RSAPrivateKey)
                    factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privatePem, "PRIVATE KEY")));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("configured RSA key pair could not be read", e);
        }
    }

    private static byte[] decode(String pem, String label) {
        String body = pem.replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
