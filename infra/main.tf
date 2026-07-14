terraform {
  required_version = ">= 1.5.0"

  required_providers {
    kind = {
      source  = "tehcyx/kind"
      version = "~> 0.6"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
  }
}

provider "kind" {}

variable "cluster_name" {
  description = "Nome do cluster Kind (Kubernetes local)"
  type        = string
  default     = "oficina-k8s"
}

variable "app_image" {
  description = "Imagem Docker da API"
  type        = string
  default     = "oficina-mecanica:local"
}

variable "http_node_port" {
  description = "NodePort exposto no host via Kind extraPortMappings"
  type        = number
  default     = 30080
}

# -----------------------------------------------------------------------------
# Cluster Kubernetes local (Kind)
# -----------------------------------------------------------------------------
resource "kind_cluster" "oficina" {
  name           = var.cluster_name
  wait_for_ready = true

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    node {
      role = "control-plane"

      extra_port_mappings {
        container_port = var.http_node_port
        host_port      = var.http_node_port
        protocol       = "TCP"
      }
    }
  }
}

# -----------------------------------------------------------------------------
# Carrega a imagem da API no cluster Kind
# -----------------------------------------------------------------------------
resource "null_resource" "load_image" {
  triggers = {
    cluster = kind_cluster.oficina.name
    image   = var.app_image
  }

  provisioner "local-exec" {
    command = "kind load docker-image ${var.app_image} --name ${kind_cluster.oficina.name}"
  }

  depends_on = [kind_cluster.oficina]
}

# -----------------------------------------------------------------------------
# Aplica manifestos /k8s (banco + app + HPA + secrets).
# No Windows, prefira o script infra/apply-k8s.ps1 após o cluster existir.
# -----------------------------------------------------------------------------
resource "null_resource" "apply_k8s" {
  triggers = {
    cluster = kind_cluster.oficina.name
    image   = var.app_image
  }

  provisioner "local-exec" {
    working_dir = "${path.module}/.."
    command     = "kubectl config use-context kind-${kind_cluster.oficina.name} && kubectl apply -k k8s/ && kubectl -n oficina rollout status deployment/oficina-postgres --timeout=180s && kubectl -n oficina rollout status deployment/oficina-app --timeout=300s"
  }

  depends_on = [null_resource.load_image]
}

output "kubeconfig_path" {
  description = "Kubeconfig gerado pelo provider Kind"
  value       = kind_cluster.oficina.kubeconfig_path
}

output "cluster_name" {
  value = kind_cluster.oficina.name
}

output "api_url" {
  description = "URL da API no host (NodePort mapeado pelo Kind)"
  value       = "http://localhost:${var.http_node_port}/api"
}

output "recursos_criados" {
  description = "Resumo dos recursos provisionados por este Terraform"
  value = {
    cluster_kubernetes = "Kind cluster (control-plane) com NodePort ${var.http_node_port}"
    banco_de_dados     = "PostgreSQL 16 (Deployment + Service + PVC 5Gi) no namespace oficina"
    aplicacao          = "oficina-app (Deployment 2 réplicas + Service + HPA CPU/memória)"
    configuracao       = "ConfigMap (DB_URL, mail, profile) + Secret (JWT, senhas, token e-mail)"
    email_dev          = "MailHog para inspeção de notificações de status"
    migracoes_banco    = "Flyway na inicialização da API (scripts em db/migration)"
  }
}
