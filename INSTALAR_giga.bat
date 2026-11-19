@echo off
chcp 65001 >nul
title Instalar giga+ (resolve "failed to install" / conflito de assinatura)

REM ============================================================
REM  Instala o APK giga+ (principal/debug) no aparelho/emulador
REM  conectado, DESINSTALANDO antes a versao antiga (que foi
REM  assinada com outra chave e causa "failed to install").
REM  Depois desta 1a vez, as proximas atualizacoes instalam por
REM  cima normalmente (mesma chave 2d5425b2).
REM ============================================================

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "APK=%~dp0app\build\outputs\apk\principal\debug\app-principal-debug.apk"
set "PKG=com.example.acessomaterno"

if not exist "%ADB%" (
    echo [ERRO] adb nao encontrado em %ADB%
    pause & exit /b 1
)
if not exist "%APK%" (
    echo [ERRO] APK nao encontrado. Compile o giga+ no painel primeiro.
    echo        Esperado: %APK%
    pause & exit /b 1
)

echo [1/4] Procurando aparelho/emulador...
REM tenta conectar no Nox (porta padrao) se nenhum USB estiver presente
"%ADB%" connect 127.0.0.1:62001 >nul 2>&1
"%ADB%" devices

echo.
echo [2/4] Desinstalando versao antiga (principal e modulo suporte)...
"%ADB%" uninstall %PKG% >nul 2>&1
"%ADB%" uninstall %PKG%.suporte >nul 2>&1
echo    - Removido (se existia).

echo.
echo [3/4] Instalando APK novo...
"%ADB%" install -r -d "%APK%"

echo.
echo [4/4] Concluido. Se apareceu "Success" acima, o app foi instalado.
echo    Se aparecer "no devices/emulators found", conecte o celular com
echo    Depuracao USB ligada (ou abra o Nox) e rode de novo.
pause
