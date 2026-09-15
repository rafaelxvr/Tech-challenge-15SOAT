# Separate customer and staff JWT trust

Configure these inputs before starting the A3 API. No deployment key is generated or committed by this implementation. Existing staff must log in again after rollout: older tokens lack the required issuer, audience, kid, principal type and token purpose.

| Input | Value |
| --- | --- |
| `JWT_SECRET` | Independently supplied staff HMAC secret with at least 32 UTF-8 bytes and sufficient random entropy. The existing raw-text interpretation is retained; it is not Base64-decoded. |
| `JWT_STAFF_ISSUER` | Exact staff issuer from the environment's `contracts/phase3-v1/token-claims.json` entry. |
| `JWT_STAFF_AUDIENCE` | Exact API audience from that entry. |
| `JWT_STAFF_KEY_ID` | Identifier for this configured HMAC key; the signer emits it and the validator requires it. |
| `JWT_CUSTOMER_ISSUER`, `JWT_CUSTOMER_AUDIENCE` | Exact customer issuer and API audience for the same environment. |
| `security.jwt.customer.public-keys` | Map of trusted customer `kid` values to RSA public keys, minimum 2048 bits, in X.509 SubjectPublicKeyInfo PEM format. |

Supply the public-key map through a mounted Spring configuration file or `SPRING_APPLICATION_JSON`. Its structure is `{"security":{"jwt":{"customer":{"public-keys":{"<trusted-kid>":"<PEM-public-key>"}}}}}`. Replace both placeholders with issuer-provided values, using JSON `\n` escapes for PEM line breaks. Docker Compose forwards this JSON and the required trust environment variables. Do not place the customer's private signing key in the API configuration. Public-key IDs preserve their exact case; a configuration file or JSON avoids environment-variable map-key normalization.

Missing trust values or an invalid/empty customer key map prevent startup. Customer validation accepts only RS256 with a configured key ID. It never fetches `jku`, `x5u`, embedded `jwk` or `x5c` material. Rotation uses a reviewed replacement map: overlap only the intended trusted public keys, then remove the old ID. Staff validates only HS256 and the current configured ID; changing that secret/ID requires staff re-login.

Both validators require signed issuer, exact audience, subject, issued-at, expiry, principal type and access purpose. Customer identity versions must match the active database record on each authenticated request. The current staff database role governs `ROLE_ADMIN` or `ROLE_MECANICO`; the legacy `CLIENTE` user role cannot obtain or use staff tokens. Customer scopes become only `SCOPE_orders:read:self` and/or `SCOPE_orders:decide:self`. Refresh tokens remain separate in the unchanged login response and cannot authenticate resources.

A4 owns the subsequent route/ownership rollout and replacement of legacy public order routes. Do not expose the customer flow as complete until that dependency and its route matrix tests are delivered. A3 prevents the authenticated catch-all from granting customers staff-route access; it does not claim that A4's ownership checks already exist.
