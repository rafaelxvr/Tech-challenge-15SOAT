# Fallback: EC2 unica com k3s (acionado em G8 trocando compute_mode = "k3s"; sem redesenho).
# Usa o LabInstanceProfile existente. Sem SSH: acesso administrativo por SSM Session Manager (card proprio).
data "aws_ssm_parameter" "al2023" {
  count = var.compute_mode == "k3s" ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_security_group" "k3s" {
  count = var.compute_mode == "k3s" ? 1 : 0

  name_prefix = "${local.name}-k3s-"
  description = "k3s: API 6443 somente do /32 do operador; sem SSH"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-k3s" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "k3s_api" {
  count = var.compute_mode == "k3s" ? 1 : 0

  security_group_id = aws_security_group.k3s[0].id
  description       = "kube-apiserver do operador"
  ip_protocol       = "tcp"
  from_port         = 6443
  to_port           = 6443
  cidr_ipv4         = var.operator_cidr
}

resource "aws_vpc_security_group_egress_rule" "k3s_all" {
  count = var.compute_mode == "k3s" ? 1 : 0

  security_group_id = aws_security_group.k3s[0].id
  description       = "saida (imagens GHCR, k3s, AWS APIs)"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_instance" "k3s" {
  count = var.compute_mode == "k3s" ? 1 : 0

  ami                         = data.aws_ssm_parameter.al2023[0].value
  instance_type               = var.k3s_instance_type
  subnet_id                   = aws_subnet.public[0].id
  vpc_security_group_ids      = [aws_security_group.k3s[0].id]
  iam_instance_profile        = data.aws_iam_instance_profile.lab[0].name
  associate_public_ip_address = true
  user_data_replace_on_change = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_size           = 30
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  # k3s pinado, sem Traefik e sem ServiceLB (nao ha LoadBalancer; entrada por port-forward).
  # O IP publico entra no certificado da API via IMDSv2 (token obrigatorio).
  user_data = <<-EOT
    #!/bin/bash
    set -euo pipefail
    TOKEN=$(curl -sS -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
    PUBLIC_IP=$(curl -sS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4)
    curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION='${var.k3s_version}' sh -s - server \
      --disable traefik --disable servicelb --write-kubeconfig-mode 600 --tls-san "$PUBLIC_IP"
  EOT

  tags = { Name = "${local.name}-k3s" }

  lifecycle {
    precondition {
      condition     = var.k3s_version != ""
      error_message = "compute_mode = k3s exige k3s_version pinada (vX.Y.Z+k3sN)."
    }
  }
}
