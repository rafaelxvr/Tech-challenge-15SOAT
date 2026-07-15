# C4 — Contêineres

```mermaid
C4Container
  title Contêineres - Sistema da Oficina Mecânica

  Person(equipe, "Equipe da Oficina", "Opera clientes, veículos, catálogo e ordens")
  Person(cliente, "Cliente", "Acompanha a OS e decide o orçamento")
  System_Ext(emailTool, "Ferramenta de e-mail / Webhook", "Integração externa por token")

  System_Boundary(oficina, "Sistema da Oficina Mecânica") {
    Container(api, "API Oficina", "Java 17 / Spring Boot 3.2.5", "Executa regras de negócio, autenticação e contratos REST")
    ContainerDb(database, "Banco da Oficina", "PostgreSQL 16", "Persiste dados operacionais e histórico das ordens")
    Container(mailhog, "Servidor SMTP local", "MailHog", "Recebe e apresenta notificações no ambiente local")
  }

  Rel(equipe, api, "Gerencia recursos e o ciclo da OS", "JSON/HTTPS + JWT")
  Rel(cliente, api, "Consulta status e decide orçamento", "JSON/HTTPS")
  Rel(emailTool, api, "Atualiza status mediante token", "JSON/HTTPS")
  Rel(api, database, "Lê e grava dados", "JPA/JDBC")
  Rel(api, mailhog, "Envia notificações", "SMTP")
```

## Leitura

A solução é implantada como uma API stateless, um banco PostgreSQL e um SMTP local. Swagger integra a própria API e, por isso, não é modelado como contêiner independente.
