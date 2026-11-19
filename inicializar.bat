@echo off
chcp 65001 >nul
title Inicializador Acesso Materno (27105)
cd /d "C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master"

echo ============================================================
echo  INICIALIZADOR PAINEL ACESSO MATERNO - Tunel Cloudflare 27105
echo ============================================================
echo.

echo [1/4] Matando processos antigos (python/pythonw/Acesso Materno) para liberar a porta 27105...
taskkill /F /IM python.exe            >nul 2>&1
taskkill /F /IM pythonw.exe           >nul 2>&1
taskkill /F /IM "Acesso Materno.exe"  >nul 2>&1
timeout /t 1 /nobreak >nul

echo [2/4] Iniciando servidor FastAPI+Socket.IO (console proprio, minimizado)...
rem  --sem-janela = o servidor NAO dispara o .exe sozinho (evita duplicidade/kill recursivo).
rem  cmd /k mantem o console do servidor aberto para ver os logs do Uvicorn.
start "Servidor Acesso Materno (27105)" /MIN cmd /k "py -3.12 server.py --sem-janela"

echo [3/4] Aguardando 4s para estabilizacao da RAM...
timeout /t 4 /nobreak >nul

echo [4/4] Abrindo interface visual (dist\Acesso Materno.exe) em MODO JANELA...
rem  SOU_EXEC_INTERFACE=1 e setado DEPOIS que o servidor ja spawnou (com seu proprio
rem  env), entao afeta SOMENTE o .exe da interface: ele abre so a janela e morre
rem  em sys.exit(0) — nunca roda Uvicorn, nunca mata o servidor.
set SOU_EXEC_INTERFACE=1
start "" ".\dist\Acesso Materno.exe"

echo.
echo ============================================================
echo  CONCLUIDO. O servidor continua rodando no console minimizado.
echo  Pode fechar esta janela.
echo ============================================================
timeout /t 5 /nobreak >nul
exit
