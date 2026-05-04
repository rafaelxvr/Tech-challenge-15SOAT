# Oficina Mecânica — Sistema Integrado de Atendimento

Back-end MVP para gestão de ordens de serviço, clientes, veículos, catálogo de peças e serviços e métricas administrativas.

---

## Stack tecnológica

| Tecnologia | Versão | Uso |
|---|---|---|
| Java | 17 | Runtime |
| Spring Boot | 3.2.5 | Framework |
| PostgreSQL | 16 | Banco (Docker / local) |
| Flyway | (via Spring Boot) | Migrações do schema |
| Spring Security + JWT | jjwt 0.12.x | API stateless |
| MapStruct | 1.5.5 | Mapeamento DTO |
| Lombok | 1.18.x | Redução de boilerplate |
| SpringDoc OpenAPI | 2.5 | Swagger |
| Testcontainers | 1.19.x | Testes com Postgres real |
| JaCoCo | 0.8.11 | Cobertura mínima configurada |

---

## Pré-requisitos

- **Java 17+** — [Eclipse Temurin (Adoptium)](https://adoptium.net/)
- **Maven 3.9+** — [Apache Maven](https://maven.apache.org/)
- **Docker Desktop** (ou engine Docker equivalente) rodando — [Docker](https://www.docker.com/)

---

## Execução rápida

### Docker Compose (recomendado)

Na raiz do projeto:

```bash
docker compose up --build -d
```

Requisitos: Docker Desktop aberto até o engine ficar ativo (senão o cliente não encontra o pipe `dockerDesktopLinuxEngine` no Windows).

Variável opcional antes do comando (produção / ambientes reais):

```bash
set JWT_SECRET=sua-chave-com-pelo-menos-32-caracteres
docker compose up --build -d
```

(PowerShell: `$env:JWT_SECRET="..."`.)

- API base: **http://localhost:8080/api**
- Health: **http://localhost:8080/api/actuator/health**

Logs da aplicação:

```bash
docker compose logs -f app
```

### Apenas PostgreSQL + app local

```bash
docker compose up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Perfil `dev` habilita SQL debug no console (ver `application.yml`).

### PgAdmin (perfil opcional)

```bash
docker compose --profile tools up -d
```

- Interface: http://localhost:5050  
- Credenciais padrão no `docker-compose.yml` (`PGADMIN_DEFAULT_EMAIL` / `PGADMIN_DEFAULT_PASSWORD`).

### Variáveis de ambiente (desenvolvimento local)

```bash
copy .env.example .env
```

Ajuste `DB_*`, `JWT_*` e `SPRING_PROFILES_ACTIVE` conforme necessário. Não commite o arquivo `.env`.

---

## Conexão ao banco (DBeaver ou similar)

Com o serviço `postgres` do Compose em execução (porta publicada `5432`):

| Campo | Valor |
|---|---|
| Host | `localhost` |
| Porta | `5432` |
| Banco | `oficina_mecanica` |
| Usuário | `oficina` |
| Senha | `oficina123` |

Valores definidos em `docker-compose.yml`. Se a porta 5432 já estiver em uso na máquina, altere o mapeamento no Compose e use a porta correspondente no cliente.

---

## Documentação da API

Com a aplicação no ar:

- **Swagger UI:** http://localhost:8080/api/swagger-ui.html  
- **OpenAPI JSON:** http://localhost:8080/api/v3/api-docs  

### Autenticação

1. `POST /api/auth/login` com corpo, por exemplo:

```json
{
  "email": "admin@oficina.com",
  "senha": "Admin@123"
}
```

2. Use o `accessToken` retornado no Swagger (**Authorize**) como `Bearer <token>`.

Usuário seed está em `db/migration/V2__seed_data.sql`.

---

## Organização do código

Estrutura principal em `src/main/java/com/oficina/`:

| Pacote | Responsabilidade |
|---|---|
| `controller` | Controllers REST |
| `dto` | DTOs de entrada/saída e envelopes (`ApiResponse`) |
| `entity` | Entidades JPA e enums persistidos |
| `exception` | Exceções de domínio e `GlobalExceptionHandler` |
| `repository` | Spring Data JPA |
| `service` | Regras de aplicação e orquestração |
| `validation` | Validadores reutilizáveis (documento, placa) |
| `config` | Segurança, JWT, OpenAPI |

Classe de entrada: `OficinaApplication`.

Recursos:

- `src/main/resources/application.yml` — perfis `dev`, `test`, padrão + variáveis de ambiente  
- `src/main/resources/db/migration/` — scripts Flyway (`V1` schema, `V2` seed, `V3` ajustes de schema)

---

## Fluxo da ordem de serviço

Estados (`status_os`):

```
RECEBIDA → EM_DIAGNOSTICO → AGUARDANDO_APROVACAO → EM_EXECUCAO → FINALIZADA → ENTREGUE
```

| Status | Descrição |
|---|---|
| `RECEBIDA` | OS criada |
| `EM_DIAGNOSTICO` | Diagnóstico em andamento |
| `AGUARDANDO_APROVACAO` | Aguardando aprovação do cliente |
| `EM_EXECUCAO` | Serviço autorizado e em execução |
| `FINALIZADA` | Serviço concluído |
| `ENTREGUE` | Veículo entregue |

---

## Testes

```bash
mvn test
```

Relatório JaCoCo:

```bash
mvn test jacoco:report
```

Abrir: `target/site/jacoco/index.html`.

Testes que usam **Testcontainers** exigem Docker ativo.

Regra de cobertura no `pom.xml`: pacotes `com.oficina.entity`, `com.oficina.validation` e `com.oficina.service` com razão de linhas cobertas mínima de **80%** (goal `jacoco:check`).

---

## Endpoints principais (prefixo `/api`)

Todos os caminhos abaixo são relativos à base **http://localhost:8080/api**.

| Método | Caminho | Descrição | Auth |
|---|---|---|---|
| POST | `/auth/login` | Login JWT | Pública |
| GET/POST | `/clientes` | Listar / criar clientes | Autenticado (ver `@PreAuthorize` no controller) |
| GET/POST | `/veiculos` | Listar / cadastrar veículos | Autenticado (ver `@PreAuthorize` no controller) |
| POST | `/ordens-servico` | Criar OS | Autenticado |
| GET | `/ordens-servico` | Listar OS | Autenticado |
| GET | `/ordens-servico/{numero}/acompanhamento` | Acompanhamento por número | Pública |
| POST | `/ordens-servico/{id}/iniciar-diagnostico` | Transição de status | ADMIN / MECANICO |
| POST | `/ordens-servico/{id}/enviar-orcamento` | Enviar orçamento | ADMIN / MECANICO |
| POST | `/ordens-servico/{numero}/aprovar` | Aprovar orçamento (cliente, documento) | Pública |
| POST | `/ordens-servico/{id}/finalizar` | Finalizar serviço | Autenticado |
| POST | `/ordens-servico/{id}/entregar` | Registrar entrega | Autenticado |
| GET | `/servicos`, `/pecas` | Catálogo paginado | Autenticado |
| GET | `/admin/metricas/tempo-execucao-servicos` | Métricas (admin) | Admin |

Detalhes e restrições por papel: anotações `@PreAuthorize` nos controllers e regras em `SecurityConfig`.

---

## Perfis de acesso (roles)

| Role | Uso típico |
|---|---|
| `ADMIN` | Operações administrativas e métricas |
| `MECANICO` | Fluxo operacional da oficina |
| `CLIENTE` | Acesso limitado (ex.: acompanhamento quando aplicável) |

---

## Variáveis de ambiente relevantes

| Variável | Padrão (local) | Descrição |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/oficina_mecanica` | JDBC URL |
| `DB_USERNAME` | `oficina` | Usuário do banco |
| `DB_PASSWORD` | `oficina123` | Senha do banco |
| `JWT_SECRET` | Veja `application.yml` / Compose | **Definir valor forte em produção** |
| `JWT_EXPIRATION` | `86400000` | Expiração do access token (ms) |
| `SERVER_PORT` | `8080` | Porta HTTP |
| `SPRING_PROFILES_ACTIVE` | `prod` no Compose da app | Perfil Spring |

No Docker Compose, a app usa host `postgres` em `DB_URL` e perfil `prod`.

---

## Contribuição

1. Branch: `git checkout -b feature/descricao-curta`
2. Commits com mensagens claras
3. Abrir Pull Request para revisão

---

## Licença

Projeto privado — todos os direitos reservados.
