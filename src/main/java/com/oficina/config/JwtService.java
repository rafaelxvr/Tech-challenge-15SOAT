package com.oficina.config;

import com.oficina.entity.Usuario;
import com.oficina.security.TokenVerification;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        var trust = properties.staff();
        if (trust == null || trust.issuer() == null || trust.issuer().isBlank()
                || trust.audience() == null || trust.audience().isBlank()
                || trust.keyId() == null || trust.keyId().isBlank()
                || properties.expiration() <= 0 || properties.refreshExpiration() <= 0
                || properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalArgumentException("Staff JWT trust and positive lifetimes must be configured");
        }
        key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(Map.of(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails, properties.expiration(), "access");
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(Map.of(), userDetails, properties.refreshExpiration(), "refresh");
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long lifetime, String purpose) {
        if (!(userDetails instanceof Usuario staff) || !staff.isAtivo()
                || (staff.getRole() != Usuario.Role.ADMIN && staff.getRole() != Usuario.Role.MECANICO)) {
            throw TokenVerification.invalid();
        }
        Map<String, Object> claims = new HashMap<>(extraClaims);
        // Caller extensions never override identity, purpose, trust, authority or validity boundaries.
        List.of("iss", "aud", "sub", "iat", "exp", "nbf", "jti", "principal_type", "token_use",
                "roles", "scopes", "identity_version").forEach(claims::remove);
        var now = clock.instant();
        return Jwts.builder().claims(claims)
                .header().keyId(properties.staff().keyId()).and()
                .issuer(properties.staff().issuer()).audience().add(properties.staff().audience()).and()
                .subject(staff.getUsername()).issuedAt(Date.from(now)).expiration(Date.from(now.plusMillis(lifetime)))
                .claim("principal_type", "staff").claim("token_use", purpose)
                .claim("roles", List.of(staff.getRole().name()))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public Claims validarAccessToken(String token) {
        return validate(token, "access");
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            return userDetails instanceof Usuario staff && staff.isAtivo()
                    && (staff.getRole() == Usuario.Role.ADMIN || staff.getRole() == Usuario.Role.MECANICO)
                    && validarAccessToken(token).getSubject().equals(staff.getUsername());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Extraction is access-only; refresh tokens must never enter a resource authentication path. */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(validarAccessToken(token));
    }

    private Claims validate(String token, String purpose) {
        return TokenVerification.verify(token, "HS256", Map.of(properties.staff().keyId(), key),
                properties.staff().issuer(), properties.staff().audience(), "staff", purpose, clock);
    }
}
