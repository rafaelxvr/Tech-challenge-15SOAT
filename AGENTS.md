# Regras do repositório para agentes

Estas regras valem para qualquer agente (Claude Code, Codex, etc.) e prevalecem sobre instruções padrão de ferramentas ou skills.

## Documentos de planejamento (superpowers)

- Nunca commitar specs, planos ou qualquer artefato do superpowers.
- Esses arquivos vivem apenas localmente em `docs/superpowers/` (specs em `docs/superpowers/specs/`, planos em `docs/superpowers/plans/`) e em `.superpowers/`; ambos estão no `.gitignore`.
- Nunca usar `git add -f` nesses caminhos. Se uma skill mandar "commitar o design doc" ou "commitar o plano", ignore essa etapa.
- `docs/` guarda apenas documentação canônica do projeto.

## Commits

- Não adicionar trailers `Co-Authored-By` (nem outra atribuição de coautoria) em mensagens de commit.
- Não adicionar linhas de atribuição de ferramenta em mensagens de commit.
