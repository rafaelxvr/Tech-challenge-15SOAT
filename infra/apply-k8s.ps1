# Aplica os manifestos Kubernetes após o cluster Kind existir.
# Uso (PowerShell, na raiz do repo):
#   .\infra\apply-k8s.ps1
#   .\infra\apply-k8s.ps1 -Image "oficina-mecanica:local" -ClusterName "oficina-k8s"

param(
    [string]$Image = "oficina-mecanica:local",
    [string]$ClusterName = "oficina-k8s"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Write-Host ">> Contexto kubectl: kind-$ClusterName"
kubectl config use-context "kind-$ClusterName"

Write-Host ">> Carregando imagem $Image no Kind..."
kind load docker-image $Image --name $ClusterName

Write-Host ">> Ajustando imagem em k8s/app.yaml e kustomization..."
$appYaml = Join-Path $root "k8s\app.yaml"
$kustYaml = Join-Path $root "k8s\kustomization.yaml"
(Get-Content $appYaml) -replace "image: .*oficina-mecanica:.*", "image: $Image" | Set-Content $appYaml

Write-Host ">> Aplicando manifestos..."
kubectl apply -f (Join-Path $root "k8s\namespace.yaml")
kubectl apply -f (Join-Path $root "k8s\configmap.yaml")
kubectl apply -f (Join-Path $root "k8s\secret.yaml")
kubectl apply -f (Join-Path $root "k8s\postgres.yaml")
kubectl apply -f (Join-Path $root "k8s\mailhog.yaml")
kubectl apply -f $appYaml

Write-Host ">> Aguardando Postgres e App..."
kubectl -n oficina rollout status deployment/oficina-postgres --timeout=180s
kubectl -n oficina rollout status deployment/oficina-app --timeout=300s

Write-Host ">> Recursos:"
kubectl -n oficina get deploy,svc,hpa,pods
Write-Host "API: http://localhost:30080/api"
