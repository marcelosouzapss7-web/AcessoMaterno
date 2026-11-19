@echo off
chcp 65001 >nul
title Acesso Materno - Servidor (ADMIN / codigo novo / porta 27104 fixa)

REM ============================================================
REM  ACESSO MATERNO - Lancador v3
REM  - Auto-eleva para ADMINISTRADOR (necessario para matar o
REM    processo antigo que ocupa a porta 27104).
REM  - Roda o server.py CORRIGIDO via Python (prioriza py -3.12).
REM  - O servidor AGORA FORÇA a porta 27104 (o Cloudflare Tunnel
REM    de painel.megabrasiliautilidades.store aponta para
REM    http://localhost:27104). Se nao conseguir liberar a porta,
REM    ele recusa subir e mostra o erro (nao muda de porta).
REM ============================================================

REM --- Auto-eleva para Administrador ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Solicitando privilegios de Administrador (necessario para finalizar o processo antigo)...
    powershell -NoProfile -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"

echo ============================================================
echo  ACESSO MATERNO - Modo Terminal (codigo novo, porta 27104 fixa)
echo  Executando como Administrador. Mantenha esta janela aberta
echo  para o tunel Cloudflare continuar conectado.
echo ============================================================
echo.

REM --- 1) Finaliza processos antigos nas portas 27103/27104 ---
echo [1/3] Finalizando processos antigos (evita erro 404)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":27104" ^| findstr LISTENING') do (
    echo    - Matando PID %%a (porta 27104)
    taskkill /F /T /PID %%a >nul 2>&1
    wmic process where "ProcessId=%%a" delete >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":27103" ^| findstr LISTENING') do (
    taskkill /F /T /PID %%a >nul 2>&1
)
taskkill /F /IM "Acesso Materno.exe" /T >nul 2>&1
timeout /t 2 /nobreak >nul

REM --- 2) Localiza o Python (prioriza 3.12) ---
echo [2/3] Procurando Python...
set PYCMD=
py -3.12 --version >nul 2>&1 && set PYCMD=py -3.12
if not defined PYCMD (
    python --version >nul 2>&1 && set PYCMD=python
)
if not defined PYCMD (
    py --version >nul 2>&1 && set PYCMD=py
)
if not defined PYCMD (
    echo    ERRO: Python nao encontrado. Instale Python 3.12.
    pause
    exit /b 1
)
echo    Python: %PYCMD%

REM --- 3) Localiza o server.py ---
echo [3/3] Procurando server.py corrigido...
set SERVERPY=
for %%D in (
    "%~dp0"
    "%~dp0.."
    "%~dp0..\.."
    "%~dp0..\..\.."
    "%CD%"
    "C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master"
) do (
    if exist "%%~D\server.py" (
        set SERVERPY=%%~fD\server.py
        set PASTASRV=%%~fD
        goto :ENCONTROU
    )
)
echo    ERRO: server.py nao encontrado. Coloque o server.py CORRIGIDO na pasta do projeto.
pause
exit /b 1

:ENCONTROU
echo    server.py em: %PASTASRV%
echo.
echo ============================================================
echo  Iniciando servidor NOVO na porta 27104...
echo  Depois, no painel clique em "🔧 Diagnosticar servidor"
echo  e confira: ✅ Servidor NOVO ok (rota_salvar_config_existe = true)
echo ============================================================
echo.

cd /d "%PASTASRV%"
set ACESSO_MATERNO_SEM_JANELA=1
%PYCMD% server.py --sem-janela

echo.
echo ============================================================
echo  Servidor encerrado. Pressione qualquer tecla para fechar.
echo ============================================================
pause >nul