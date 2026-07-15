# C4 — Contexto do sistema

```mermaid
C4Context
  title Contexto do Sistema - Oficina Mecânica

  Person(equipe, "Equipe da Oficina", "Gerencia clientes, veículos, peças, serviços e ordens de serviço")
  Person(cliente, "Cliente", "Acompanha a OS e aprova ou recusa o orçamento")
  Person(avaliador, "Avaliador / Desenvolvedor", "Executa, inspeciona e valida a solução")

  System(oficina, "Sistema da Oficina Mecânica", "API para gestão do ciclo de vida das ordens de serviço")

  System_Ext(email, "Ferramenta de e-mail / Webhook", "Envia notificações externas de aprovação e atualização de status")
  System_Ext(github, "GitHub", "Hospeda o repositório, executa CI/CD e armazena imagens no GHCR")

  Rel(equipe, oficina, "Opera a oficina e atualiza ordens", "JSON/HTTPS")
  Rel(cliente, oficina, "Consulta status e decide o orçamento", "JSON/HTTPS")
  Rel(avaliador, oficina, "Testa APIs e verifica saúde", "Swagger/HTTPS")
  Rel(email, oficina, "Notifica decisões e mudanças de status", "JSON/HTTPS")
  Rel(github, oficina, "Constrói e implanta", "GitHub Actions/Kubernetes")
```

## Leitura

O sistema centraliza os fluxos operacionais da oficina e expõe contratos REST para operadores, clientes e integrações externas. O GitHub participa do ciclo de entrega, mas não do domínio de negócio.
