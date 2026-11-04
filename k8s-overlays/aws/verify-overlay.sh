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
! grep -qE 'hostNetwork|privileged|hostPID|hostPath' <<<"$out" || fail "pod privilegiado/hostNetwork"
grep -q 'SPRING_PROFILES_ACTIVE: aws' <<<"$out" || fail "perfil Spring aws ausente"
grep -q 'automountServiceAccountToken: false' <<<"$out" || fail "automountServiceAccountToken deve ser false"

# Guarda inversa de S-3: nada de endpoint/credencial de LocalStack no overlay.
! grep -qiE 'localstack|AWS_ENDPOINT_URL|SAGA_SQS_ENDPOINT|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|:4566' <<<"$out" || fail "endpoint/credencial local no overlay aws"

# Imagem sempre por digest.
grep -qE 'image: ghcr\.io/[^@ ]+@sha256:[0-9a-f]{64}' <<<"$out" || fail "imagem deve ser GHCR pinada por digest"
if [ "${1:-}" = "--release" ] && grep -qE 'sha256:0{64}' <<<"$out"; then
  fail "digest placeholder: substitua pelo digest do manifesto de release antes de aplicar"
fi
echo "verify-overlay: OK"
