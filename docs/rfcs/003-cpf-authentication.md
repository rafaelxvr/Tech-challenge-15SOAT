# RFC 003 — CPF authentication

## Context

Customers must access their own orders through CPF verification without turning passwords or OTP values into repository data.

## Alternatives

1. Give customers a permanent password.
2. Trust a client-supplied CPF on each request.
3. Send a one-time CPF challenge, retain only safe challenge state, and issue a signed customer token after conditional consumption.

## Outcome

Choose option 3. Expired, invalid, and replayed challenges fail without a token. Staff tokens follow separate issuer/audience/key-id trust and never share customer OTP state.

## Implementation links

[authentication sequence](../phase-3/architecture/authentication-sequence.md), [API snapshot](../phase-3/api/contracts.md), and [function implementation handoff](../../../oficina-functions/docs/architecture.md).
