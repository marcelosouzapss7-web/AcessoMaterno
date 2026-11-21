' ============================================================================
' servidor_autorestart.vbs
' Roda o servidor_autorestart.ps1 TOTALMENTE escondido (sem janela preta).
' E este arquivo que o Windows chama no boot (via Agendador de Tarefas).
' ============================================================================
Option Explicit
Dim WshShell
Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master"

' 0 = janela oculta ; False = nao espera terminar (o loop roda pra sempre).
WshShell.Run "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master\servidor_autorestart.ps1""", 0, False

Set WshShell = Nothing
