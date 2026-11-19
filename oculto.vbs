' ============================================================================
' oculto.vbs — Executa o inicializar.bat SEM mostrar janela de console.
' Uso: clique duplo neste .vbs (ou compile o inicializar.py com PyInstaller).
' O servidor (py -3.12 server.py) continua com sua propria janela minimizada
' porque o .bat usa "start /MIN cmd /k". Para ocultar tambem o servidor, use
' o inicializar.py com CREATE_NO_WINDOW e compile com --windowed.
' ============================================================================
Option Explicit
Dim WshShell
Set WshShell = CreateObject("WScript.Shell")

WshShell.CurrentDirectory = "C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master"

' 0 = janela oculta ; False = nao espera o .bat terminar (retorna na hora).
WshShell.Run "cmd /c ""inicializar.bat""", 0, False

Set WshShell = Nothing
