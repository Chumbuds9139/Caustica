# Apaga os artifacts "caustica-bundled-jar" antigos deste repositorio.
#
# POR QUE ISTO EXISTE
#   A cota de artifacts do GitHub Actions em conta Free e' 500 MB. Cada build guarda um jar de
#   ~38 MB e, com a retencao padrao de 90 dias, eles se acumulam ate a cota encher. Quando isso
#   acontece TODO upload passa a falhar, os jobs nativos ficam vermelhos e o job que compila o
#   mod nem chega a rodar -- ou seja, o CI para de validar o codigo.
#
#   Os jars sao apenas builds de commits que ja estao no historico: qualquer um pode ser refeito
#   com `./gradlew build`. Os shims (ngxshim-*) somam ~11 MB no total e sao ignorados por este
#   script.
#
# COMO USAR (PowerShell, no seu PC)
#   1) Autentique-se com a SUA conta:
#        gh auth login
#   2) Veja o que seria apagado, sem apagar nada:
#        .\limpar-artifacts.ps1
#   3) Apague de verdade, preservando o jar mais recente:
#        .\limpar-artifacts.ps1 -Apagar -Manter 1
#
#   Se o Windows bloquear a execucao do script, rode antes (vale so para esta janela):
#        Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#
# DEPOIS
#   O GitHub recalcula o uso a cada 6-12 horas, entao os uploads podem demorar um pouco para
#   voltar a funcionar mesmo apos a exclusao.

[CmdletBinding()]
param(
    # Sem este switch o script apenas simula, sem apagar nada.
    [switch]$Apagar,
    # Quantos jars mais recentes preservar (0 = apaga todos).
    [int]$Manter = 0
)

$ErrorActionPreference = 'Stop'

$Repo = 'xysgottaken2/testingcasutica'
$Nome = 'caustica-bundled-jar'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Error "'gh' nao encontrado. Instale o GitHub CLI: https://cli.github.com"
    exit 1
}

# gh escreve o status na saida de erro; redirecionamos para capturar sem abortar o script.
gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Nao autenticado. Rode 'gh auth login' primeiro."
    exit 1
}

$conta = (gh api user --jq '.login' 2>$null)
Write-Host "Autenticado como: $conta"
Write-Host "Consultando artifacts de $Repo..."

# IMPORTANTE: o filtro jq abaixo NAO pode conter aspas duplas.
#
# Ao invocar um executavel nativo, o PowerShell remove as aspas duplas internas do argumento antes
# de entrega-lo ao processo. Um filtro como  select(.name=="caustica-bundled-jar")  chega ao jq como
# select(.name==caustica-bundled-jar), que o jq interpreta como a subtracao dos identificadores
# "caustica", "bundled" e "jar" -- e falha com  "function not defined: jar/0".
#
# A solucao e' nao filtrar por nome dentro do jq: pedimos todos os campos como TSV (sem nenhuma
# aspa) e fazemos a filtragem por nome aqui no PowerShell, onde a comparacao de strings e' segura.
$jq = '.artifacts[] | select(.expired==false) | [.id, .size_in_bytes, .created_at, .name] | @tsv'
$bruto = gh api "repos/$Repo/actions/artifacts?per_page=100" --paginate --jq $jq 2>$null

if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($bruto)) {
    Write-Error "Falha ao consultar os artifacts. Confira 'gh auth status' e o acesso ao repositorio."
    exit 1
}

$todos = $bruto -split "`n" |
    Where-Object { $_.Trim() } |
    ForEach-Object {
        $c = $_ -split "`t"
        [pscustomobject]@{
            Id      = $c[0]
            Size    = [long]$c[1]
            Created = $c[2]
            Name    = $c[3]
        }
    } |
    Where-Object { $_.Name -eq $Nome } |
    Sort-Object -Property Created -Descending

if (-not $todos -or $todos.Count -eq 0) {
    Write-Host "Nenhum artifact '$Nome' encontrado. Nada a fazer."
    exit 0
}

$totalMb = [math]::Round(($todos | Measure-Object -Property Size -Sum).Sum / 1MB)
Write-Host "Encontrados: $($todos.Count) jars, $totalMb MB no total."

if ($Manter -gt 0) {
    Write-Host "Preservando os $Manter mais recentes."
    $alvos = @($todos | Select-Object -Skip $Manter)
} else {
    $alvos = @($todos)
}

if ($alvos.Count -eq 0) {
    Write-Host "Nada sobrou para apagar depois de preservar $Manter."
    exit 0
}

$alvoMb = [math]::Round(($alvos | Measure-Object -Property Size -Sum).Sum / 1MB)

if (-not $Apagar) {
    Write-Host ''
    Write-Host '--- SIMULACAO (nada foi apagado) ---'
    Write-Host "Seriam apagados $($alvos.Count) jars, liberando $alvoMb MB."
    Write-Host "Sobraria: $($totalMb - $alvoMb) MB de jars + ~11 MB de shims."
    Write-Host ''
    Write-Host 'Para apagar de verdade:  .\limpar-artifacts.ps1 -Apagar -Manter 1'
    exit 0
}

Write-Host ''
Write-Host "Apagando $($alvos.Count) jars ($alvoMb MB)..."
$ok = 0
$falhou = 0
foreach ($a in $alvos) {
    gh api -X DELETE "repos/$Repo/actions/artifacts/$($a.Id)" --silent 2>$null
    if ($LASTEXITCODE -eq 0) {
        $ok++
        Write-Host -NoNewline "`r  apagados: $ok/$($alvos.Count)"
    } else {
        $falhou++
        Write-Host ''
        Write-Host "  FALHOU id=$($a.Id) -- sem permissao? confira 'gh auth status'"
    }
}
Write-Host ''
Write-Host "Concluido: $ok apagados, $falhou falharam."

# Mesmo cuidado com aspas: filtro simples, sem nenhuma aspa dupla.
$restante = gh api "repos/$Repo/actions/artifacts?per_page=100" --paginate `
    --jq '.artifacts[] | select(.expired==false) | .size_in_bytes' 2>$null
if ($restante) {
    $linhas = @($restante -split "`n" | Where-Object { $_.Trim() })
    $bytes = ($linhas | ForEach-Object { [long]$_ } | Measure-Object -Sum).Sum
    Write-Host "Uso atual: $($linhas.Count) artifacts, $([math]::Round($bytes / 1MB)) MB  (limite Free: 500 MB)"
}

Write-Host ''
Write-Host 'Observacao: o GitHub recalcula a cota a cada 6-12 horas; se um upload ainda falhar'
Write-Host 'logo apos a limpeza, tente novamente mais tarde.'
