<#
.SYNOPSIS
  Bring-up único dos 3 serviços da Fase 4 (os-service, billing, execution) em UM cluster Kind efêmero.
  Sem AWS: imagens locais (`kind load`), LocalStack com credenciais fictícias.
.PARAMETER Action
  Build   exporta cada serviço no SHA pinado (git archive) e builda a imagem local; não precisa de cluster
  Up      Build (se faltar imagem) + cluster + dependências + Flyway do os + `kind load` + 3 serviços + readiness
  Verify  readiness dos 3 serviços (rollout, /actuator/health/readiness dentro do pod) e imagens por SHA
  Down    `kind delete cluster` e prova de que não restou cluster, container, imagem, processo nem porta
.PARAMETER ClusterName
  Padrão oficina-phase4 (ou $env:PHASE4_KIND_CLUSTER). Use outro nome para ensaiar sem tocar no cluster do runner.
.PARAMETER BillingRepo / ExecutionRepo
  Checkouts locais dos repositórios billing e execution (precisam conter o SHA pinado). O os-service é este repositório.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Build', 'Up', 'Verify', 'Down')][string]$Action,
    [string]$ClusterName,
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$OsSha = 'cf34717',
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$BillingSha = '96c811a',
    [ValidatePattern('^[0-9a-f]{7,40}$')][string]$ExecutionSha = '1bf1e6c',
    [string]$OsRepo,
    [string]$BillingRepo,
    [string]$ExecutionRepo
)
if ($ClusterName) { $env:PHASE4_KIND_CLUSTER = $ClusterName }
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')

if (-not $OsRepo) { $OsRepo = $script:RepoRoot }
$FlywayImage = 'flyway/flyway:10.22-alpine@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf'

# Um registro por serviço: tudo que varia entre os três fica aqui, o resto do script é genérico.
$script:Services = @(
    [pscustomobject]@{ Key = 'os'; Repo = $OsRepo; Sha = $OsSha; Image = "oficina-phase4/os-service:$OsSha"; Placeholder = 'oficina-os-service:local'
        Namespace = 'oficina'; Deployment = 'oficina-os-service'; Health = '/api/actuator/health/readiness'; Kustomize = (Join-Path $script:KindDir 'os-service') }
    [pscustomobject]@{ Key = 'billing'; Repo = $BillingRepo; Sha = $BillingSha; Image = "oficina-phase4/billing-service:$BillingSha"; Placeholder = 'billing-service'
        Namespace = 'oficina-billing'; Deployment = 'oficina-billing'; Health = '/billing/actuator/health/readiness'; Kustomize = $null }
    [pscustomobject]@{ Key = 'execution'; Repo = $ExecutionRepo; Sha = $ExecutionSha; Image = "oficina-phase4/execution-service:$ExecutionSha"; Placeholder = 'oficina-execution:local'
        Namespace = 'oficina-execution'; Deployment = 'oficina-execution'; Health = '/execucao/actuator/health/readiness'; Kustomize = $null }
)

function Test-ImagePresent {
    param([string]$Image)
    (Invoke-Native docker @('image', 'inspect', $Image) -AllowFailure).ExitCode -eq 0
}

# Extrai exatamente o SHA pinado (git archive -> zip) para $Dest, sem tocar no working tree do dono do repositório.
function Expand-GitTree {
    param([string]$Repo, [string]$Sha, [string]$Dest, [string[]]$Paths = @())
    $zip = Join-Path ([IO.Path]::GetTempPath()) "phase4-$([Guid]::NewGuid().ToString('N')).zip"
    try {
        Invoke-Native git (@('-C', $Repo, 'archive', '--format=zip', '-o', $zip, $Sha) + $Paths) | Out-Null
        Expand-Archive -Path $zip -DestinationPath $Dest -Force
    }
    finally { Remove-Item $zip -Force -ErrorAction SilentlyContinue }
}

# Exporta exatamente o SHA pinado (sem tocar no checkout do dono) para um diretório temporário exclusivo.
function Export-ServiceSource {
    param($Svc, [string]$Dest)
    if (-not $Svc.Repo -or -not (Test-Path (Join-Path $Svc.Repo '.git'))) { throw "Repo do serviço '$($Svc.Key)' ausente: informe -$((Get-Culture).TextInfo.ToTitleCase($Svc.Key))Repo com um checkout git." }
    $resolved = (Invoke-Native git @('-C', $Svc.Repo, 'rev-parse', '--verify', "$($Svc.Sha)^{commit}")).Output.Trim()
    Expand-GitTree $Svc.Repo $resolved $Dest
    Write-Host "export $($Svc.Key): $resolved"
}

function Invoke-Build {
    $work = Join-Path ([IO.Path]::GetTempPath()) "phase4-stack-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        foreach ($s in $script:Services) {
            if (Test-ImagePresent $s.Image) { Write-Host "image $($s.Image): present"; continue }
            $src = Join-Path $work $s.Key
            Export-ServiceSource $s $src
            Invoke-Native docker @('build', '-q', '-t', $s.Image, $src) | Out-Null
            Write-Host "image $($s.Image): built"
        }
    }
    finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
}

function Import-Image {
    param($Svc)
    $r = Invoke-Native kind @('load', 'docker-image', $Svc.Image, '--name', $script:ClusterName) -AllowFailure
    if ($r.ExitCode -ne 0) {
        # Docker com image store containerd pode recusar `load docker-image`; o arquivo tar contorna isso.
        $tar = Join-Path ([IO.Path]::GetTempPath()) "phase4-$($Svc.Key)-$PID.tar"
        try {
            Invoke-Native docker @('save', '-o', $tar, $Svc.Image) | Out-Null
            Invoke-Native kind @('load', 'image-archive', $tar, '--name', $script:ClusterName) | Out-Null
        }
        finally { Remove-Item $tar -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "kind load $($Svc.Image): ok"
}

# Aplica um manifesto renderizado trocando a imagem placeholder pela tag do SHA.
function Apply-Rendered {
    param([string]$Yaml, $Svc)
    $rendered = $Yaml -replace "(?m)^(\s*image:\s*)$([regex]::Escape($Svc.Placeholder))\s*$", "`${1}$($Svc.Image)"
    if ($rendered -notmatch [regex]::Escape($Svc.Image)) { throw "placeholder de imagem '$($Svc.Placeholder)' não encontrado no manifesto de $($Svc.Key)" }
    Invoke-Kube @('apply', '-f', '-') -StdIn $rendered | Out-Null
}

# O os-service exige chave pública RSA pinada para validar o JWT de cliente. O par é descartável: só a chave
# pública (não secreta) vai ao cluster, via SPRING_APPLICATION_JSON; a privada é descartada com o processo.
function Set-CustomerTrust {
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try { $pem = $rsa.ExportSubjectPublicKeyInfoPem() } finally { $rsa.Dispose() }
    $json = @{ security = @{ jwt = @{ customer = @{ 'public-keys' = @{ 'customer-kind-1' = $pem } } } } } | ConvertTo-Json -Depth 6 -Compress
    $cm = [ordered]@{ apiVersion = 'v1'; kind = 'ConfigMap'; metadata = @{ name = 'oficina-os-customer-trust'; namespace = 'oficina' }; data = @{ SPRING_APPLICATION_JSON = $json } }
    Invoke-Kube @('apply', '-f', '-') -StdIn ($cm | ConvertTo-Json -Depth 6) | Out-Null
}

# Segredos aleatórios por cluster; nunca impressos nem versionados. As senhas do Postgres são copiadas do namespace `data`.
function Initialize-AppSecrets {
    $existing = (Invoke-Kube @('get', 'secret', 'oficina-os-secrets', '-n', 'oficina') -AllowFailure).ExitCode -eq 0
    if ($existing) { return }
    Set-CustomerTrust
    $jwt = (New-RandomSecret 32)   # HMAC compartilhado: o execution valida o token de staff emitido pelo os-service
    Set-KubeSecret oficina 'oficina-os-secrets' @{ jwt_secret = $jwt; db_app_password = (Get-KubeSecretValue data 'postgres-os-roles' app_password) }
    Set-KubeSecret oficina-execution 'oficina-execution-secrets' @{ staff_hmac_secret = $jwt }
    Set-KubeSecret oficina-billing 'oficina-billing-secrets' @{
        jwt_secret = $jwt; mp_webhook_secret = (New-RandomSecret 32)
        db_app_password = (Get-KubeSecretValue data 'postgres-billing-roles' app_password)
        db_migrator_password = (Get-KubeSecretValue data 'postgres-billing-roles' migrator_password)
    }
}

# Flyway (oficina_migrator) + grants D15/D16 a partir das migrations do SHA pinado do os-service.
function Invoke-OsMigration {
    param([string]$MigrationsDir)
    $cm = (Invoke-Kube @('create', 'configmap', 'os-migrations', '-n', 'data', '--dry-run=client', '-o', 'yaml', "--from-file=$MigrationsDir")).Output
    Invoke-Kube @('apply', '-f', '-') -StdIn $cm | Out-Null
    Invoke-Kube @('delete', 'job', 'flyway-os', '-n', 'data', '--ignore-not-found', '--wait=true') | Out-Null
    $job = @"
apiVersion: batch/v1
kind: Job
metadata: { name: flyway-os, namespace: data }
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: flyway
          image: $FlywayImage
          args: ["migrate"]
          env:
            - { name: FLYWAY_URL, value: "jdbc:postgresql://postgres-os.data.svc.cluster.local:5432/oficina" }
            - { name: FLYWAY_USER, value: oficina_migrator }
            - name: FLYWAY_PASSWORD
              valueFrom: { secretKeyRef: { name: postgres-os-roles, key: migrator_password } }
            - { name: FLYWAY_LOCATIONS, value: "filesystem:/flyway/sql" }
            - { name: FLYWAY_CONNECT_RETRIES, value: "10" }
          volumeMounts:
            - { name: migrations, mountPath: /flyway/sql }
      volumes:
        - name: migrations
          configMap: { name: os-migrations }
"@
    Invoke-Kube @('apply', '-f', '-') -StdIn $job | Out-Null
    $wait = Invoke-Kube @('wait', '--for=condition=complete', 'job/flyway-os', '-n', 'data', '--timeout=240s') -AllowFailure
    if ($wait.ExitCode -ne 0) { throw "flyway-os falhou:`n$((Invoke-Kube @('logs', 'job/flyway-os', '-n', 'data') -AllowFailure).Output)" }
    $grants = Get-Content -Raw (Join-Path $script:KindDir 'postgres/grants-os.sql')
    $migPw = Get-KubeSecretValue data 'postgres-os-roles' migrator_password
    Invoke-Psql -Instance os -Database oficina -User oficina_migrator -Password $migPw -Sql $grants -Variables @{ app = 'oficina_app' } | Out-Null
    Write-Host 'os: flyway + grants ok'
}

# billing: overlay kind-fake do próprio repositório + patch do contrato de JWT de staff (o execution é a referência).
function Get-BillingManifest {
    param($Svc, [string]$Work)
    $patchDir = Join-Path $Work 'billing-kustomize'
    New-Item -ItemType Directory -Path $patchDir -Force | Out-Null
    # Renderiza a partir do SHA pinado, não do working tree.
    $src = Join-Path $Work 'billing-k8s'
    Expand-GitTree $Svc.Repo $Svc.Sha $src @('k8s')
    $rel = [IO.Path]::GetRelativePath($patchDir, (Join-Path $src 'k8s/overlays/kind-fake')) -replace '\\', '/'
    Set-Content -Path (Join-Path $patchDir 'kustomization.yaml') -Encoding utf8 -Value @"
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - $rel
patches:
  - path: jwt-staff.yaml
"@
    Copy-Item (Join-Path $script:KindDir 'billing-jwt-staff-patch.yaml') (Join-Path $patchDir 'jwt-staff.yaml')
    (Invoke-Native kubectl @('kustomize', $patchDir)).Output
}

function Get-ExecutionManifest {
    param($Svc, [string]$Work)
    $src = Join-Path $Work 'execution-k8s'
    Expand-GitTree $Svc.Repo $Svc.Sha $src @('k8s')
    (Invoke-Native kubectl @('kustomize', (Join-Path $src 'k8s/local'))).Output
}

function Wait-Service {
    param($Svc)
    Invoke-Kube @('rollout', 'status', "deploy/$($Svc.Deployment)", '-n', $Svc.Namespace, '--timeout=300s') | Out-Null
    Write-Host "ready: $($Svc.Namespace)/$($Svc.Deployment) image=$($Svc.Image)"
}

function Invoke-Up {
    Invoke-Build
    $pwsh = (Get-Process -Id $PID).Path
    Invoke-Native $pwsh @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'phase4-kind.ps1'), '-Action', 'Up') | ForEach-Object { $_.Output } | Write-Host
    Invoke-Native $pwsh @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'phase4-kind-init.ps1')) | ForEach-Object { $_.Output } | Write-Host
    foreach ($s in $script:Services) { Import-Image $s }
    Initialize-AppSecrets

    $work = Join-Path ([IO.Path]::GetTempPath()) "phase4-stack-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $os, $billing, $execution = $script:Services
        # Migrations do SHA pinado do os-service (nunca do working tree).
        $osSrc = Join-Path $work 'os-migrations'
        Expand-GitTree $os.Repo $os.Sha $osSrc @('src/main/resources/db/migration')
        Invoke-OsMigration (Join-Path $osSrc 'src/main/resources/db/migration')

        # O overlay do os-service é versionado NESTE repositório (infra/phase4-kind/os-service), já no commit corrente.
        Apply-Rendered (Invoke-Native kubectl @('kustomize', $os.Kustomize)).Output $os
        Apply-Rendered (Get-BillingManifest $billing $work) $billing
        Apply-Rendered (Get-ExecutionManifest $execution $work) $execution
    }
    finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
    foreach ($s in $script:Services) { Wait-Service $s }
    Write-Host 'Up: OK'
}

function Invoke-Verify {
    Assert-KindCluster
    $fail = 0
    foreach ($s in $script:Services) {
        $ready = (Invoke-Kube @('get', 'deploy', $s.Deployment, '-n', $s.Namespace, '-o', 'jsonpath={.status.readyReplicas}') -AllowFailure).Output
        $img = (Invoke-Kube @('get', 'deploy', $s.Deployment, '-n', $s.Namespace, '-o', 'jsonpath={.spec.template.spec.containers[0].image}') -AllowFailure).Output
        $h = Invoke-Kube @('exec', '-n', $s.Namespace, "deploy/$($s.Deployment)", '--', 'wget', '-qO-', "http://127.0.0.1:8080$($s.Health)") -AllowFailure
        if ($ready -eq '1' -and $img -eq $s.Image -and $h.Output -match '"status"\s*:\s*"UP"') { Write-Host "PASS $($s.Key) readyReplicas=1 image=$img readiness=UP" }
        else { Write-Host "FAIL $($s.Key) readyReplicas=$ready image=$img readiness=$($h.Output)"; $fail = 1 }
    }
    # billing_app precisa de DML nas tabelas que o Flyway do billing criou (default privileges de grants-billing.sql).
    foreach ($t in 'outbox', 'pagamento') {
        $sql = "SELECT CASE WHEN to_regclass('public.$t') IS NULL THEN 'missing' ELSE has_table_privilege('billing_app', 'public.$t', 'SELECT,INSERT,UPDATE')::text END"
        $g = Invoke-Psql -Instance billing -Database billing -Sql $sql -AllowFailure
        if ($g.Output.Trim() -eq 'true') { Write-Host "PASS billing_app tem SELECT/INSERT/UPDATE em $t" }
        else { Write-Host "FAIL billing_app sem DML em $t ($($g.Output.Trim())); rode phase4-kind-init.ps1 (grants-billing.sql) e reinicie o billing"; $fail = 1 }
    }
    if ($fail) { throw 'Verify FAILED' }
    Write-Host 'Verify: OK'
}

# Cleanup comprovado: apaga só o cluster nomeado e confirma que não sobrou nada dele.
function Invoke-Down {
    Invoke-Native kind @('delete', 'cluster', '--name', $script:ClusterName) | Out-Null
    $clusters = (Invoke-Native kind @('get', 'clusters') -AllowFailure).Output -split "`n"
    $containers = (Invoke-Native docker @('ps', '-a', '--filter', "name=^$($script:ClusterName)-", '--format', '{{.Names}}')).Output.Trim()
    $pf = @(Get-CimInstance Win32_Process -Filter "Name='kubectl.exe'" | Where-Object { $_.CommandLine -match "kind-$([regex]::Escape($script:ClusterName))\b.*port-forward" })
    $problems = @()
    if ($clusters -contains $script:ClusterName) { $problems += 'cluster ainda existe' }
    if ($containers) { $problems += "containers: $containers" }
    if ($pf.Count) { $problems += "port-forward residual: $($pf.ProcessId -join ',')" }
    foreach ($s in $script:Services) { Invoke-Native docker @('rmi', $s.Image) -AllowFailure | Out-Null }
    $imagesLeft = @($script:Services | Where-Object { Test-ImagePresent $_.Image })
    if ($imagesLeft) { $problems += "imagens: $($imagesLeft.Image -join ',')" }
    if ($problems) { throw "cleanup incompleto: $($problems -join '; ')" }
    Write-Host "cluster $($script:ClusterName): deleted; containers=0 port-forwards=0 images-removed=$($script:Services.Count)"
}

switch ($Action) {
    'Build' { Invoke-Build }
    'Up' { Invoke-Up }
    'Verify' { Invoke-Verify }
    'Down' { Invoke-Down }
}
