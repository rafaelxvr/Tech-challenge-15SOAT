#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  echo 'BOOTSTRAP_FAILED: invalid argument count' >&2
  exit 2
fi
review="$1"
review_sha="$2"
ca="$3"
receipt="$4"
if ! printf '%s' "$review_sha" | grep -Eq '^[a-f0-9]{64}$'; then
  echo 'BOOTSTRAP_FAILED: invalid review digest' >&2
  exit 2
fi
case "$review" in /work/*) ;; *) echo 'BOOTSTRAP_FAILED: review path is outside the mounted work directory' >&2; exit 2 ;; esac
case "$receipt" in /work/*) ;; *) echo 'BOOTSTRAP_FAILED: receipt path is outside the mounted work directory' >&2; exit 2 ;; esac
case "$ca" in /etc/oficina/public/*) ;; *) echo 'BOOTSTRAP_FAILED: CA path is outside the pinned public mount' >&2; exit 2 ;; esac
if [ ! -f "$review" ] || [ ! -f "$ca" ] || [ -e "$receipt" ]; then
  echo 'BOOTSTRAP_FAILED: required input or empty receipt path is invalid' >&2
  exit 2
fi

umask 077
if ! java -cp '/opt/oficina/classes:/opt/oficina/libs/*' com.oficina.bootstrap.BootstrapMain "$review" "$review_sha" "$ca" "$receipt"; then
  exit 1
fi
printf '%s\n' 'BOOTSTRAP_RECEIPT_JSON_BEGIN'
cat "$receipt"
printf '\n%s\n' 'BOOTSTRAP_RECEIPT_JSON_END'
