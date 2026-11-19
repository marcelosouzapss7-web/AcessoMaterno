# -*- mode: python ; coding: utf-8 -*-

from PyInstaller.utils.hooks import collect_all

_datas, _binaries, _hidden = [], [], []

# Pacotes que precisam ser coletados inteiros (FastAPI, PyQt6, SocketIO, Supabase)
_PACOTES_COLETAR = [
    'PyQt6', 'fastapi', 'uvicorn', 'socketio', 'engineio',
    'pygame', 'PIL',
    'supabase', 'gotrue', 'supabase_auth', 'postgrest', 'realtime',
    'storage3', 'supafunc', 'supabase_functions',
    'httpx', 'httpcore', 'h2', 'websockets', 'certifi',
    'pydantic', 'pydantic_core', 'deprecation', 'dateutil', 'jwt',
]
for _pkg in _PACOTES_COLETAR:
    try:
        _d, _b, _h = collect_all(_pkg)
        _datas += _d
        _binaries += _b
        _hidden += _h
    except Exception:
        pass

_hidden += [
    'licenca_cliente',
    'uvicorn.logging', 'uvicorn.loops.auto',
    'uvicorn.protocols.http.auto', 'uvicorn.protocols.websockets.auto',
    'engineio.async_drivers.threading', 'engineio.async_drivers.asgi',
    'supabase', 'gotrue', 'postgrest', 'realtime', 'storage3',
]

# AQUI ESTÁ A CORREÇÃO: Adicionado o env_vars.py para ser empacotado
a = Analysis(
    ['server.py'],
    pathex=[],
    binaries=_binaries,
    datas=[
        ('index.html', '.'),
        ('icone.ico', '.'),
        ('env_vars.py', '.'), # <--- OBRIGATÓRIO PARA LER A URL
        ('css', 'css'),       # frontend servido via StaticFiles (_MEIPASS)
        ('js', 'js'),
    ] + _datas,
    hiddenimports=_hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['painel_ceo', 'painel_ceo.py'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

# ONEFILE (Gera um único .exe que extrai para o TEMP)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='Acesso Materno',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True, # Deixe True para ver os erros no terminal
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    uac_admin=True,
    icon='icone.ico',
)