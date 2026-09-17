# ADR 003 — Token trust separation

## Decision

Use separate customer CPF challenge/token material and staff issuer/audience/key-id trust settings per environment.

## Consequences

Customer OTP state cannot authorize staff requests, and staff credential rotation remains independent. Documentation can show flows without publishing token, OTP, or signing-key values.

## Implementation links

[authentication sequence](../phase-3/architecture/authentication-sequence.md), [API snapshot](../phase-3/api/contracts.md), and [functions architecture](../../../oficina-functions/docs/architecture.md).
