# Trava de conta (UNR-608 C3): perfil fixo, conta permitida, regiao travada e arquivo de
# credenciais explicito. Qualquer outro perfil/conta faz o provider abortar antes de agir.
provider "aws" {
  region                   = var.region
  profile                  = "fiap-lab"
  shared_credentials_files = [var.shared_credentials_file]
  allowed_account_ids      = var.allowed_account_ids

  default_tags {
    tags = {
      Project   = "oficina-fase4"
      Env       = "learner-lab"
      ManagedBy = "terraform"
    }
  }
}
