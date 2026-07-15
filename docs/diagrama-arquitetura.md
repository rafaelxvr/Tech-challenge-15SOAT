## 1. Visão geral da solução

```mermaid
flowchart TB
  subgraph externos [Acesso externo]
    Dev[Desenvolvedor / Avaliador]
    Swagger[Swagger UI / Postman]
    EmailHook[Notificação e-mail / webhook]
  end

  subgraph cicd [CI/CD - GitHub Actions]
    CI1[1. mvn verify - build e testes]
    CI2[2. Build imagem Docker]
    CI3[3. Push GHCR]
    CI4[4. Kind + kubectl apply]
    CI1 --> CI2 --> CI3 --> CI4
  end

  subgraph iac [IaC - Terraform /infra]
    TF[Kind cluster oficina-k8s]
    TF --> CI4
  end

  subgraph cluster [Kubernetes - namespace oficina]
    direction TB
    Svc[Service NodePort :30080]
    HPA[HPA CPU 60% / Mem 70%]
    App[Deployment oficina-app<br/>2 a 6 réplicas]
    Pg[PostgreSQL 16 + PVC]
    Mail[MailHog SMTP]
    CM[ConfigMap]
    Sec[Secret JWT / DB / token e-mail]

    HPA --> App
    Svc --> App
    App --> Pg
    App --> Mail
    CM -.-> App
    Sec -.-> App
  end

  Dev --> Swagger
  Dev --> EmailHook
  Swagger --> Svc
  EmailHook --> Svc
  CI4 --> cluster
  Dev -.->|terraform apply| TF
```

**Legenda rápida**

| Elemento | Função |
|---|---|
| Terraform + Kind | Provisiona o cluster local |
| GitHub Actions | Automatiza build, testes, imagem e deploy |
| App + HPA | Escalabilidade dinâmica sob carga |
| Postgres + Flyway | Persistência e migração de schema |
| MailHog | Visualiza e-mails de atualização de status |
| ConfigMap / Secret | Separação de config e dados sensíveis |

---

## 2. Fluxo de deploy

```mermaid
sequenceDiagram
  participant Dev as Desenvolvedor
  participant GH as GitHub Actions
  participant Reg as GHCR
  participant Kind as Cluster Kind
  participant API as oficina-app
  participant DB as PostgreSQL

  Dev->>GH: git push main
  GH->>GH: mvn verify
  GH->>Reg: docker build + push
  GH->>Kind: kind create / load image
  GH->>Kind: kubectl apply Postgres
  Kind->>DB: Pod Postgres ready
  GH->>Kind: kubectl apply App + HPA + MailHog
  Kind->>API: Pods app ready
  API->>DB: Flyway migrate
  API-->>Dev: Health OK /api/actuator/health
```

---

## 3. Arquitetura da aplicação (hexagonal)

```mermaid
flowchart LR
  subgraph adapters_in [Adaptadores de entrada]
    REST[Controllers REST / DTO]
  end

  subgraph app [Aplicação]
    UC[Services / casos de uso]
    PortOut[NotificacaoPort]
  end

  subgraph domain [Domínio]
    Ent[Entities OS, Cliente, Veículo...]
    Val[Validation / regras de transição]
  end

  subgraph adapters_out [Adaptadores de saída]
    JPA[Repositories JPA]
    Mail[EmailNotificacaoAdapter]
  end

  REST --> UC
  UC --> Ent
  UC --> Val
  UC --> PortOut
  PortOut --> Mail
  UC --> JPA
  JPA --> PG[(PostgreSQL)]
  Mail --> SMTP[MailHog / SMTP]
```

---

## 4. Fluxo de negócio — Ordem de Serviço

```mermaid
stateDiagram-v2
  [*] --> RECEBIDA: POST /ordens-servico
  RECEBIDA --> EM_DIAGNOSTICO: iniciar-diagnostico<br/>ou e-mail
  EM_DIAGNOSTICO --> AGUARDANDO_APROVACAO: enviar-orcamento
  AGUARDANDO_APROVACAO --> EM_EXECUCAO: notificacao APROVADO
  AGUARDANDO_APROVACAO --> EM_DIAGNOSTICO: notificacao RECUSADO
  EM_EXECUCAO --> FINALIZADA: finalizar / e-mail
  FINALIZADA --> ENTREGUE: entregar

  note right of RECEBIDA
    Listagem operacional exclui
    FINALIZADA e ENTREGUE
  end note
```

**Prioridade na listagem:** Em Execução → Aguardando Aprovação → Diagnóstico → Recebida (mais antigas primeiro).

---