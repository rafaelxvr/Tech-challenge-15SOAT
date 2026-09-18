package com.oficina.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Runtime-generated test keys only; claim names/values come from the immutable B2 fixtures. */
public final class TokenFixtures {
    public static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final String CUSTOMER_KID = "customer-test-key";
    public static final String STAFF_KID = "staff-test-key";
    public static final String STAFF_EMAIL = "staff.fixture@example.invalid";
    public static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    public static final UUID STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000000302");
    private final KeyPair rsa = Jwts.SIG.RS256.keyPair().build();
    private final String secret = Base64.getEncoder().encodeToString(Jwts.SIG.HS512.key().build().getEncoded());
    private final SecretKey hmac = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtProperties properties() { return properties("staging"); }

    public JwtProperties properties(String environment) {
        var customer = claims("customerAccess", environment);
        var staff = claims("staffAccess", environment);
        return new JwtProperties(secret, 60000, 120000,
                new JwtProperties.StaffTrust((String) staff.get("iss"), (String) staff.get("aud"), STAFF_KID),
                new JwtProperties.CustomerTrust((String) customer.get("iss"), (String) customer.get("aud"),
                        Map.of(CUSTOMER_KID, "-----BEGIN PUBLIC KEY-----\n"
                                + Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded())
                                + "\n-----END PUBLIC KEY-----")));
    }

    public String customer(UUID id, long version, String environment, Instant expiresAt) {
        var claims = claims("customerAccess", environment);
        claims.put("sub", id.toString());
        claims.put("identity_version", version);
        claims.put("exp", Date.from(expiresAt));
        return signCustomer(claims, Map.of("kid", CUSTOMER_KID));
    }

    public String staff(String tokenUse, String environment) {
        var claims = claims("staffAccess", environment);
        claims.put("token_use", tokenUse);
        return signStaff(claims, Map.of("kid", STAFF_KID));
    }

    public Map<String, Object> claims(String kind, String environment) {
        try {
            var fixture = mapper.readTree(Path.of("contracts/phase3-v1/token-claims.json").toFile())
                    .path("environments").path(environment).path(kind);
            Map<String, Object> claims = mapper.convertValue(fixture, new TypeReference<>() {});
            claims.put("iat", Date.from(NOW.minusSeconds(1)));
            claims.put("exp", Date.from(NOW.plusSeconds(900)));
            return new HashMap<>(claims);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load immutable token contract", exception);
        }
    }

    public String signCustomer(Map<String, Object> claims, Map<String, Object> headers) {
        return sign(claims, headers, true);
    }

    public String signStaff(Map<String, Object> claims, Map<String, Object> headers) {
        return sign(claims, headers, false);
    }

    // Raw JOSE signing lets negative fixtures contain headers rejected by JJWT's issuer-side builder.
    private String sign(Map<String, Object> claims, Map<String, Object> headers, boolean customer) {
        try {
            var header = new HashMap<>(headers);
            header.put("alg", customer ? "RS256" : "HS256");
            var payload = new HashMap<>(claims);
            payload.replaceAll((name, value) -> value instanceof Date date ? date.toInstant().getEpochSecond() : value);
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String unsigned = encoder.encodeToString(mapper.writeValueAsBytes(header)) + "."
                    + encoder.encodeToString(mapper.writeValueAsBytes(payload));
            byte[] bytes = unsigned.getBytes(StandardCharsets.US_ASCII);
            byte[] signature;
            if (customer) {
                var signer = java.security.Signature.getInstance("SHA256withRSA");
                signer.initSign(rsa.getPrivate()); signer.update(bytes); signature = signer.sign();
            } else {
                var signer = javax.crypto.Mac.getInstance("HmacSHA256");
                signer.init(new SecretKeySpec(hmac.getEncoded(), "HmacSHA256")); signature = signer.doFinal(bytes);
            }
            return unsigned + "." + encoder.encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign test fixture", exception);
        }
    }

    public String wrongAlgorithm() {
        SecretKey publicKeyAsHmac = new SecretKeySpec(rsa.getPublic().getEncoded(), "HmacSHA256");
        return Jwts.builder().header().keyId(CUSTOMER_KID).and()
                .claims(claims("customerAccess", "staging"))
                .signWith(publicKeyAsHmac, Jwts.SIG.HS256).compact();
    }

    public String staffWrongAlgorithm() {
        return Jwts.builder().header().keyId(STAFF_KID).and().claims(claims("staffAccess", "staging"))
                .signWith(hmac, Jwts.SIG.HS512).compact();
    }

    public io.jsonwebtoken.Jws<io.jsonwebtoken.Claims> readStaff(String token) {
        return Jwts.parser().clock(() -> Date.from(NOW)).verifyWith(hmac).build().parseSignedClaims(token);
    }
}
