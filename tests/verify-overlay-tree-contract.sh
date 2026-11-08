#!/usr/bin/env bash
# Contrato offline (sem AWS, sem cluster, sem rede) de check_delivery_ref em verify-overlay.sh --release:
# a limpeza deve valer para a ARVORE GIT INTEIRA do checkout, nao so para o diretorio do overlay.
# Uso: tests/verify-overlay-tree-contract.sh
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
ref="$(python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["services"]["os-service"]["deliveryRef"])' "$repo/infra/aws-academy/release-manifest.json")"

# Checkout fixture com o overlay real, a base real (../../k8s) e o manifesto real, commitado e com a tag de entrega.
fx="$temp/fx"
mkdir -p "$fx/k8s-overlays" "$fx/infra"
cp -r "$repo/k8s-overlays/aws" "$fx/k8s-overlays/aws"
cp -r "$repo/k8s" "$fx/k8s"
cp -r "$repo/infra/aws-academy" "$fx/infra/aws-academy"
printf 'ignorado.tmp\n' > "$fx/.gitignore"
git -C "$fx" init -q
git -C "$fx" add -A
git -C "$fx" -c user.name=teste -c user.email=teste@example.invalid commit -q -m fixture
git -C "$fx" tag "${ref#refs/tags/}"
verify() { (cd "$fx/k8s-overlays/aws" && ./verify-overlay.sh --release 2>&1); }
cleanup_fx() { git -C "$fx" reset -q --hard HEAD; git -C "$fx" clean -qfd; }
expect_ok() {
  local out; out="$(verify)" || { echo "FALHOU ($1): esperado sucesso"; echo "$out"; exit 1; }
  grep -q 'verify-overlay: OK' <<<"$out" || { echo "FALHOU ($1): sem 'verify-overlay: OK'"; exit 1; }
  echo "ok: $1"
}
expect_fail() {
  local out
  if out="$(verify)"; then echo "FALHOU ($1): esperado recusa, mas passou"; exit 1; fi
  grep -q 'alteracoes locais' <<<"$out" || { echo "FALHOU ($1): recusa por motivo errado"; echo "$out"; exit 1; }
  echo "ok: $1"
}

expect_ok "checkout inteiro limpo passa"

target="$(git -C "$fx" ls-files k8s | head -n1)"
printf '\n# alteracao local\n' >> "$fx/$target"
expect_fail "alteracao rastreada FORA do overlay ($target) falha"
cleanup_fx

printf 'extra\n' > "$fx/k8s/nao-rastreado.yaml"
expect_fail "arquivo nao rastreado FORA do overlay falha"
cleanup_fx

printf 'extra\n' > "$fx/k8s-overlays/aws/nao-rastreado.yaml"
expect_fail "arquivo nao rastreado DENTRO do overlay falha"
cleanup_fx

printf '\n# alteracao local\n' >> "$fx/k8s-overlays/aws/kustomization.yaml"
expect_fail "alteracao rastreada DENTRO do overlay falha"
cleanup_fx

printf 'x\n' > "$fx/ignorado.tmp"
expect_ok "arquivo ignorado pelo .gitignore nao bloqueia"
rm -f "$fx/ignorado.tmp"

# Fail-closed preservado: tag ausente recusa sem --delivery-tag-pending; com a flag, so avisa.
git -C "$fx" tag -d "${ref#refs/tags/}" >/dev/null
if out="$(verify)"; then echo "FALHOU: tag ausente deveria recusar"; exit 1; fi
grep -q 'nao existe' <<<"$out" || { echo "FALHOU: recusa de tag ausente com motivo errado"; echo "$out"; exit 1; }
echo "ok: tag ausente recusa"
out="$(cd "$fx/k8s-overlays/aws" && ./verify-overlay.sh --release --delivery-tag-pending 2>&1)" || { echo "FALHOU: --delivery-tag-pending"; echo "$out"; exit 1; }
grep -q 'AVISO' <<<"$out" || { echo "FALHOU: --delivery-tag-pending sem aviso"; exit 1; }
printf 'extra\n' > "$fx/k8s/nao-rastreado.yaml"
if out="$(cd "$fx/k8s-overlays/aws" && ./verify-overlay.sh --release --delivery-tag-pending 2>&1)"; then echo "FALHOU: --delivery-tag-pending nao pode ignorar arvore suja"; exit 1; fi
echo "ok: --delivery-tag-pending continua recusando arvore suja"

# Proveniencia: tag deve ser SHA de 40 hex e igual a sourceSha (fail-closed). Cada caso comita o manifesto
# alterado na fixture (arvore limpa) para que a recusa venha da tag, nao de arvore suja.
git -C "$fx" tag "${ref#refs/tags/}"
manifest_fx="$fx/infra/aws-academy/release-manifest.json"
manifest_ok="$(cat "$manifest_fx")"
mutate_tag() { # $1 = servico, $2 = novo valor de tag ("__remover__" remove o campo)
  python3 -I - "$manifest_fx" "$1" "$2" <<'PY'
import json, sys
p, svc, val = sys.argv[1:4]
m = json.load(open(p, encoding="utf-8"))
if val == "__remover__":
    del m["services"][svc]["tag"]
else:
    m["services"][svc]["tag"] = val
json.dump(m, open(p, "w", encoding="utf-8"), indent=2)
PY
  git -C "$fx" add -A
  git -C "$fx" -c user.name=teste -c user.email=teste@example.invalid commit -q -m "tag $1"
  git -C "$fx" tag -f "${ref#refs/tags/}" >/dev/null
}
restore_manifest() {
  printf '%s
' "$manifest_ok" > "$manifest_fx"
  git -C "$fx" add -A
  git -C "$fx" -c user.name=teste -c user.email=teste@example.invalid commit -q --allow-empty -m restaurar
  git -C "$fx" tag -f "${ref#refs/tags/}" >/dev/null
}
expect_tag_fail() {
  local out
  if out="$(verify)"; then echo "FALHOU ($1): esperado recusa, mas passou"; exit 1; fi
  grep -q 'manifesto invalido' <<<"$out" && grep -q "$2" <<<"$out" || { echo "FALHOU ($1): recusa por motivo errado"; echo "$out"; exit 1; }
  if grep -q 'verify-overlay: OK' <<<"$out"; then echo "FALHOU ($1): imprimiu OK"; exit 1; fi
  echo "ok: $1"
}
other_sha="0123456789abcdef0123456789abcdef01234567"
for svc in os-service billing execution; do
  mutate_tag "$svc" "$other_sha"
  expect_tag_fail "tag divergente de sourceSha em $svc falha" 'tag diverge de sourceSha'
  restore_manifest
  mutate_tag "$svc" "latest"
  expect_tag_fail "tag nao-SHA em $svc falha" 'tag ausente ou invalida'
  restore_manifest
  mutate_tag "$svc" "__remover__"
  expect_tag_fail "tag ausente em $svc falha" 'tag ausente ou invalida'
  restore_manifest
done
expect_ok "manifesto com tag == sourceSha continua passando"
echo "verify-overlay-tree-contract: OK"
