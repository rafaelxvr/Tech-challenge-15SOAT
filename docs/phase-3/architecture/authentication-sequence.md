# CPF authentication

```mermaid
sequenceDiagram
participant C as Customer
participant T as Staff identity provider
participant G as Gateway
participant F as Challenge Lambda
participant D as DynamoDB
participant S as SES
participant A as APP
alt Customer CPF flow
    C->>G: request CPF challenge
    G->>F: validated request
    F->>D: create hashed one-time challenge
    F->>S: send code
    C->>G: verify code
    G->>F: consume challenge
    F->>D: conditional one-time consume
    F-->>G: customer JWT or safe failure
    G-->>C: protected API token response
else Staff flow
    C->>T: authenticate as staff
    T-->>C: signed staff token
    C->>G: protected request with staff token
    G->>A: forward after issuer/audience/key-id trust check
    A-->>G: authorized response
    G-->>C: response
end
```

Expired, replayed, or invalid codes fail without token issuance. Customer OTP values and credentials never appear in source exports. Staff requests use a configured issuer/audience/key-id trust path and do not consume customer OTP state. The public shapes are in the [API snapshot](../api/contracts.md); Terraform owns the deployed gateway and Lambda binding.
