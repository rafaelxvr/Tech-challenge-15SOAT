# DynamoDB on-demand do execution: mesmos nomes/chaves/indices de infra/phase4-kind/localstack/init.sh.
# Sem PITR e sem replicacao (ambiente descartavel).
resource "aws_dynamodb_table" "execucoes" {
  name         = "oficina-phase4-execucoes"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "execucaoId"

  attribute {
    name = "execucaoId"
    type = "S"
  }
  attribute {
    name = "filaChave"
    type = "S"
  }
  attribute {
    name = "osId"
    type = "S"
  }
  attribute {
    name = "enfileiradoEm"
    type = "N"
  }

  global_secondary_index {
    name            = "fila-index"
    hash_key        = "filaChave"
    range_key       = "enfileiradoEm"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "os-index"
    hash_key        = "osId"
    range_key       = "enfileiradoEm"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = false
  }
}

resource "aws_dynamodb_table" "outbox" {
  name         = "oficina-phase4-execucao-outbox"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "messageId"

  attribute {
    name = "messageId"
    type = "S"
  }
  attribute {
    name = "estado"
    type = "S"
  }
  attribute {
    name = "disponivelEm"
    type = "N"
  }
  attribute {
    name = "osId"
    type = "S"
  }
  attribute {
    name = "sequencia"
    type = "N"
  }

  global_secondary_index {
    name            = "estado-disponivel-index"
    hash_key        = "estado"
    range_key       = "disponivelEm"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "os-sequencia-index"
    hash_key        = "osId"
    range_key       = "sequencia"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = false
  }
}

resource "aws_dynamodb_table" "inbox" {
  name         = "oficina-phase4-execucao-inbox"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "messageId"

  attribute {
    name = "messageId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = false
  }
}
