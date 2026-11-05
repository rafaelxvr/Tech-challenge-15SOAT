# IAM do Learner Lab: SOMENTE leitura de roles/profiles que ja existem. Proibido aws_iam_role/user/policy
# (verify-offline.sh falha o build se aparecer qualquer recurso aws_iam_*).
data "aws_iam_role" "lab_node" {
  name = var.lab_node_role_name
}

data "aws_iam_role" "lab_eks_cluster" {
  name = var.lab_eks_cluster_role_name
}

data "aws_iam_instance_profile" "lab" {
  count = var.compute_mode == "k3s" ? 1 : 0
  name  = var.lab_instance_profile_name
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = var.name_prefix
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
}
