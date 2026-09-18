package com.oficina.security;

import com.oficina.config.JwtProperties;
import com.oficina.entity.Cliente;
import com.oficina.repository.ClienteRepository;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class CustomerTokenValidator implements ValidadorToken {
    private static final Set<String> SCOPES = Set.of("orders:read:self", "orders:decide:self");
    private final JwtProperties.CustomerTrust trust;
    private final Map<String, RSAPublicKey> keys;
    private final ClienteRepository clientes;
    private final Clock clock;

    public CustomerTokenValidator(JwtProperties properties, ClienteRepository clientes, Clock clock) {
        this.trust = properties.customer();
        this.clientes = clientes;
        this.clock = clock;
        try {
            if (trust == null || trust.issuer() == null || trust.issuer().isBlank()
                    || trust.audience() == null || trust.audience().isBlank()
                    || trust.publicKeys() == null || trust.publicKeys().isEmpty()) throw new IllegalArgumentException();
            Map<String, RSAPublicKey> configured = new HashMap<>();
            for (var entry : trust.publicKeys().entrySet()) {
                if (entry.getKey().isBlank()) throw new IllegalArgumentException();
                String encoded = entry.getValue().replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
                configured.put(entry.getKey(), key);
            }
            keys = Map.copyOf(configured);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Customer JWT trust must contain issuer, audience and pinned RSA public keys");
        }
    }

    @Override public IdentidadeAutenticada validar(String token) {
        var claims = TokenVerification.verify(token, "RS256", keys, trust.issuer(), trust.audience(),
                "customer", "access", clock);
        UUID id;
        long version;
        Set<String> permissions = new HashSet<>();
        try {
            id = UUID.fromString(claims.getSubject());
            if (!id.toString().equals(claims.getSubject())) throw TokenVerification.invalid();
            Object rawVersion = claims.get("identity_version");
            if (!(rawVersion instanceof Integer || rawVersion instanceof Long)) throw TokenVerification.invalid();
            version = ((Number) rawVersion).longValue();
            if (version < 1 || claims.containsKey("roles")) throw TokenVerification.invalid();
            if (!(claims.get("scopes") instanceof List<?> scopes) || scopes.isEmpty()) throw TokenVerification.invalid();
            for (Object scope : scopes) {
                if (!(scope instanceof String value) || !SCOPES.contains(value)) throw TokenVerification.invalid();
                permissions.add("SCOPE_" + value);
            }
        } catch (RuntimeException exception) {
            throw TokenVerification.invalid();
        }
        Cliente cliente = clientes.findById(id).orElseThrow(TokenVerification::invalid);
        if (!cliente.isAtivo() || cliente.getVersaoIdentidade() != version) throw TokenVerification.invalid();
        return new IdentidadeAutenticada(TipoPrincipal.CUSTOMER, id, permissions, version);
    }
}
