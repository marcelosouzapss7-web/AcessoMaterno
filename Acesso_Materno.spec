# -*- mode: python ; coding: utf-8 -*-
#
# Variante com nome "Acesso_Materno" (sem espaco). Mesmo conteudo canonico do
# "Acesso Materno.spec": inclui PyQt6 + supabase, exige Admin (uac_admin) e
# exclui o painel administrativo. Prefira o "Acesso Materno.spec".
#     pyinstaller --noconfirm --clean "Acesso_Materno.spec"

from PyInstaller.utils.hooks import collect_all

_datas, _binaries, _hidden = [], [], []

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

a = Analysis(
    ['server.py'],
    pathex=[],
    binaries=_binaries,
    datas=[('index.html', '.'), ('icone.ico', '.')] + _datas,
    hiddenimports=_hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['painel_ceo', 'painel_ceo.py'],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='Acesso_Materno',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    uac_admin=True,
    icon='icone.ico',
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='Acesso_Materno',
)
