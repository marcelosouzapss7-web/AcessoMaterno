@echo off
chcp 65001 >nul
title Build Limpo do Acesso Materno.exe (Python 3.12 / sem conflito de DLL)

REM ============================================================
REM  Build LIMPO do Acesso Materno.exe
REM  - Usa SOMENTE Python 3.12 (via py -3.12)
REM  - Cria um venv ISOLADO (nenhuma DLL do Python 3.14 entra no pacote)
REM  - PyInstaller empacota tudo em _internal (python312.dll + .pyd)
REM  - Console VISIVEL nesta primeira build, para voce ver qualquer erro
REM ============================================================

cd /d "%~dp0"

REM --- Remove qualquer variavel que aponte para Python global ---
set PYTHONPATH=
set PYTHONHOME=

echo [1/5] Criando venv limpa com Python 3.12...
py -3.12 -m venv .venv-build
if errorlevel 1 (
    echo ERRO: Python 3.12 nao encontrado. Instale o Python 3.12 64-bit em python.org.
    pause
    exit /b 1
)

echo [2/5] Instalando dependencias no venv (so 3.12)...
call .venv-build\Scripts\activate.bat
python -m pip install --upgrade pip
REM supabase É OBRIGATORIO: licenca_cliente.py importa "from supabase import create_client".
REM Sem ele o .exe fecha na inicializacao (ModuleNotFoundError: supabase).
pip install fastapi uvicorn python-socketio python-multipart pillow pygame pyqt6 pyqt6-webengine requests supabase
pip install pyinstaller

echo [3/5] Limpando builds antigos (o .spec canonico NAO e apagado)...
rmdir /s /q build 2>nul
rmdir /s /q dist 2>nul

echo [4/5] Compilando a PARTIR do .spec canonico (onedir)...
REM Build dirigido pelo "Acesso Materno.spec": e ELE que define uac_admin=True
REM (exige Admin), exclui painel_ceo e faz collect-all de PyQt6 + supabase.
pyinstaller --noconfirm --clean "Acesso Materno.spec"

echo.
echo [5/5] Concluido! .exe novo em: dist\Acesso Materno\Acesso Materno.exe
echo.
echo ============================================================
echo  VERIFIQUE: em dist\Acesso Materno\_internal\ deve existir
echo  python312.dll (e NAO python314.dll). Se aparecer python314.dll,
echo  voce compilou com o Python errado — rode este .bat de novo.
echo.
echo  1) Teste o .exe rodando-o pelo CMD (console visivel mostra erros).
echo  2) Se subir e a porta 27104 abrir, recompile trocando --console
echo     por --windowed (sem janela preta).
echo  3) Copie a PASTA INTEIRA dist\Acesso Materno\ (nao so o .exe).
echo ============================================================
pause
