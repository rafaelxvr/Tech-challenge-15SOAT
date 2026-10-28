<#
.SYNOPSIS
  Static contract for the Phase 4 BDD matrix (no cluster needed): oracles, feature files, synthetic fixtures and
  the runner skeleton agree with each other and with the saga enums in the source. Fails on drift.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$kindTests = Join-Path $root 'tests/phase4-kind'
$failures = New-Object System.Collections.Generic.List[string]
function Assert-That { param([string]$Name, [bool]$Ok) if ($Ok) { Write-Host "PASS $Name" } else { Write-Host "FAIL $Name"; $failures.Add($Name) } }

function Get-EnumNames {
    param([string]$File)
    $text = Get-Content -Raw (Join-Path $root $File)
    $body = [regex]::Match($text, 'enum\s+\w+\s*\{(.*?)(?:;|\n\})', 'Singleline').Groups[1].Value
    [regex]::Matches($body, '\b([A-Z][A-Z_]+)\b') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
}
function Get-Tipos {
    param([string]$File)
    [regex]::Matches((Get-Content -Raw (Join-Path $root $File)), '\("([A-Za-z]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
}

$saga = 'src/main/java/com/oficina/saga/domain'
$estados = Get-EnumNames "$saga/EstadoSaga.java"
$motivos = Get-EnumNames "$saga/MotivoFalha.java"
$compensacoes = [regex]::Matches((Get-Content -Raw (Join-Path $root "$saga/Compensacao.java")), '^\s+([A-Z_]+)\(', 'Multiline') | ForEach-Object { $_.Groups[1].Value }
$comandos = Get-Tipos "$saga/Comando.java"
$respostas = Get-Tipos "$saga/Resposta.java"
Assert-That 'enums lidos do código (11 estados, 11 motivos, 4 compensações, 7 comandos, 16 respostas)' (
    $estados.Count -eq 11 -and $motivos.Count -eq 11 -and @($compensacoes).Count -eq 4 -and @($comandos).Count -eq 7 -and @($respostas).Count -eq 16)

$oraculos = Get-Content -Raw (Join-Path $kindTests 'oraculos.json') | ConvertFrom-Json
$cenarios = @($oraculos.cenarios)
$ids = @($cenarios | ForEach-Object { $_.id })
Assert-That 'ids dos cenários são únicos e no formato BDD-NNx' (($ids | Select-Object -Unique).Count -eq $ids.Count -and @($ids | Where-Object { $_ -notmatch '^BDD-\d{2}[a-z]$' }).Count -eq 0)

$exigidas = 'fluxo-feliz', 'recusa-pagamento', 'falha-pos-pagamento-estorno', 'duplicidade-idempotencia', 'reply-tardia', 'scheduler', 'indisponibilidade-outbox-localstack', 'retry-dlq-redrive', 'capacidade-reparos'
$categorias = @($cenarios | ForEach-Object { $_.categoria } | Select-Object -Unique)
Assert-That 'as nove categorias exigidas pela UNR-85 estão cobertas' (@($exigidas | Where-Object { $_ -notin $categorias }).Count -eq 0)
Assert-That 'nenhuma categoria fora da lista exigida' (@($categorias | Where-Object { $_ -notin $exigidas }).Count -eq 0)

$modos = 'tres-servicos', 'injecao-de-respostas', 'injecao-de-comandos', 'coberto-por-unitario'
Assert-That 'todo cenário declara um modo válido' (@($cenarios | Where-Object { $_.modo -notin $modos }).Count -eq 0)
$unitarios = @($cenarios | Where-Object { $_.modo -eq 'coberto-por-unitario' })
# Testes de billing/execution (OutboxPublisherTest, PublicadorOutboxTest) vivem em outros repositórios; cada cenário
# unitário precisa de ao menos uma referência resolvível aqui.
Assert-That 'cenários cobertos por unitário têm ao menos um teste existente no os-service' (@($unitarios | Where-Object {
    $achou = @($_.referenciaUnitaria | Where-Object { Get-ChildItem (Join-Path $root 'src/test/java') -Recurse -Filter "$_.java" -ErrorAction SilentlyContinue })
    $achou.Count -eq 0 }).Count -eq 0)

# Valores dos oráculos contra os enums do código.
function Get-Prop { param($Obj, [string]$Name) if ($Obj.PSObject.Properties.Name -contains $Name) { $Obj.$Name } }
$estadosFora = @($cenarios | ForEach-Object { Get-Prop $_ 'estadosSaga'; Get-Prop $_ 'estadosTransitorios' } | Where-Object { $_ -and $_ -notin $estados })
Assert-That 'estados dos oráculos existem em EstadoSaga' ($estadosFora.Count -eq 0)
$motivosFora = @($cenarios | ForEach-Object { Get-Prop $_ 'motivoFalha' } | Where-Object { $_ -and $_ -notin $motivos })
Assert-That 'motivos dos oráculos existem em MotivoFalha' ($motivosFora.Count -eq 0)
$cmdFora = @($cenarios | ForEach-Object { Get-Prop $_ 'comandos' } | Where-Object { $_ -and $_ -notin $comandos })
Assert-That 'comandos dos oráculos existem em Comando' ($cmdFora.Count -eq 0)
$respFora = @($cenarios | ForEach-Object { Get-Prop $_ 'respostas' } | Where-Object { $_ -and $_ -notin $respostas })
Assert-That 'respostas dos oráculos existem em Resposta' ($respFora.Count -eq 0)
$pilhaFora = @($cenarios | ForEach-Object { Get-Prop $_ 'pilhaAntesDaCompensacao'; Get-Prop $_ 'pilhaCompensacaoFinal' } | Where-Object { $_ -is [string] -and $_ -match '^[A-Z_]+$' -and $_ -notin $compensacoes })
Assert-That 'pilhas dos oráculos usam apenas valores de Compensacao' ($pilhaFora.Count -eq 0)

# A ordem dos comandos de compensação é o inverso da pilha, ignorando REPOR_ESTOQUE (local).
$mapa = @{ CANCELAR_ORCAMENTO = 'CancelarOrcamento'; ESTORNAR_PAGAMENTO = 'EstornarPagamento'; CANCELAR_EXECUCAO = 'CancelarExecucao' }
$ordemOk = $true
foreach ($c in @($cenarios | Where-Object { (Get-Prop $_ 'pilhaAntesDaCompensacao') -and (Get-Prop $_ 'comandos') })) {
    $esperado = @(@($c.pilhaAntesDaCompensacao)[-1..-(@($c.pilhaAntesDaCompensacao).Count)] | Where-Object { $mapa.ContainsKey($_) } | ForEach-Object { $mapa[$_] })
    $obtido = @($c.comandos)[(@($c.comandos).Count - $esperado.Count)..(@($c.comandos).Count - 1)]
    if (($esperado -join ',') -ne ($obtido -join ',')) { $ordemOk = $false; Write-Host "  ordem divergente em $($c.id): esperado $($esperado -join ',') obtido $($obtido -join ',')" }
}
Assert-That 'comandos de compensação seguem o inverso da pilha (REPOR_ESTOQUE é local)' $ordemOk

# Features x oráculos.
$features = Get-ChildItem (Join-Path $kindTests 'features') -Filter '*.feature'
Assert-That 'existe um arquivo .feature por categoria' ($features.Count -eq $exigidas.Count)
Assert-That 'todo .feature declara "# language: pt"' (@($features | Where-Object { (Get-Content $_.FullName -TotalCount 1) -ne '# language: pt' }).Count -eq 0)
$tags = @($features | ForEach-Object { Select-String -Path $_.FullName -Pattern '@(BDD-\d{2}[a-z])\b' -AllMatches } | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value })
Assert-That 'todo oráculo tem cenário @BDD-* e vice-versa' (@($ids | Where-Object { $_ -notin $tags }).Count -eq 0 -and @($tags | Where-Object { $_ -notin $ids }).Count -eq 0)
Assert-That 'cada tag @BDD-* aparece uma única vez' (($tags | Select-Object -Unique).Count -eq $tags.Count)
Assert-That 'cenários unitários no oráculo carregam @unitario no .feature' (@($unitarios | Where-Object {
    -not (Select-String -Path ($features.FullName) -Pattern "@$($_.id)\b.*@unitario" -Quiet) }).Count -eq 0)

# Fixtures sintéticas.
$sinteticos = Get-Content -Raw (Join-Path $kindTests 'fixtures/sinteticos.json') | ConvertFrom-Json
$init = Get-Content -Raw (Join-Path $root 'infra/phase4-kind/localstack/init.sh')
$filas = @($sinteticos.filas.billingComandos, $sinteticos.filas.execucaoComandos, $sinteticos.filas.respostasSaga) + @($sinteticos.filas.dlqs)
# init.sh monta os nomes como "$base.fifo" e "$base-dlq.fifo": confere a base e a convenção.
Assert-That 'nomes de fila das fixtures existem no init do LocalStack' (@($filas | Where-Object { $base = $_ -replace '(-dlq)?\.fifo$', ''; $init -notmatch [regex]::Escape($base) }).Count -eq 0 -and $init -match '-dlq\.fifo')
foreach ($t in @($oraculos.cenarios | ForEach-Object { Get-Prop $_ 'respostas' } | Where-Object { $_ } | Select-Object -Unique)) {
    if (-not (Test-Path (Join-Path $root "src/test/resources/contracts/fixtures/$t.json"))) { $failures.Add("fixture de contrato ausente: $t"); Write-Host "FAIL fixture de contrato $t" }
}
$alvo = @(Get-ChildItem $kindTests -Recurse -File | Where-Object { $_.Extension -in '.json', '.feature', '.ps1' }) + @(Get-Item (Join-Path $root 'scripts/phase4-kind-bdd.ps1'))
function Find-Padrao { param([string]$Pattern) @($alvo | Select-String -Pattern $Pattern -CaseSensitive:$false) }
Assert-That 'sem endpoint AWS real, perfil ou credencial nas fixtures' ((Find-Padrao 'amazonaws\.com|AKIA[0-9A-Z]{16}|aws_secret|--profile|sts:AssumeRole').Count -eq 0)
Assert-That 'sem JWT, e-mail ou token literal nas fixtures' ((Find-Padrao 'eyJ[A-Za-z0-9_-]{10,}|[A-Za-z0-9._-]+@[A-Za-z0-9-]+\.[a-z]{2,}|Bearer\s+[A-Za-z0-9]').Count -eq 0)

Assert-That 'runner existe e não usa --from-literal nem PGPASSWORD em argumento' ((Test-Path (Join-Path $root 'scripts/phase4-kind-bdd.ps1')) -and (Find-Padrao '--from-literal|"PGPASSWORD=').Count -eq 0)

if ($failures.Count -gt 0) { Write-Host "`n$($failures.Count) falha(s)"; exit 1 }
Write-Host "`nOK: $($cenarios.Count) cenários, $($features.Count) features, $($filas.Count) filas verificadas"
