# ============================================================================
#  Acesso Materno - Auto-restart do SERVIDOR (server.py)
#  - Mantem o servidor SEMPRE no ar: se cair, religa sozinho.
#  - PROTECAO ANTI-PISCA: sempre espera antes de religar. Se o servidor
#    cair MUITO rapido varias vezes seguidas (crash na largada), segura 60s
#    em vez de ficar em loop maluco. Quando sobe e fica estavel, so roda.
#  - Roda SEM janela (chamado pelo servidor_autorestart.vbs no boot).
# ============================================================================

$dir = 'C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master'
Set-Location -Path $dir
$log = Join-Path $dir 'servidor_autorestart.log'

function Escrever-Log($msg) {
    try {
        "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg" | Add-Content -Encoding UTF8 -Path $log
    } catch {}
}

# --- Localiza o Python (prioriza 3.12, igual aos .bat do projeto) ---
$pyExe  = $null
$pyBase = @()
try { & py -3.12 --version *> $null; if ($LASTEXITCODE -eq 0) { $pyExe = 'py'; $pyBase = @('-3.12') } } catch {}
if (-not $pyExe) { try { & python --version *> $null; if ($LASTEXITCODE -eq 0) { $pyExe = 'python'; $pyBase = @() } } catch {} }
if (-not $pyExe) { try { & py --version *> $null; if ($LASTEXITCODE -eq 0) { $pyExe = 'py'; $pyBase = @() } } catch {} }

if (-not $pyExe) {
    Escrever-Log 'ERRO: Python nao encontrado (instale o Python 3.12). Encerrando.'
    exit 1
}

Escrever-Log "===== Auto-restart iniciado. Python: $pyExe $($pyBase -join ' ') ====="

$falhasRapidas = 0

while ($true) {
    $inicio = Get-Date
    Escrever-Log 'Iniciando servidor (server.py --sem-janela)...'

    $argumentos = $pyBase + @('server.py', '--sem-janela')
    try {
        $proc = Start-Process -FilePath $pyExe -ArgumentList $argumentos `
                    -WorkingDirectory $dir -WindowStyle Hidden -PassThru -Wait
    } catch {
        Escrever-Log "Falha ao iniciar o processo: $($_.Exception.Message)"
    }

    $rodouSegundos = ((Get-Date) - $inicio).TotalSeconds

    if ($rodouSegundos -ge 60) {
        # Ficou no ar tempo suficiente = estava saudavel. Zera o contador
        # e religa rapido. (Depois de estavel, nunca entra em loop de bug.)
        $falhasRapidas = 0
        Escrever-Log ("Servidor encerrou apos {0:N0}s. Religando em 5s..." -f $rodouSegundos)
        Start-Sleep -Seconds 5
    }
    else {
        # Caiu rapido demais = provavel crash na largada. Conta a falha.
        $falhasRapidas++
        Escrever-Log ("Servidor caiu RAPIDO ({0:N0}s) - falha rapida #{1}." -f $rodouSegundos, $falhasRapidas)
        if ($falhasRapidas -ge 5) {
            Escrever-Log 'ATENCAO: 5 quedas rapidas seguidas. Segurando 60s para NAO ficar piscando.'
            Start-Sleep -Seconds 60
            $falhasRapidas = 0
        }
        else {
            Start-Sleep -Seconds 5
        }
    }
}
