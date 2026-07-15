# C4 — Componentes da API

```mermaid
C4Component
  title Componentes - API da Oficina Mecânica

  Person(usuario, "Operador / Cliente", "Consome os contratos REST")
  ContainerDb(database, "PostgreSQL", "PostgreSQL 16", "Dados da oficina")
  Container_Ext(smtp, "SMTP local", "MailHog", "Notificações do ambiente local")

  Container_Boundary(api, "API Oficina - Spring Boot") {
    Component(security, "Segurança", "Spring Security + JWT", "Autentica e autoriza requisições")
    Component(rest, "Adaptadores REST", "Controllers + DTOs", "Expõem os contratos HTTP")
    Component(application, "Serviços de aplicação", "Spring Services", "Orquestram os casos de uso")
    Component(domain, "Modelo de domínio", "Entities + Validation", "Mantém estados e regras da oficina")
    Component(persistence, "Persistência", "Spring Data JPA", "Implementa acesso direto ao PostgreSQL")
    Component(notificationPort, "Porta de notificação", "NotificacaoPort", "Define o contrato de saída de mensagens")
    Component(emailAdapter, "Adaptador de e-mail", "Spring Mail", "Implementa a saída SMTP")
  }

  Rel(usuario, security, "Envia requisições", "JSON/HTTPS")
  Rel(security, rest, "Autoriza acesso")
  Rel(rest, application, "Aciona casos de uso")
  Rel(application, domain, "Aplica regras e transições")
  Rel(application, persistence, "Consulta e persiste entidades", "Spring Data")
  Rel(persistence, database, "Lê e grava", "JPA/JDBC")
  Rel(application, notificationPort, "Solicita notificações")
  Rel(notificationPort, emailAdapter, "É implementada por")
  Rel(emailAdapter, smtp, "Envia mensagens", "SMTP")
```

## Leitura

A organização combina arquitetura em camadas com Ports & Adapters na integração de notificação. A persistência continua acoplada às interfaces Spring Data e não é apresentada como uma porta hexagonal completa.
