#!/usr/bin/env bash
# Verifica o overlay aws SEM cluster e SEM AWS (renderizacao local com kubectl kustomize).
# Uso: verify-overlay.sh            -> modo offline (aceita digest placeholder)
#      verify-overlay.sh --release  -> modo pre-apply (recusa digest placeholder)
set -euo pipefail
cd "$(dirname "$0")"
fail() { echo "FALHOU: $*" >&2; exit 1; }
out="$(kubectl kustomize . )" || fail "kustomize nao renderiza"

# Nada de dependencias locais nem Secret versionado.
! grep -qE '^kind: Secret' <<<"$out" || fail "Secret nao pode ser versionado (S-11: criar por stdin)"
! grep -qiE 'oficina-postgres|oficina-mailhog|HorizontalPodAutoscaler' <<<"$out" || fail "dependencia local no overlay"

# Entrada somente por port-forward: sem NodePort/LoadBalancer.
! grep -qE 'type: (NodePort|LoadBalancer)|nodePort:' <<<"$out" || fail "Service exposto (NodePort/LoadBalancer)"
! grep -qE '^kind: (Ingress|Gateway|HTTPRoute|GRPCRoute|TCPRoute|IngressRoute)' <<<"$out" || fail "Ingress/Gateway/HTTPRoute proibido (sem entrada publica; webhook nao existe)"
! grep -qE 'hostNetwork|privileged|hostPID|hostPath' <<<"$out" || fail "pod privilegiado/hostNetwork"
grep -q 'SPRING_PROFILES_ACTIVE: aws' <<<"$out" || fail "perfil Spring aws ausente"
grep -q 'automountServiceAccountToken: false' <<<"$out" || fail "automountServiceAccountToken deve ser false"

# Guarda inversa de S-3: nada de endpoint/credencial de LocalStack no overlay.
! grep -qiE 'localstack|AWS_ENDPOINT_URL|SAGA_SQS_ENDPOINT|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN|AWS_PROFILE|AWS_CONTAINER_|AWS_WEB_IDENTITY|AWS_ROLE_ARN|AWS_EC2_METADATA|AWS_CONFIG_FILE|AWS_SHARED_CREDENTIALS|AWS_ENDPOINT_URL_|aws\.accessKeyId|aws\.secretAccessKey|aws\.endpointUrl|:4566' <<<"$out" || fail "endpoint/credencial local no overlay aws"

# Imagem sempre por digest.
grep -qE 'image: ghcr\.io/[^@ ]+@sha256:[0-9a-f]{64}' <<<"$out" || fail "imagem deve ser GHCR pinada por digest"
if [ "${1:-}" = "--release" ] && grep -qE 'sha256:0{64}' <<<"$out"; then
  fail "digest placeholder: substitua pelo digest do manifesto de release antes de aplicar"
fi

# O checkout do overlay deve ser o commit apontado pelo deliveryRef (tag local), com arvore limpa.
# Somente leitura: nao cria, move nem baixa tags; sem rede e sem AWS.
check_delivery_ref() {
  local svc="$1" dir="$2" ref="$3" pending="$4" head tagged
  git -C "$dir" rev-parse --show-toplevel >/dev/null 2>&1 || fail "overlay de $svc nao esta em um checkout git (necessario para provar o deliveryRef)"
  # Arvore INTEIRA do checkout (sem pathspec): o overlay referencia bases fora do proprio diretorio
  # (ex.: ../../k8s) e qualquer alteracao rastreada ou arquivo nao rastreado (nao ignorado) muda o render.
  # Falha fechada: se o git status falhar, o checkout nao e considerado limpo.
  local dirty
  dirty="$(git -C "$dir" status --porcelain --untracked-files=normal 2>/dev/null)" || fail "nao foi possivel ler o estado git do checkout de $svc"
  [ -z "$dirty" ] || fail "checkout de $svc tem alteracoes locais (rastreadas ou nao rastreadas) em qualquer parte da arvore; use checkout limpo no deliveryRef"
  if tagged="$(git -C "$dir" rev-parse --verify --quiet "$ref^{commit}")"; then
    head="$(git -C "$dir" rev-parse HEAD)"
    [ "$head" = "$tagged" ] || fail "checkout de $svc ($head) difere do deliveryRef $ref ($tagged)"
  elif [ "$pending" = 1 ]; then
    echo "AVISO: $ref nao existe em $svc; ensaio pre-tag (--delivery-tag-pending). NAO usar para o apply." >&2
  else
    fail "deliveryRef $ref nao existe no checkout de $svc (crie a tag de entrega ou use --delivery-tag-pending so em ensaio)"
  fi
}

# Release: cada servico do manifesto versionado deve bater, por chave, com a imagem+digest do overlay.
# Uso: --release [--billing <dir-overlay>] [--execution <dir-overlay>] [--delivery-tag-pending]
#   Overlays dos outros repos devem estar em checkouts limpos no deliveryRef (tag de entrega) do manifesto.
#   sourceSha prova a origem da imagem; o overlay de entrega vem do deliveryRef, nunca do sourceSha.
#   --delivery-tag-pending: ensaio ANTES de o operador criar a tag; so avisa se a tag nao existir
#   (nao usar para o apply: sem tag nao ha prova de que o checkout e o commit de entrega).
if [ "${1:-}" = "--release" ]; then
  shift
  manifest="../../infra/aws-academy/release-manifest.json"
  [ -f "$manifest" ] || fail "manifesto de release ausente: infra/aws-academy/release-manifest.json"
  declare -A overlay_dir=([os-service]=".")
  tag_pending=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --billing) overlay_dir[billing]="${2:?--billing exige o diretorio do overlay aws}"; shift 2 ;;
      --execution) overlay_dir[execution]="${2:?--execution exige o diretorio do overlay aws}"; shift 2 ;;
      --delivery-tag-pending) tag_pending=1; shift ;;
      *) fail "argumento desconhecido: $1" ;;
    esac
  done
  for svc in os-service billing execution; do
    expected="$(python3 -I - "$manifest" "$svc" <<'PY'
import json, re, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
if m.get("schema") != "release-manifest/v1":
    sys.exit("schema invalido")
s = m["services"][sys.argv[2]]
if not re.fullmatch(r"sha256:[0-9a-f]{64}", s["digest"]) or re.fullmatch(r"sha256:0{64}", s["digest"]):
    sys.exit("digest invalido ou placeholder")
if not re.fullmatch(r"[0-9a-f]{40}", s["sourceSha"]):
    sys.exit("sourceSha invalido")
refs = {v.get("deliveryRef") for v in m["services"].values()}
ref = s.get("deliveryRef")
if len(refs) != 1 or not isinstance(ref, str) or not re.fullmatch(r"refs/tags/[A-Za-z0-9._-]+", ref):
    sys.exit("deliveryRef ausente, invalido ou divergente entre servicos (esperado refs/tags/<nome> comum)")
print(s["image"] + "@" + s["digest"])
print(ref)
PY
)" || fail "manifesto invalido para $svc"
    ref="$(sed -n 2p <<<"$expected")"; expected="$(sed -n 1p <<<"$expected")"
    if [ -n "${overlay_dir[$svc]:-}" ]; then
      check_delivery_ref "$svc" "${overlay_dir[$svc]}" "$ref" "$tag_pending"
      rendered="$(kubectl kustomize "${overlay_dir[$svc]}")" || fail "overlay de $svc nao renderiza"
      grep -qF "image: $expected" <<<"$rendered" || fail "overlay de $svc difere do manifesto (esperado $expected)"
      [ "$(grep -cE 'image: ghcr\.io/' <<<"$rendered")" = "$(grep -cF "image: $expected" <<<"$rendered")" ] || fail "overlay de $svc tem imagem GHCR fora do manifesto"
    else
      echo "AVISO: overlay de $svc nao informado; so o digest do manifesto foi validado (use --$svc <dir>)" >&2
    fi
  done
fi
echo "verify-overlay: OK"
