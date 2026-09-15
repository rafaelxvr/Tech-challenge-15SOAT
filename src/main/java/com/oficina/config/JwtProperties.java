package com.oficina.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(
        String secret,
        long expiration,
        long refreshExpiration,
        StaffTrust staff,
        CustomerTrust customer
) {
    public record StaffTrust(String issuer, String audience, String keyId) {}
    /** Pinned X.509 SubjectPublicKeyInfo PEM public keys, indexed by trusted kid. */
    public record CustomerTrust(String issuer, String audience, java.util.Map<String, String> publicKeys) {}
}
