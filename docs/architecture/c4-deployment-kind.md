# C4 — Deployment local com Kind

```mermaid
C4Deployment
  title Deployment - Ambiente Local Windows com Kind

  Person(avaliador, "Avaliador / Desenvolvedor", "Executa e valida a solução")

  Deployment_Node(host, "Máquina Windows", "Windows + WSL 2", "Host de desenvolvimento") {
    Deployment_Node(docker, "Docker Desktop", "Linux Containers", "Executa os nós do Kind") {
      Deployment_Node(cluster, "Cluster oficina-k8s", "Kubernetes Kind", "Cluster local provisionado por Terraform") {
        Deployment_Node(node, "Control-plane", "Kubernetes node", "Executa as cargas da oficina") {
          Container(api, "oficina-app", "Spring Boot", "Deployment com 2 a 6 pods controlados por HPA")
          ContainerDb(database, "oficina-postgres", "PostgreSQL 16", "Deployment com PVC de 5 GiB")
          Container(mailhog, "oficina-mailhog", "MailHog", "SMTP e interface local de mensagens")
        }
      }
    }
  }

  Rel(avaliador, api, "Acessa pela porta 30080", "NodePort/HTTP")
  Rel(api, database, "Lê e grava dados", "JDBC")
  Rel(api, mailhog, "Envia notificações", "SMTP")
```

## Leitura

O ambiente é local e reproduzível. ConfigMap e Secret alimentam o Deployment da API; Service NodePort expõe a porta 30080; o HPA ajusta as réplicas quando o Metrics Server fornece métricas.
