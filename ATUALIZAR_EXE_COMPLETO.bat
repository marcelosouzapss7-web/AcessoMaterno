@echo off
title ATUALIZAR Acesso Materno.exe (projeto completo + Emulador FPS)
setlocal enableextensions

REM ============================================================================
REM  ATUALIZAR_EXE_COMPLETO.bat - 2 cliques = novo .exe COMPLETO e atualizado
REM
REM   1) Limpa processos antigos e restos de build.
REM   2) Usa a venv boa (.venv-build) - cria se nao existir (Python 3.12).
REM   3) Garante as dependencias do requirements.lock.txt (inclui o FPS).
REM   4) Compila pelo .spec canonico "Acesso Materno.spec".
REM   5) Copia o resultado por cima da pasta de uso "Acesso Materno".
REM ============================================================================

cd /d "%~dp0"

set "PYTHONPATH="
set "PYTHONHOME="

set "VENV=.venv-build"
set "PY=%VENV%\Scripts\python.exe"
set "SPEC=Acesso Materno.spec"
set "PASTA_USO=Acesso Materno"

echo.
echo ============================================================
echo  [0/5] LIMPEZA: fechando processos antigos e restos de build
echo ============================================================
taskkill /IM "%PASTA_USO%.exe" /F /T >nul 2>&1
taskkill /IM "pyinstaller.exe" /F >nul 2>&1
taskkill /IM "emulador_flutuante_nativo.exe" /F >nul 2>&1
ping -n 2 127.0.0.1 >nul
if exist "build" rmdir /s /q "build"
if exist "dist" rmdir /s /q "dist"
if exist "__pycache__" rmdir /s /q "__pycache__"
echo Limpeza concluida.

echo.
echo ============================================================
echo  [1/5] Verificando a venv de build (%VENV%)
echo ============================================================
if not exist "%PY%" (
    echo venv nao encontrada. Criando com Python 3.12...
    py -3.12 -m venv "%VENV%"
    if errorlevel 1 (
        echo.
        echo ERRO: Python 3.12 64-bit nao encontrado. Instale em python.org
        echo e rode este arquivo de novo.
        echo.
        pause
        exit /b 1
    )
)

echo.
echo ============================================================
echo  [2/5] Instalando/conferindo dependencias (inclui o FPS)
echo         Na 1a vez demora; depois e rapido.
echo ============================================================
"%PY%" -m pip install --upgrade pip
if exist "requirements.lock.txt" (
    "%PY%" -m pip install -r requirements.lock.txt
) else (
    echo AVISO: requirements.lock.txt nao achado - instalando o conjunto minimo.
    "%PY%" -m pip install fastapi uvicorn python-socketio python-multipart pillow pygame pyqt6 pyqt6-webengine requests supabase opencv-python numpy mss pynput websocket-client pytesseract rapidocr-onnxruntime onnxruntime pyinstaller
)
if errorlevel 1 (
    echo.
    echo ERRO ao instalar dependencias. Verifique a internet e tente de novo.
    echo.
    pause
    exit /b 1
)

echo.
echo Conferindo pacotes do FPS na venv (cv2 / numpy / onnxruntime)...
"%PY%" -c "import cv2, numpy, onnxruntime; print('OK FPS deps:', cv2.__version__, numpy.__version__)"
if errorlevel 1 (
    echo.
    echo ERRO: faltam dependencias do FPS na venv. O .exe sairia sem o Emulador FPS.
    echo Rode este arquivo de novo com internet para completar a instalacao.
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  [3/5] Limpando builds antigos (o .spec NAO e apagado)
echo ============================================================
if exist "build" rmdir /s /q "build"
if exist "dist" rmdir /s /q "dist"

echo.
echo ============================================================
echo  [4/5] Compilando o .exe COMPLETO pelo .spec canonico
echo         (server.py + painel + FPS embutido + PyQt6 + supabase)
echo ============================================================
"%PY%" -m PyInstaller --noconfirm --clean "%SPEC%"
if errorlevel 1 goto falha_build
if not exist "dist\%PASTA_USO%\%PASTA_USO%.exe" goto falha_build

echo.
echo ============================================================
echo  [5/5] Atualizando a pasta de uso "%PASTA_USO%"
echo ============================================================
REM Fecha o app E seus processos-filho que travam arquivos na pasta de uso
REM (painel QtWebEngine, emulador nativo e o FPS embutido). /T mata a árvore.
taskkill /IM "%PASTA_USO%.exe" /F /T >nul 2>&1
taskkill /IM "QtWebEngineProcess.exe" /F >nul 2>&1
taskkill /IM "emulador_flutuante_nativo.exe" /F /T >nul 2>&1
ping -n 3 127.0.0.1 >nul
robocopy "dist\%PASTA_USO%" "%PASTA_USO%" /E /R:3 /W:2 /NFL /NDL /NJH /NJS >nul
REM 2a tentativa automática se a 1a pegou arquivo em uso (code >= 8).
if %ERRORLEVEL% GEQ 8 (
    ping -n 3 127.0.0.1 >nul
    taskkill /IM "%PASTA_USO%.exe" /F /T >nul 2>&1
    taskkill /IM "QtWebEngineProcess.exe" /F >nul 2>&1
    robocopy "dist\%PASTA_USO%" "%PASTA_USO%" /E /R:3 /W:2 /NFL /NDL /NJH /NJS >nul
)
if %ERRORLEVEL% GEQ 8 (
    echo.
    echo AVISO: nao consegui copiar para "%PASTA_USO%". Arquivo em uso?
    echo O build novo esta pronto em: dist\%PASTA_USO%\
    echo Feche o Acesso Materno e rode de novo, ou copie a pasta manualmente.
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  CONCLUIDO! .exe COMPLETO e ATUALIZADO.
echo    Build novo:   dist\%PASTA_USO%\%PASTA_USO%.exe
echo    Pasta de uso: %PASTA_USO%\%PASTA_USO%.exe (atualizada)
echo  Abra pelo atalho de sempre. O Emulador FPS esta embutido.
echo ============================================================
echo.
pause
exit /b 0

:falha_build
echo.
echo ============================================================
echo  ERRO ao compilar o .exe. Role a tela acima para ver a causa.
echo ============================================================
echo.
pause
exit /b 1
