# Dois RDS PostgreSQL independentes (isolamento por servico mantido): os-service e billing.
locals {
  databases = {
    os      = "oficina_mecanica"
    billing = "billing"
  }

  # SG que identifica os workloads (nos do EKS ou a EC2 k3s): unica origem permitida na 5432.
  workload_sg_id = var.compute_mode == "eks" ? aws_eks_cluster.this[0].vpc_config[0].cluster_security_group_id : aws_security_group.k3s[0].id
}

resource "random_password" "db_master" {
  for_each = local.databases

  length  = 32
  special = false # sem caracteres que quebrem URL JDBC/psql. ATENCAO: fica em texto claro no tfstate (ver SECURITY.md).
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.public[*].id # RDS exige 2 AZs; as instancias NAO sao publicas (publicly_accessible=false)
}

resource "aws_security_group" "rds" {
  name_prefix = "${local.name}-rds-"
  description = "RDS: 5432 somente do SG dos workloads"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${local.name}-rds" }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_workloads" {
  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL dos workloads"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = local.workload_sg_id
}

resource "aws_db_parameter_group" "pg" {
  name_prefix = "${local.name}-pg16-"
  family      = "postgres16"
  description = "TLS obrigatorio"

  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  for_each = local.databases

  identifier     = "${local.name}-${each.key}"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = each.value
  username = "pgmaster" # master usado so para migration; usuarios de runtime sem DDL sao criados por servico
  password = random_password.db_master[each.key].result

  allocated_storage      = var.db_allocated_storage_gb
  storage_type           = "gp2"
  storage_encrypted      = true
  multi_az               = false
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.pg.name

  # Ambiente descartavel de avaliacao: nada de snapshot, backup ou protecao contra delete.
  backup_retention_period    = 0
  skip_final_snapshot        = true
  deletion_protection        = false
  auto_minor_version_upgrade = false
  apply_immediately          = true
}
