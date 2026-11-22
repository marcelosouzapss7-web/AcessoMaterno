@echo off
chcp 65001 >nul
title Painel de Chaves - CEO (Acesso Materno)
cd /d "%~dp0"

REM --- Localiza o Python (prioriza 3.12) ---
set PYCMD=
py -3.12 --version >nul 2>&1 && set PYCMD=py -3.12
if not defined PYCMD ( python --version >nul 2>&1 && set PYCMD=python )
if not defined PYCMD ( py --version >nul 2>&1 && set PYCMD=py )
if not defined PYCMD (
    echo ERRO: Python nao encontrado. Instale o Python 3.12.
    pause
    exit /b 1
)

%PYCMD% PainelChaves_CEO.py
if errorlevel 1 (
    echo.
    echo O painel fechou com erro. Leia a mensagem acima.
    pause
)
