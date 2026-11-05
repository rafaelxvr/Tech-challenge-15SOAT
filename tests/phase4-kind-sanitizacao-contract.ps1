<#
.SYNOPSIS
  Contrato da sanitização dos artefatos BDD (UNR-690): Hide-Secret, bdd-results.json e diagnostico.txt.
  Só valores fictícios; o JWT é montado em tempo de execução para não existir como literal no repositório.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts/phase4-kind.common.ps1')
$failures = New-Object System.Collections.Generic.List[string]
function Assert-That { param([string]$Name, [bool]$Ok) if ($Ok) { Write-Host "PASS $Name" } else { Write-Host "FAIL $Name"; $failures.Add($Name) } }
function ConvertTo-B64Url { param([string]$s) [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)).TrimEnd('=').Replace('+', '-').Replace('/', '_') }

$h = ConvertTo-B64Url '{"alg":"HS256"}'; $p = ConvertTo-B64Url '{"sub":"DUMMYsub"}'
$jwt = "$h.$p.DUMMYsig_-9"
$jwtSemAssinatura = "$h.$p."
Assert-That 'fixture JWT começa com eyJ' ($jwt.StartsWith('eyJ'))

# Bearer / Authorization
$m = Hide-Secret 'Authorization: Bearer DUMMYbearer123 ok'
Assert-That 'Bearer: credencial mascarada, prefixo preservado' (($m -notmatch 'DUMMYbearer123') -and ($m -match 'Bearer \*\*\*'))
Assert-That 'Bearer em minúsculas' ((Hide-Secret 'bearer DUMMYbearer123') -notmatch 'DUMMYbearer123')
Assert-That 'Authorization sem esquema mascara o valor' ((Hide-Secret 'authorization=DUMMYraw789') -notmatch 'DUMMYraw789')
Assert-That 'Authorization em JSON' ((Hide-Secret '{"Authorization":"Bearer DUMMYjsonbearer"}') -notmatch 'DUMMYjsonbearer')
Assert-That 'Basic mascarado' ((Hide-Secret 'Authorization: Basic DUMMYbasic456') -notmatch 'DUMMYbasic456')

# JWT solto
Assert-That 'JWT solto mascarado' ((Hide-Secret "token vazou $jwt fim") -notmatch [regex]::Escape($h))
Assert-That 'JWT solto sem assinatura mascarado' ((Hide-Secret "x $jwtSemAssinatura y") -notmatch [regex]::Escape($p))
Assert-That 'JWT em linha de log mantém o resto' ((Hide-Secret "req id=42 $jwt status=500") -match 'req id=42 \*\*\* status=500')

# Padrões antigos (sem regressão)
Assert-That 'PGPASSWORD=valor' ((Hide-Secret 'PGPASSWORD=DUMMYsecret123 x') -notmatch 'DUMMYsecret123')
Assert-That 'password=valor' ((Hide-Secret 'password=DUMMYsecret123') -notmatch 'DUMMYsecret123')
Assert-That 'SQL PASSWORD literal' ((Hide-Secret "ALTER ROLE demo PASSWORD 'DUMMYsecret123'") -notmatch 'DUMMYsecret123')
Assert-That 'SQL PASSWORD dollar-quoted' ((Hide-Secret 'ALTER ROLE demo PASSWORD $$DUMMYsecret123$$') -notmatch 'DUMMYsecret123')
Assert-That 'JSON "password": "valor"' ((Hide-Secret '{"password": "DUMMYjson456"}') -notmatch 'DUMMYjson456')
Assert-That 'token: valor e api_key=valor' ((Hide-Secret 'token: DUMMYtok1 api_key=DUMMYkey2') -notmatch 'DUMMYtok1|DUMMYkey2')
Assert-That 'texto inócuo inalterado' ((Hide-Secret 'pod ready 1/1') -eq 'pod ready 1/1')
$uma = Hide-Secret "Authorization: Bearer DUMMYbearer123 password=DUMMYsecret123 $jwt"
Assert-That 'Hide-Secret é idempotente' ((Hide-Secret $uma) -eq $uma)

# Campo detalhe do bdd-results.json (retorno normal de handler, não exceção)
$res = @(
    [pscustomobject]@{ id = 'BDD-01'; status = 'PASS'; detalhe = "ok Authorization: Bearer DUMMYbearer123 e $jwt" },
    [pscustomobject]@{ id = 'BDD-02'; status = 'COBERTO_POR_UNITARIO'; detalhe = 'A.java, B.java' },
    [pscustomobject]@{ id = 'BDD-03'; status = 'FAIL'; detalhe = $null }
)
$dir = Join-Path ([IO.Path]::GetTempPath()) ('phase4-san-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dir | Out-Null
try {
    $json = Join-Path $dir 'bdd-results.json'
    [ordered]@{ resultados = (Protect-Resultados $res) } | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $json
    $conteudo = Get-Content -Raw $json
    Assert-That 'bdd-results.json sem Bearer nem JWT' (($conteudo -notmatch 'DUMMYbearer123') -and ($conteudo -notmatch [regex]::Escape($h)))
    Assert-That 'bdd-results.json preserva id, status e detalhe inócuo' (($conteudo -match 'BDD-02') -and ($conteudo -match 'A\.java, B\.java') -and ($conteudo -match 'COBERTO_POR_UNITARIO'))
    $bdd = Get-Content -Raw (Join-Path $root 'scripts/phase4-kind-bdd.ps1')
    Assert-That 'phase4-kind-bdd.ps1 serializa resultados via Protect-Resultados' ($bdd -match 'resultados = \(Protect-Resultados \$resultados\)')

    # diagnostico.txt: sanitizado no caminho final, inclusive com falha no meio da coleta
    $diag = Join-Path $dir 'diagnostico.txt'
    $secoes = @(
        @{ Titulo = 'logs a'; Coletar = { 'linha ok'; 'Authorization: Bearer DUMMYbearer123'; "jwt $jwt" }.GetNewClosure() },
        @{ Titulo = 'logs b'; Coletar = { 'PGPASSWORD=DUMMYsecret123'; throw 'falhou com password=DUMMYsecret999' } },
        @{ Titulo = 'logs c'; Coletar = { 'depois da falha' } }
    )
    Write-SanitizedDiagnostico -Path $diag -Secoes $secoes
    $d = Get-Content -Raw $diag
    Assert-That 'diagnostico.txt não contém credenciais fictícias' (($d -notmatch 'DUMMY') -and ($d -notmatch [regex]::Escape($h)))
    Assert-That 'diagnostico.txt registra a interrupção e segue coletando' (($d -match 'coleta interrompida') -and ($d -match 'depois da falha') -and ($d -match 'linha ok'))
    Assert-That 'nenhum arquivo temporário bruto sobra no diretório' (@(Get-ChildItem $dir -File | Where-Object { $_.Name -notin 'bdd-results.json', 'diagnostico.txt' }).Count -eq 0)

    # sanitização que falha nunca publica texto bruto
    $diag2 = Join-Path $dir 'diagnostico2.txt'
    function Hide-Secret { param([string]$Text) throw 'falha simulada' }
    Write-SanitizedDiagnostico -Path $diag2 -Secoes @(@{ Titulo = 'x'; Coletar = { 'password=DUMMYsecret123' } })
    $d2 = Get-Content -Raw $diag2
    Assert-That 'falha na sanitização publica só o marcador, sem texto bruto' (($d2 -notmatch 'DUMMY') -and ($d2 -match 'indisponível'))
    Remove-Item Function:\Hide-Secret
    . (Join-Path $root 'scripts/phase4-kind.common.ps1')

    # truncamento
    $diag3 = Join-Path $dir 'diagnostico3.txt'
    Write-SanitizedDiagnostico -Path $diag3 -Secoes @(@{ Titulo = 'x'; Coletar = { 'a' * 500 } }) -MaxChars 100
    Assert-That 'diagnóstico é limitado por MaxChars' ((Get-Content -Raw $diag3) -match 'truncado em 100')
}
finally { Remove-Item -Recurse -Force $dir -ErrorAction SilentlyContinue }

# Workflow: o passo de diagnóstico usa o script sanitizador, nunca Add-Content no caminho final
$wf = Get-Content -Raw (Join-Path $root '.github/workflows/phase4-kind-bdd.yml')
Assert-That 'workflow chama phase4-kind-diagnostico.ps1' ($wf -match 'phase4-kind-diagnostico\.ps1')
Assert-That 'workflow não escreve diagnostico.txt com Add-Content' ($wf -notmatch 'Add-Content')

if ($failures.Count) { Write-Host "sanitizacao contract FAILED: $($failures -join '; ')"; exit 1 }
Write-Host 'phase4-kind-sanitizacao-contract=PASS'
