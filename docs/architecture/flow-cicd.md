# Fluxo de CI/CD

```mermaid
flowchart LR
  Push[Push ou Pull Request] --> Checkout[Checkout]
  Checkout --> Java[Setup Java 17]
  Java --> Verify[mvn verify]
  Verify --> DockerBuild[Build da imagem Docker]
  DockerBuild --> PushGHCR{Push na branch principal?}
  PushGHCR -- Não --> EndPR[Finaliza validação da PR]
  PushGHCR -- Sim --> GHCR[Push no GHCR]
  GHCR --> Kind[Cria cluster Kind efêmero]
  Kind --> Database[Aplica namespace, config, secret e PostgreSQL]
  Database --> App[Aplica MailHog, API, Service e HPA]
  App --> Rollout[Verifica rollouts]
  Rollout --> Smoke[Smoke test /actuator/health]
```

## Leitura

O pipeline separa CI, construção de imagem e deploy. Pull requests executam build e testes; pushes na branch principal publicam a imagem e validam o deployment em um cluster Kind efêmero.
