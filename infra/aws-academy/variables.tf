variable "region" {
  description = "Regiao do Learner Lab (somente us-east-1 ou us-west-2)."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = contains(["us-east-1", "us-west-2"], var.region)
    error_message = "O Learner Lab so permite us-east-1 ou us-west-2."
  }
}

variable "allowed_account_ids" {
  description = "ID(s) da conta do Learner Lab. Informar em tfvars FORA do repositorio; nunca versionar."
  type        = list(string)

  validation {
    condition     = length(var.allowed_account_ids) > 0 && alltrue([for id in var.allowed_account_ids : can(regex("^[0-9]{12}$", id))])
    error_message = "allowed_account_ids deve conter ao menos um ID de conta com 12 digitos."
  }
}

variable "shared_credentials_file" {
  description = "Arquivo de credenciais explicito que contem o perfil fiap-lab."
  type        = string
  default     = "~/.aws/credentials"
}

variable "operator_cidr" {
  description = "IP publico do operador em /32 (acesso ao endpoint publico do EKS / API do k3s). Nunca 0.0.0.0/0."
  type        = string

  validation {
    condition     = can(cidrhost(var.operator_cidr, 0)) && endswith(var.operator_cidr, "/32")
    error_message = "operator_cidr deve ser um unico IPv4 em /32."
  }

  # Rejeita /32 que nao e IP publico do operador: 0.0.0.0, loopback, RFC1918, CGNAT, link-local, multicast/reservado.
  validation {
    condition = !can(regex(
      "^(0|10|127|169[.]254|192[.]168|172[.](1[6-9]|2[0-9]|3[01])|100[.](6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])|22[4-9]|2[3-5][0-9])[.]",
      var.operator_cidr
    ))
    error_message = "operator_cidr deve ser o IP publico do operador (nao 0.0.0.0, privado, loopback, CGNAT, link-local ou reservado)."
  }
}

variable "compute_mode" {
  description = "Caminho de compute: eks (padrao) ou k3s (fallback EC2 unica). O restante (rede, RDS, DynamoDB, SQS) e identico."
  type        = string
  default     = "eks"

  validation {
    condition     = contains(["eks", "k3s"], var.compute_mode)
    error_message = "compute_mode deve ser eks ou k3s."
  }
}

variable "name_prefix" {
  description = "Prefixo dos recursos."
  type        = string
  default     = "oficina-lab"
}

# --- IAM pre-existente do Learner Lab (somente data sources; nenhum recurso IAM e criado) ---
variable "lab_node_role_name" {
  description = "Role existente usada pelos nos do EKS e pelos pods (via instance profile)."
  type        = string
  default     = "LabRole"
}

variable "lab_eks_cluster_role_name" {
  description = "Role existente do control plane do EKS. LabEksClusterRole nao existe na conta (sessao 1); LabRole confia em eks.amazonaws.com."
  type        = string
  default     = "LabRole"
}

variable "lab_instance_profile_name" {
  description = "Instance profile existente usado pela EC2 do fallback k3s."
  type        = string
  default     = "LabInstanceProfile"
}

# --- EKS ---
variable "kubernetes_version" {
  description = "Versao do Kubernetes do EKS. Confirmar a versao suportada no card de preflight."
  type        = string
  default     = "1.32"
}

variable "node_instance_type" {
  description = "Tipo dos nos do EKS (limite do Lab: ate large; 2 nos t3.medium = 4 vCPU)."
  type        = string
  default     = "t3.medium"
}

variable "node_count" {
  description = "Numero fixo de nos do EKS."
  type        = number
  default     = 2
}

# --- k3s (fallback) ---
variable "k3s_instance_type" {
  description = "Tipo da EC2 unica do fallback k3s."
  type        = string
  default     = "t3.large"
}

variable "k3s_version" {
  description = "Versao PINADA do k3s (ex.: v1.32.x+k3s1). Sem default: escolher e registrar no card do fallback."
  type        = string
  default     = ""

  validation {
    condition     = var.k3s_version == "" || can(regex("^v[0-9]+\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+$", var.k3s_version))
    error_message = "k3s_version deve ter o formato vX.Y.Z+k3sN."
  }
}

# --- RDS ---
variable "db_instance_class" {
  description = "Classe das instancias RDS (limite do Lab: nano a medium)."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage_gb" {
  description = "Armazenamento gp2 de cada RDS em GB (limite do Lab: 100)."
  type        = number
  default     = 20
}

variable "db_engine_version" {
  description = "Versao major do PostgreSQL (igual ao Kind: 16)."
  type        = string
  default     = "16"
}
