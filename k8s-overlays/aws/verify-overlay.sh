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

# Release: o digest do overlay deve ser o do os-service no manifesto versionado.
if [ "${1:-}" = "--release" ]; then
  manifest="../../infra/aws-academy/release-manifest.json"
  [ -f "$manifest" ] || fail "manifesto de release ausente: infra/aws-academy/release-manifest.json"
  for svc in os-service billing execution; do
    grep -qE "\"$svc\": \{" "$manifest" || fail "manifesto sem o servico $svc"
  done
  grep -qE '"digest": "sha256:0{64}"' "$manifest" && fail "manifesto com digest placeholder"
  [ "$(grep -cE '"digest": "sha256:[0-9a-f]{64}"' "$manifest")" = 3 ] || fail "manifesto deve ter 3 digests sha256 validos"
  os_digest="$(grep -oE 'image: ghcr\.io/[^@ ]+@sha256:[0-9a-f]{64}' <<<"$out" | head -1 | grep -oE 'sha256:[0-9a-f]{64}')"
  grep -q "\"digest\": \"$os_digest\"" "$manifest" || fail "digest do overlay difere do manifesto (os-service)"
fi
echo "verify-overlay: OK"
