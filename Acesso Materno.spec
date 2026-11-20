# -*- mode: python ; coding: utf-8 -*-
#
# SPEC CANONICO do "Acesso Materno.exe" (servidor + tela de licenca).
# Build recomendado (gera o .exe que exige Admin e inclui PyQt6 + supabase):
#     pyinstaller --noconfirm --clean "Acesso Materno.spec"
#
# NAO rode mais o build por linha de comando solta: este .spec e a unica
# fonte de verdade (uac_admin, excludes do painel, collect-all de supabase).

import os
from PyInstaller.utils.hooks import collect_all, collect_submodules

# ---------------------------------------------------------------------------
# FPS EMBUTIDO: a pasta ..\FPS (pacote 'leitor') é adicionada ao pathex para
# que o PyInstaller compile o leitor de tela DENTRO do proprio .exe. O botao
# do FPS no painel relança "Acesso Materno.exe --modo-fps" (ver server.py),
# sem depender de python.exe externo nem de run.py fora da pasta dist.
# ---------------------------------------------------------------------------
_FPS_DIR = os.path.abspath(os.path.join(SPECPATH, '..', 'FPS'))

# GARANTIA: os arquivos de trabalho/config do FPS saem SEMPRE em
# dist\Acesso Materno\FPS\ (para o emulador FPS funcionar numa máquina nova,
# sem depender de pasta FPS pré-existente). As subpastas capturas/videos/finais
# o próprio código cria em runtime (makedirs); aqui garantimos só os arquivos
# essenciais que existirem. Não inclui gravações/capturas antigas (evita inchar
# o .exe).
_fps_datas_extra = []
for _f in ('run.py', 'sensibilidade.json', 'target_window.json', 'README.md', 'requirements.txt'):
    _p = os.path.join(_FPS_DIR, _f)
    if os.path.exists(_p):
        _fps_datas_extra.append((_p, 'FPS'))

# ---------------------------------------------------------------------------
# Coleta robusta de pacotes "pesados" (codigo + binarios + metadados .dist-info,
# necessarios para o supabase/pydantic validarem versoes em runtime).
# Pacotes ausentes no ambiente sao ignorados sem quebrar o build.
# ---------------------------------------------------------------------------
_datas, _binaries, _hidden = [], [], []

_PACOTES_COLETAR = [
    # UI / servidor
    'PyQt6', 'fastapi', 'uvicorn', 'socketio', 'engineio',
    'pygame', 'PIL',
    # supabase + toda a arvore de dependencias (nomes variam por versao,
    # por isso listamos todos os candidatos; os inexistentes sao pulados)
    'supabase', 'gotrue', 'supabase_auth', 'postgrest', 'realtime',
    'storage3', 'supafunc', 'supabase_functions',
    'httpx', 'httpcore', 'h2', 'websockets', 'gotrue_py', 'certifi',
    'pydantic', 'pydantic_core', 'deprecation', 'dateutil', 'jwt',
    # --- FPS (leitor de tela) embutido ---
    'mss', 'pynput', 'websocket', 'pytesseract',
    'rapidocr_onnxruntime', 'onnxruntime',
]

for _pkg in _PACOTES_COLETAR:
    try:
        _d, _b, _h = collect_all(_pkg)
        _datas += _d
        _binaries += _b
        _hidden += _h
    except Exception:
        # Pacote nao instalado neste ambiente: ignora.
        pass

# Pacotes NATIVOS (DLLs/.pyd) que exigem collect_all para copiar os binarios:
# cv2 (gravacao de tela) e numpy; onnxruntime (OCR do FPS). Sem isso o .exe
# sobe mas gravacao/OCR caem em modo degradado por falta das DLLs.
_pacotes_nativos = ['cv2', 'numpy', 'onnxruntime']
for _pkg in _pacotes_nativos:
    try:
        _d, _b, _h = collect_all(_pkg)
        _datas += _d
        _binaries += _b
        _hidden += _h
    except Exception:
        pass

# Imports que o analisador estatico do PyInstaller costuma nao enxergar.
_hidden += [
    'licenca_cliente',
    'uvicorn.logging', 'uvicorn.loops.auto',
    'uvicorn.protocols.http.auto', 'uvicorn.protocols.websockets.auto',
    'engineio.async_drivers.threading', 'engineio.async_drivers.asgi',
    'supabase', 'supabase.client', 'gotrue', 'postgrest', 'realtime',
    'storage3', 'supabase_auth',
]

# Pacote 'leitor' do FPS (todos os submodulos) empacotado dentro do .exe.
try:
    _hidden += collect_submodules('leitor')
except Exception:
    pass
_hidden += [
    'leitor', 'leitor.main', 'leitor.worker', 'leitor.socket_client',
    'leitor.window_tracker', 'leitor.wireframe_view', 'leitor.region_selector',
    'leitor.teclado_capture', 'leitor.teclado_log',
]


a = Analysis(
    ['server.py'],
    pathex=[_FPS_DIR],
    binaries=_binaries,
    datas=[
        ('index.html', '.'),
        ('icone.ico', '.'),
        ('env_vars.py', '.'),
        # Pastas de frontend servidas via StaticFiles a partir de _MEIPASS
        # (ver server.py: montagem de /css e /js). Sem elas o .exe sobe
        # mas o index.html fica sem estilo/scripts.
        ('css', 'css'),
        ('js', 'js'),
    ] + _fps_datas_extra + _datas,
    hiddenimports=_hidden,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # Painel administrativo NUNCA entra no pacote do cliente.
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
    name='Acesso Materno',
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
    # Exige elevacao de Administrador no Windows (manifest UAC requireAdministrator).
    uac_admin=True,
    # Icone oficial do .exe (multi-resolucao 16..256).
    icon='icone.ico',
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='Acesso Materno',
)
