<#
.SYNOPSIS
  Reproducible proof of the Phase 4 os database: Flyway V1..V10 on an empty database, V8-fixture upgrade
  to V10, least-privilege runtime grants (D15/D16) and credential/instance isolation. Runs against the
  disposable Kind cluster only. Prints clean-install=V10, upgrade-fixture=V10, runtime-grants=PASS.
#>
[CmdletBinding()]
param()
. (Join-Path $PSScriptRoot 'phase4-kind.common.ps1')
Assert-KindCluster

$FlywayImage = 'flyway/flyway:10.22-alpine@sha256:94a81ca7db9a9f24fd8acd7463fa4560cb8f66aae2aa64485c27eb296f5851cf'
$Migrator = 'oficina_migrator'
$App = 'oficina_app'
$pgDir = Join-Path $script:KindDir 'postgres'
$rolesSql = Get-Content -Raw (Join-Path $pgDir 'init-roles.sql')
$grantsSql = Get-Content -Raw (Join-Path $pgDir 'grants-os.sql')
$fixtureSql = Get-Content -Raw (Join-Path $pgDir 'v8-fixture.sql')

$migPw = Get-KubeSecretValue data 'postgres-os-roles' migrator_password
$appPw = Get-KubeSecretValue data 'postgres-os-roles' app_password
$script:Failures = New-Object System.Collections.Generic.List[string]

function Assert-That {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { Write-Host "PASS $Name" } else { Write-Host "FAIL $Name $Detail"; $script:Failures.Add($Name) }
}

function Assert-Denied {
    param([string]$Name, $Result, [string]$Pattern)
    Assert-That $Name ($Result.ExitCode -ne 0 -and $Result.Output -match $Pattern) "exit=$($Result.ExitCode) out=$($Result.Output)"
}

function Initialize-Database {
    param([string]$Database, [switch]$Create)
    if ($Create) {
        Invoke-Psql -Instance os -Database postgres -Sql "DROP DATABASE IF EXISTS $Database WITH (FORCE); CREATE DATABASE $Database;" | Out-Null
    }
    $vars = @{ db = $Database; migrator = $Migrator; app = $App; migrator_pw = $migPw; app_pw = $appPw }
    Invoke-Psql -Instance os -Database $Database -Sql $rolesSql -Variables $vars | Out-Null
}

function Invoke-Flyway {
    param([string]$Database, [string]$Target = 'latest')
    $name = 'flyway-os'
    $cm = (Invoke-Kube @('create', 'configmap', 'os-migrations', '-n', 'data', '--dry-run=client', '-o', 'yaml',
            "--from-file=$(Join-Path $script:RepoRoot 'src/main/resources/db/migration')")).Output
    Invoke-Kube @('apply', '-f', '-') -StdIn $cm | Out-Null
    Invoke-Kube @('delete', 'job', $name, '-n', 'data', '--ignore-not-found', '--wait=true') | Out-Null
    $job = @"
apiVersion: batch/v1
kind: Job
metadata: { name: $name, namespace: data }
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
            - { name: FLYWAY_URL, value: "jdbc:postgresql://postgres-os.data.svc.cluster.local:5432/$Database" }
            - { name: FLYWAY_USER, value: $Migrator }
            - name: FLYWAY_PASSWORD
              valueFrom: { secretKeyRef: { name: postgres-os-roles, key: migrator_password } }
            - { name: FLYWAY_LOCATIONS, value: "filesystem:/flyway/sql" }
            - { name: FLYWAY_TARGET, value: "$Target" }
            - { name: FLYWAY_CONNECT_RETRIES, value: "10" }
          volumeMounts:
            - { name: migrations, mountPath: /flyway/sql }
      volumes:
        - name: migrations
          configMap: { name: os-migrations }
"@
    Invoke-Kube @('apply', '-f', '-') -StdIn $job | Out-Null
    $wait = Invoke-Kube @('wait', '--for=condition=complete', "job/$name", '-n', 'data', '--timeout=240s') -AllowFailure
    $logs = (Invoke-Kube @('logs', "job/$name", '-n', 'data') -AllowFailure).Output
    if ($wait.ExitCode -ne 0) { throw "flyway job failed for $Database (target $Target):`n$logs" }
    ($logs -split "`n" | Select-String -Pattern 'Successfully (applied|validated)|Schema .* is up to date|Migrating schema' | ForEach-Object { "  flyway: $($_.Line.Trim())" }) | Write-Host
}

function Get-SchemaVersion {
    param([string]$Database)
    $r = Invoke-Psql -Instance os -Database $Database -User $Migrator -Password $migPw `
        -Sql 'SELECT max(version::int) FROM flyway_schema_history WHERE success'
    [int]$r.Output.Trim()
}

function Grant-Runtime {
    param([string]$Database)
    Invoke-Psql -Instance os -Database $Database -User $Migrator -Password $migPw -Sql $grantsSql -Variables @{ app = $App } | Out-Null
}

function Invoke-AsApp { param([string]$Database, [string]$Sql, [switch]$AllowFailure)
    Invoke-Psql -Instance os -Database $Database -User $App -Password $appPw -Sql $Sql -AllowFailure:$AllowFailure
}

function Test-PositiveGrants {
    param([string]$Database)
    $sql = @'
BEGIN;
INSERT INTO os_saga (id, os_id, estado, etapa, versao_orcamento, criado_em, atualizado_em)
  VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000b1', 'AGUARDANDO_DIAGNOSTICO', 'DIAGNOSTICO', 1, now(), now());
UPDATE os_saga SET versao = versao + 1 WHERE id = '00000000-0000-0000-0000-0000000000d1';
INSERT INTO os_saga_passo (id, saga_id, ordem, etapa, evento, estado_saga, ocorrido_em)
  VALUES ('00000000-0000-0000-0000-0000000000d2', '00000000-0000-0000-0000-0000000000d1', 1, 'DIAGNOSTICO', 'CMD:Iniciar', 'AGUARDANDO_DIAGNOSTICO', now());
UPDATE os_saga_passo SET etapa = 'DIAGNOSTICO' WHERE id = '00000000-0000-0000-0000-0000000000d2';
INSERT INTO saga_outbox (message_id, os_id, destino, tipo, envelope)
  VALUES ('00000000-0000-0000-0000-0000000000d3', '00000000-0000-0000-0000-0000000000b1', 'EXECUCAO', 'IniciarDiagnostico', '{}'::jsonb);
UPDATE saga_outbox SET estado = 'PUBLISHED', tentativas = tentativas + 1 WHERE message_id = '00000000-0000-0000-0000-0000000000d3';
INSERT INTO saga_inbox (message_id, tipo) VALUES ('00000000-0000-0000-0000-0000000000d4', 'Resposta');
UPDATE saga_inbox SET tipo = 'Resposta' WHERE message_id = '00000000-0000-0000-0000-0000000000d4';
SELECT (SELECT count(*) FROM os_saga) + (SELECT count(*) FROM os_saga_passo) + (SELECT count(*) FROM saga_outbox) + (SELECT count(*) FROM saga_inbox);
UPDATE clientes SET nome = nome WHERE id = '00000000-0000-0000-0000-0000000000c1';
ROLLBACK;
'@
    $r = Invoke-AsApp $Database $sql -AllowFailure
    Assert-That 'app SELECT/INSERT/UPDATE on os_saga, os_saga_passo, saga_outbox, saga_inbox (+ identity column, no sequence grant)' ($r.ExitCode -eq 0 -and $r.Output.Trim() -eq '4') $r.Output
}

function Test-NegativeGrants {
    param([string]$Database)
    foreach ($t in 'os_saga', 'os_saga_passo', 'saga_outbox', 'saga_inbox', 'clientes', 'veiculos', 'servicos', 'pecas',
                   'ordens_servico', 'os_servicos', 'os_pecas', 'os_historico', 'usuarios') {
        Assert-Denied "app DELETE on $t denied" (Invoke-AsApp $Database "DELETE FROM $t;" -AllowFailure) 'permission denied for table'
    }
    Assert-Denied 'app TRUNCATE os_saga denied' (Invoke-AsApp $Database 'TRUNCATE os_saga CASCADE;' -AllowFailure) 'permission denied'
    Assert-Denied 'app CREATE TABLE denied' (Invoke-AsApp $Database 'CREATE TABLE p4_probe (i int);' -AllowFailure) 'permission denied for schema'
    Assert-Denied 'app ALTER TABLE denied (not owner)' (Invoke-AsApp $Database 'ALTER TABLE os_saga ADD COLUMN p4_probe int;' -AllowFailure) 'must be owner'
    Assert-Denied 'app DROP TABLE denied (not owner)' (Invoke-AsApp $Database 'DROP TABLE saga_outbox;' -AllowFailure) 'must be owner'
    Assert-Denied 'app cannot read flyway_schema_history' (Invoke-AsApp $Database 'SELECT 1 FROM flyway_schema_history;' -AllowFailure) 'permission denied for table'
    $own = Invoke-Psql -Instance os -Database $Database -User $Migrator -Password $migPw `
        -Sql "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner <> '$Migrator'"
    Assert-That 'every public table is owned by the migration role, none by the runtime role' ($own.Output.Trim() -eq '0') $own.Output
    $super = Invoke-Psql -Instance os -Database $Database -Sql "SELECT count(*) FROM pg_roles WHERE rolname IN ('$Migrator','$App') AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)"
    Assert-That 'migrator/runtime roles hold no superuser/createdb/createrole/replication/bypassrls' ($super.Output.Trim() -eq '0') $super.Output
}

function Test-Isolation {
    $billingAppPw = Get-KubeSecretValue data 'postgres-billing-roles' app_password
    Assert-Denied 'runtime role with a wrong password is rejected (scram enforced off-loopback)' `
        (Invoke-Psql -Instance os -Database oficina -User $App -Password 'not-the-password' -Sql 'SELECT 1' -AllowFailure) 'password authentication failed'
    Assert-Denied 'os runtime credentials rejected by the billing instance' `
        (Invoke-Psql -Instance os -ConnectInstance billing -Database billing -User $App -Password $appPw -Sql 'SELECT 1' -AllowFailure) 'authentication failed|does not exist'
    Assert-Denied 'billing runtime credentials rejected by the os instance' `
        (Invoke-Psql -Instance billing -ConnectInstance os -Database oficina -User 'billing_app' -Password $billingAppPw -Sql 'SELECT 1' -AllowFailure) 'authentication failed|does not exist'
    Assert-Denied 'os runtime role cannot connect to the maintenance database' `
        (Invoke-Psql -Instance os -Database postgres -User $App -Password $appPw -Sql 'SELECT 1' -AllowFailure) 'permission denied for database'
    Assert-Denied 'billing runtime role cannot create objects in its own database' `
        (Invoke-Psql -Instance billing -Database billing -User 'billing_app' -Password $billingAppPw -Sql 'CREATE TABLE p4_probe (i int);' -AllowFailure) 'permission denied for schema'
    $same = ($migPw -ne $appPw) -and ($appPw -ne $billingAppPw)
    Assert-That 'migrator, os runtime and billing runtime passwords are all distinct' $same
}

$clean = 'p4_clean_check'
$upgrade = 'p4_upgrade_check'
try {
    Write-Host '== clean install (empty database)'
    Initialize-Database $clean -Create
    Invoke-Flyway $clean
    Grant-Runtime $clean
    $cleanVersion = Get-SchemaVersion $clean
    Assert-That "clean-install=V$cleanVersion" ($cleanVersion -eq 10)

    Write-Host '== upgrade fixture (V8 data -> V10)'
    Initialize-Database $upgrade -Create
    Invoke-Flyway $upgrade '8'
    $v8 = Get-SchemaVersion $upgrade
    Assert-That "schema at V8 before fixture (found V$v8)" ($v8 -eq 8)
    Invoke-Psql -Instance os -Database $upgrade -User $Migrator -Password $migPw -Sql $fixtureSql | Out-Null
    Invoke-Flyway $upgrade
    Grant-Runtime $upgrade
    $upVersion = Get-SchemaVersion $upgrade
    Assert-That "upgrade-fixture=V$upVersion" ($upVersion -eq 10)
    $kept = Invoke-Psql -Instance os -Database $upgrade -User $Migrator -Password $migPw -Sql "SELECT count(*) FROM ordens_servico WHERE id = '00000000-0000-0000-0000-0000000000b1'"
    Assert-That 'V8 fixture order survives the upgrade' ($kept.Output.Trim() -eq '1') $kept.Output

    Write-Host '== runtime grants (as oficina_app, real scram login)'
    Test-PositiveGrants $upgrade
    Test-NegativeGrants $upgrade
    Write-Host '== isolation'
    Test-Isolation

    Write-Host '== main database (oficina) left migrated for the service tracks'
    Initialize-Database 'oficina'
    Invoke-Flyway 'oficina'
    Grant-Runtime 'oficina'
    Assert-That 'oficina at V10' ((Get-SchemaVersion 'oficina') -eq 10)
}
finally {
    foreach ($db in $clean, $upgrade) {
        Invoke-Psql -Instance os -Database postgres -Sql "DROP DATABASE IF EXISTS $db WITH (FORCE);" -AllowFailure | Out-Null
    }
}

if ($script:Failures.Count -gt 0) {
    Write-Host "runtime-grants=FAIL ($($script:Failures.Count) failed): $($script:Failures -join '; ')"
    exit 1
}
Write-Host 'runtime-grants=PASS'
