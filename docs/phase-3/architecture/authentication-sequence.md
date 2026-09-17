# Customer and staff authentication

```mermaid
sequenceDiagram
  participant C as Caller
  participant G as Environment HTTP API
  participant F as CPF challenge and verification handlers
  participant P as PostgreSQL auth view
  participant D as DynamoDB challenge state
  participant S as SES
  participant Z as REQUEST authorizer
  participant A as APP
  C->>G: CPF challenge request
  G->>F: Public challenge route
  F->>P: Resolve active identity and trusted contact
  F->>D: Store hashed challenge with bounded expiry and attempts
  F->>S: Send OTP to trusted contact
  C->>G: Challenge identifier and OTP
  G->>F: Public verification route
  F->>P: Recheck active identity and version
  F->>D: Conditional consume
  alt invalid, expired, replayed or losing concurrent consume
    F-->>C: Sanitized failure with no token
  else successful consume
    F-->>C: RS256 customer access token
  end
  C->>G: Staff login credentials
  G->>A: Public staff login route
  A-->>C: HS256 staff access and distinct refresh token
  C->>G: Protected request with access token
  G->>Z: Signed token and exact route key
  Z-->>G: Token and route decision without cache
  G->>A: Allowed request through private integration
  A->>P: Recheck current identity or roles and resource ownership
  A-->>C: Authorized result or sanitized denial
```

The final APP check uses its own repositories, not the function-only auth view; the diagram groups PostgreSQL reads for clarity. Customer RS256 public keys and staff HS256 secrets remain separate. Each path enforces issuer, audience, expiry, purpose, principal type and kid. A discriminator selects a verifier only. Refresh tokens cannot authorize business routes; unknown algorithms, key URLs and cross-environment claims fail closed.

DynamoDB TTL cleanup does not extend OTP validity. A consumed challenge is never reopened after a later signing/response failure. See [ADR 003](../../adrs/003-token-trust.md), [RFC 003](../../rfcs/003-cpf-authentication.md), [FUN rotation contract](../../../../oficina-functions/docs/token-trust.md), [APP trust tests](../../../src/test/java/com/oficina/security/TokenTrustTest.java) and [API exports](../api/contracts.md). Live gateway statuses, initialized secrets and key rotation require separate evidence.
