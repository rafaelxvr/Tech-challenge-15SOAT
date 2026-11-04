# Caminho padrao: EKS com managed node group. Roles pre-existentes do Lab (data sources).
resource "aws_eks_cluster" "this" {
  count = var.compute_mode == "eks" ? 1 : 0

  name     = local.name
  role_arn = data.aws_iam_role.lab_eks_cluster.arn
  version  = var.kubernetes_version

  vpc_config {
    subnet_ids              = aws_subnet.public[*].id
    endpoint_public_access  = true
    endpoint_private_access = false
    public_access_cidrs     = [var.operator_cidr] # /32 do operador; nunca 0.0.0.0/0
  }

  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }

  # Sem logs do control plane (custo). Consequencia registrada: sem trilha de auditoria do Kubernetes.
  enabled_cluster_log_types = []
}

# IMDSv2 obrigatorio com hop limit 2: pods alcancam o IMDS do no (LabRole) pela default chain;
# IRSA nao existe no Learner Lab. Limitacao documentada em SECURITY.md.
resource "aws_launch_template" "nodes" {
  count = var.compute_mode == "eks" ? 1 : 0

  name_prefix = "${local.name}-nodes-"

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size           = 20
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${local.name}-node" }
  }
}

resource "aws_eks_node_group" "this" {
  count = var.compute_mode == "eks" ? 1 : 0

  cluster_name    = aws_eks_cluster.this[0].name
  node_group_name = "${local.name}-nodes"
  node_role_arn   = data.aws_iam_role.lab_node.arn
  subnet_ids      = aws_subnet.public[*].id
  instance_types  = [var.node_instance_type]
  ami_type        = "AL2023_x86_64_STANDARD"
  capacity_type   = "ON_DEMAND"

  # Sem remote_access: nenhum SSH nos nos.
  scaling_config {
    desired_size = var.node_count
    min_size     = var.node_count
    max_size     = var.node_count
  }

  launch_template {
    id      = aws_launch_template.nodes[0].id
    version = aws_launch_template.nodes[0].latest_version
  }
}
