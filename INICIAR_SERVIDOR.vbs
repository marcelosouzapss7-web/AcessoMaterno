Set WshShell = CreateObject("WScript.Shell")
WshShell.Run "py -3.12 server.py", 0, False
Set WshShell = Nothing
