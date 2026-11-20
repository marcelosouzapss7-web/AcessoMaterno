# ============================================================================
# ISOLAMENTO DE DLL (PyInstaller — Python 3.12 vs 3.14)
# ----------------------------------------------------------------------------
# Evita o erro: "ImportError: Module use of python312.dll conflicts with this
# version of Python" e o crash silencioso do servidor (que causa
# "127.0.0.1 refused to connect" na janela do emulador).
# ESTE BLOCO RODA ANTES DE QUALQUER import de terceiros. Quando rodando como
# .exe (frozen), remove do sys.path qualquer site-packages global e remove
# PYTHONPATH/PYTHONHOME do ambiente, para que nenhuma biblioteca C compilada
# para OUTRA versão do Python seja carregada por engano.
# ============================================================================
import sys as _sys_isol
import os as _os_isol
if getattr(_sys_isol, "frozen", False):
    _raiz_exe = _os_isol.path.dirname(_sys_isol.executable)
    _meipass = getattr(_sys_isol, "_MEIPASS", _raiz_exe)
    _permitidos = (_raiz_exe.lower(), _meipass.lower())
    _sys_isol.path = [
        p for p in _sys_isol.path
        if (p.lower().startswith(_permitidos) or "site-packages" not in p.lower())
        and "python314" not in p.lower()
    ]
    for _v in ("PYTHONPATH", "PYTHONHOME", "PYTHONEXECUTABLE"):
        _os_isol.environ.pop(_v, None)
    del _permitidos, _v
del _sys_isol, _os_isol

# ============================================================================
# BLINDAGEM PRECOCE --windowed/--noconsole (PyInstaller)
# ----------------------------------------------------------------------------
# Sem console no Windows (.exe compilado com --windowed/--noconsole),
# sys.stdout e sys.stderr sao None. Modulos como uvicorn/logging.py chamam
# sys.stdout.isatty() AINDA NO IMPORT (e nao so no uvicorn.run), causando:
#   AttributeError: 'NoneType' object has no attribute 'isatty'
#   (Unable to configure formatter 'default')
# Roda ANTES de QUALQUER import de terceiro, logo apos o isolamento de DLL,
# para o fluxo ficticio de console ja existir na memoria global do Python.
# ============================================================================
import sys, io
if sys.stdout is None:
    sys.stdout = io.StringIO()
if sys.stderr is None:
    sys.stderr = io.StringIO()

import base64
import ipaddress
import subprocess
import os
import sys
import time
import random
import io
import asyncio
import threading
import multiprocessing
import re
import shutil
import zipfile
from collections import deque  # noqa: E402 (importado tambem no topo anti-NameError)
from typing import Optional
from xml.sax.saxutils import escape as xml_escape


def _nome_seguro_xml(nome):
    """Escapa caracteres para não quebrar o strings.xml (XML + Android).
    XML: & < >  |  Android: apóstrofo (') precisa de \' e contrabarra (\\) de \\.
    Sem o escape do apóstrofo, o AAPT2 aborta com processReleaseResources FAILED."""
    texto = xml_escape(nome.strip())
    texto = texto.replace("\\", "\\\\").replace("'", "\\'")
    return texto


def _patch_gradle_app_name(raiz, nome_app, mensagens):
    """WHITE-LABEL — FORCA o appName em TODOS os manifestPlaceholders do
    app/build.gradle(.kts), em AMBOS os flavors (principal E suporte).
    Usa multiplos regex para cobrir TODAS as sintaxes possiveis:
      Kotlin: manifestPlaceholders["appName"] = "..."
              manifestPlaceholders += mapOf("appName" to "...")
              manifestPlaceholders.appName = "..."
      Groovy: manifestPlaceholders = [appName: "..."]
              manifestPlaceholders.appName "..."
    Tambem remove valores hardcoded de marcas antigas (Trindade, Giga +,
    Rastreio Facil, Acesso Materno) que aparecam como valor de appName."""
    caminhos_gradle = [
        os.path.join(raiz, "app", "build.gradle.kts"),
        os.path.join(raiz, "app", "build.gradle"),
    ]
    nome_limpo = nome_app.strip().replace('"', '\\"')
    total_substituido = 0
    for caminho_gradle in caminhos_gradle:
        if not os.path.exists(caminho_gradle):
            continue
        try:
            with open(caminho_gradle, "r", encoding="utf-8") as f:
                conteudo = f.read()
            original = conteudo
            # --- Sintaxe Kotlin: manifestPlaceholders["appName"] = "..." ---
            conteudo, n1 = re.subn(
                r'manifestPlaceholders\s*\[\s*"appName"\s*\]\s*=\s*"[^"]*"',
                f'manifestPlaceholders["appName"] = "{nome_limpo}"',
                conteudo,
            )
            # --- Sintaxe Kotlin: manifestPlaceholders += mapOf("appName" to "...") ---
            conteudo, n2 = re.subn(
                r'manifestPlaceholders\s*\+=\s*mapOf\(\s*"appName"\s+to\s+"[^"]*"',
                f'manifestPlaceholders += mapOf("appName" to "{nome_limpo}"',
                conteudo,
            )
            # --- Sintaxe Kotlin: manifestPlaceholders.appName = "..." ---
            conteudo, n3 = re.subn(
                r'manifestPlaceholders\.appName\s*=\s*"[^"]*"',
                f'manifestPlaceholders.appName = "{nome_limpo}"',
                conteudo,
            )
            # --- Sintaxe Groovy: manifestPlaceholders = [appName: "..."] ---
            conteudo, n4 = re.subn(
                r'manifestPlaceholders\s*=\s*\[\s*appName\s*:\s*"[^"]*"',
                f'manifestPlaceholders = [appName: "{nome_limpo}"',
                conteudo,
            )
            # --- Sintaxe Groovy: manifestPlaceholders.appName "..." (sem =) ---
            conteudo, n5 = re.subn(
                r'manifestPlaceholders\.appName\s+"[^"]*"',
                f'manifestPlaceholders.appName "{nome_limpo}"',
                conteudo,
            )
            # --- Fallback: qualquer "appName" seguido de valor de marca antiga ---
            for marca in ["Trindade", "Giga +", "Giga+", "Rastreio Fácil", "Rastreio Facil", "Rastreio facil", "Acesso Materno", "NET BOA"]:
                conteudo = conteudo.replace(f'"{marca}"', f'"{nome_limpo}"')
            total_substituido += n1 + n2 + n3 + n4 + n5
            if conteudo != original:
                with open(caminho_gradle, "w", encoding="utf-8") as f:
                    f.write(conteudo)
                nome_arq = os.path.basename(caminho_gradle)
                mensagens.append(f"{nome_arq}: appName forcado para '{nome_limpo}' em {n1+n2+n3+n4+n5} ocorrencia(s) (ambos os flavors).")
                registrar_log(f"[WHITE-LABEL] {nome_arq}: manifestPlaceholders appName -> '{nome_limpo}' ({n1+n2+n3+n4+n5} ocorrencias).")
        except Exception as e_grd:
            registrar_log(f"[WHITE-LABEL ERRO build.gradle] {e_grd}")
    if total_substituido == 0:
        registrar_log("[WHITE-LABEL AVISO] Nenhuma ocorrencia de manifestPlaceholders appName encontrada no build.gradle — verifique a sintaxe.")




def _patch_manifest_labels(raiz, mensagens):
    """WHITE-LABEL — FORCA android:label="@string/app_name" em TODOS os
    AndroidManifest.xml (main + todos os flavors), em <application> E em
    <activity>. Usa regex UNIVERSAL: substitui QUALQUER valor hardcoded
    (nao apenas strings conhecidas como 'Rastreio Facil' / 'Acesso Materno').
    Tambem limpa de strings.xml qualquer <string> cujo valor contenha marcas
    antigas (Rastreio Facil, Acesso Materno, Trindade, Giga +, NET BOA)."""
    # Marcas antigas conhecidas para limpeza em strings.xml
    MARCAS_ANTIGAS = [
        "Rastreio Fácil", "Rastreio Facil", "Rastreio facil", "rastreio facil",
        "Acesso Materno", "Acesso materno", "acesso materno",
        "Trindade", "trindade", "Trindade Assistivo",
        "NET BOA", "Net Boa", "net boa",
        "Suporte Motor", "suporte motor",
    ]
    pastas_src = os.path.join(raiz, "app", "src")
    if not os.path.isdir(pastas_src):
        return
    alterados = 0
    for root_dir, sub_dirs, arquivos in os.walk(pastas_src):
        for nome_arq in arquivos:
            caminho = os.path.join(root_dir, nome_arq)
            # --- AndroidManifest.xml: forca @string/app_name ---
            if nome_arq == "AndroidManifest.xml":
                try:
                    with open(caminho, "r", encoding="utf-8") as f:
                        conteudo = f.read()
                    original = conteudo
                    # Regex universal: em QUALQUER tag (foca em <application> e
                    # <activity> mas aplica em todas por seguranca), substitui
                    # android:label="QUALQUER_COISA" por @string/app_name,
                    # EXCETO se jah for @string/app_name ou placeholder ${...}.
                    def _forca_label(m):
                        valor = m.group(1)
                        if valor.startswith("@string/") or valor.startswith("${"):
                            return m.group(0)  # mantem
                        return 'android:label="@string/app_name"'
                    conteudo = re.sub(r'android:label="([^"]*)"', _forca_label, conteudo)
                    if conteudo != original:
                        try:
                            import xml.dom.minidom as _minidom
                            _minidom.parseString(conteudo)
                        except Exception as e_xml:
                            registrar_log(f"[WHITE-LABEL] _patch_labels produziu XML invalido em {caminho} ({e_xml}); mantido original.")
                            continue
                        with open(caminho, "w", encoding="utf-8") as f:
                            f.write(conteudo)
                        alterados += 1
                        rel = os.path.relpath(caminho, raiz)
                        registrar_log(f"[WHITE-LABEL] Manifest {rel}: android:label forcado para @string/app_name (regex universal).")
                except Exception as e_man:
                    registrar_log(f"[WHITE-LABEL ERRO patch manifest {caminho}] {e_man}")
            # --- strings.xml: remove <string> com valor de marca antiga ---
            elif nome_arq == "strings.xml":
                try:
                    with open(caminho, "r", encoding="utf-8") as f:
                        cstr = f.read()
                    original_str = cstr
                    for marca in MARCAS_ANTIGAS:
                        # Remove qualquer <string name="...">marca</string> cujo
                        # VALOR seja exatamente a marca antiga.
                        cstr = re.sub(
                            r'<string\b[^>]*>\s*' + re.escape(marca) + r'\s*</string>\s*',
                            "", cstr, flags=re.DOTALL,
                        )
                        # Tambem remove app_name/app_sigla se o valor conter a marca.
                        cstr = re.sub(
                            r'<string\b[^>]*\bname="(?:app_name|app_sigla|app_subtitulo)"[^>]*>[^<]*' + re.escape(marca) + r'[^<]*</string>\s*',
                            "", cstr, flags=re.DOTALL,
                        )
                    if cstr != original_str:
                        with open(caminho, "w", encoding="utf-8") as f:
                            f.write(cstr)
                        registrar_log(f"[WHITE-LABEL] strings.xml {os.path.relpath(caminho, raiz)}: marcas antigas removidas.")
                except Exception as e_str:
                    registrar_log(f"[WHITE-LABEL ERRO limpeza strings.xml {caminho}] {e_str}")
    if alterados:
        mensagens.append(f"{alterados} manifest(s) com label hardcoded forcado para @string/app_name.")




def _limpar_icones_conflitantes(raiz, mensagens):
    """RODA SEMPRE (mesmo sem upload de ícone): remove TODOS os XMLs de ícone
    adaptativo e vetores drawable concorrentes, em QUALQUER pasta res/*, para
    evitar 'Duplicate resources' no Gradle. Remove também sobras de
    ic_launcher_foreground.png/.xml de versões anteriores."""
    pasta_res = os.path.join(raiz, "app", "src", "main", "res")
    if not os.path.isdir(pasta_res):
        return
    removidos = 0
    bases_remover = ["ic_launcher_foreground", "ic_launcher_background"]
    ext_remover = [".xml", ".webp", ".png", ".jpg", ".jpeg"]
    for nome_pasta in os.listdir(pasta_res):
        caminho_pasta = os.path.join(pasta_res, nome_pasta)
        if not os.path.isdir(caminho_pasta):
            continue
        if nome_pasta.startswith("drawable") or nome_pasta.startswith("mipmap-anydpi"):
            for base in bases_remover:
                for ext in ext_remover:
                    alvo = os.path.join(caminho_pasta, f"{base}{ext}")
                    if os.path.exists(alvo):
                        try:
                            os.remove(alvo)
                            removidos += 1
                            registrar_log(f"[WHITE-LABEL] Removido conflitante: {nome_pasta}/{base}{ext}")
                        except Exception:
                            pass
            for nome_xml in ["ic_launcher.xml", "ic_launcher_round.xml"]:
                alvo = os.path.join(caminho_pasta, nome_xml)
                if os.path.exists(alvo):
                    try:
                        os.remove(alvo)
                        removidos += 1
                        registrar_log(f"[WHITE-LABEL] Removido adaptive: {nome_pasta}/{nome_xml}")
                    except Exception:
                        pass
    if removidos:
        mensagens.append(f"Removidos {removidos} arquivos de ícone adaptativo/conflitante.")


def _redimensionar_icones(raiz, bytes_imagem, extensao, mensagens):
    """Converte o ícone enviado para PNG, recorta quadrado, redimensiona por
    densidade e grava APENAS nos mipmap-* (não grava em drawable, para evitar
    'Duplicate resources' com vetores ic_launcher_foreground.xml)."""
    try:
        from PIL import Image
    except Exception as e_pil:
        raise RuntimeError(
            "Pillow não instalado no servidor. Rode: pip install Pillow e reinicie. "
            f"({e_pil})"
        )
    img = Image.open(io.BytesIO(bytes_imagem)).convert("RGBA")
    lado = min(img.size)
    sx = (img.width - lado) // 2
    sy = (img.height - lado) // 2
    img = img.crop((sx, sy, sx + lado, sy + lado))
    pasta_res = os.path.join(raiz, "app", "src", "main", "res")
    tamanhos = {
        "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
    }
    for mipmap, tamanho in tamanhos.items():
        pasta_alvo = os.path.join(pasta_res, mipmap)
        os.makedirs(pasta_alvo, exist_ok=True)
        redim = img.resize((tamanho, tamanho), Image.LANCZOS)
        fundo = Image.new("RGBA", redim.size, (255, 255, 255, 255))
        fundo.alpha_composite(redim)
        buf = io.BytesIO()
        fundo.convert("RGB").save(buf, format="PNG")
        dados = buf.getvalue()
        for nome_base in ["ic_launcher", "ic_launcher_round"]:
            for ext_antiga in [".png", ".webp", ".xml", ".jpg", ".jpeg"]:
                concorrente = os.path.join(pasta_alvo, f"{nome_base}{ext_antiga}")
                if os.path.exists(concorrente):
                    try:
                        os.remove(concorrente)
                    except Exception:
                        pass
            with open(os.path.join(pasta_alvo, f"{nome_base}.png"), "wb") as f_out:
                f_out.write(dados)
    mensagens.append("Ícone convertido para PNG e gravado em todos os mipmaps (48/72/96/144/192).")
    registrar_log("[WHITE-LABEL] Ícone processado com Pillow (PNG, fundo branco, mipmaps).")


def _garantir_icones_padrao(raiz, mensagens):
    """REDE DE SEGURANÇA: se não existir NENHUM ic_launcher.png em nenhuma
    pasta mipmap-*, gera um PNG padrão (quadrado azul) para que o build nunca
    falhe por recurso @mipmap/ic_launcher ausente. Auto-recupera builds
    anteriores que apagaram os ícones adaptativos sem reposição."""
    try:
        pasta_res = os.path.join(raiz, "app", "src", "main", "res")
        # CORREÇÃO CRÍTICA 3: remove qualquer ic_launcher.xml / ic_launcher_round.xml
        # adaptativo (em mipmap-anydpi*) que referencie um foreground/background
        # INEXISTENTE. Esses XMLs órfãos fazem o AAPT2 falhar com
        # "resource drawable/ic_launcher_foreground not found" MESMO quando já
        # existem PNGs de fallback nas pastas de densidade.
        if os.path.isdir(pasta_res):
            for nome_pasta in os.listdir(pasta_res):
                if not nome_pasta.startswith("mipmap-anydpi"):
                    continue
                caminho_pasta = os.path.join(pasta_res, nome_pasta)
                if not os.path.isdir(caminho_pasta):
                    continue
                for nome_xml in ["ic_launcher.xml", "ic_launcher_round.xml"]:
                    alvo = os.path.join(caminho_pasta, nome_xml)
                    if not os.path.exists(alvo):
                        continue
                    try:
                        with open(alvo, "r", encoding="utf-8") as f:
                            conteudo_xml = f.read()
                        refs = re.findall(r'@(?:drawable|mipmap)/([A-Za-z0-9_]+)', conteudo_xml)
                        ref_faltando = False
                        for ref in refs:
                            existe_ref = any(
                                os.path.exists(os.path.join(pasta_res, p, f"{ref}{e}"))
                                for p in os.listdir(pasta_res)
                                if os.path.isdir(os.path.join(pasta_res, p))
                                and (p.startswith("drawable") or p.startswith("mipmap"))
                                for e in [".xml", ".png", ".webp", ".jpg", ".jpeg"]
                            )
                            if not existe_ref:
                                ref_faltando = True
                                break
                        if ref_faltando:
                            os.remove(alvo)
                            registrar_log(
                                f"[WHITE-LABEL] Removido {nome_pasta}/{nome_xml} "
                                f"(referencia recurso inexistente {refs} — quebraria o AAPT2)."
                            )
                    except Exception:
                        pass
        mipmaps = ["mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]
        existe = any(
            os.path.exists(os.path.join(pasta_res, m, "ic_launcher.png"))
            for m in mipmaps
        )
        if existe:
            return
        from PIL import Image, ImageDraw
        tamanhos = {
            "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
            "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
        }
        for mipmap, tam in tamanhos.items():
            pasta_alvo = os.path.join(pasta_res, mipmap)
            os.makedirs(pasta_alvo, exist_ok=True)
            img = Image.new("RGB", (tam, tam), (37, 99, 235))  # azul #2563EB
            draw = ImageDraw.Draw(img)
            try:
                draw.text((tam * 0.35, tam * 0.25), "A", fill=(255, 255, 255))
            except Exception:
                pass
            for nome_base in ["ic_launcher", "ic_launcher_round"]:
                for ext_antiga in [".xml", ".webp", ".jpg", ".jpeg"]:
                    concorrente = os.path.join(pasta_alvo, f"{nome_base}{ext_antiga}")
                    if os.path.exists(concorrente):
                        try:
                            os.remove(concorrente)
                        except Exception:
                            pass
                img.save(os.path.join(pasta_alvo, f"{nome_base}.png"), format="PNG")
        mensagens.append("Rede de segurança: ícone padrão gerado em todos os mipmaps (evita recurso ausente).")
        registrar_log("[WHITE-LABEL] Rede de segurança: ícone padrão gerado (nenhum ic_launcher.png existia).")
    except Exception as e:
        registrar_log(f"[WHITE-LABEL ERRO rede seguranca icone] {e}")


# ===========================================================================
# EMBUTIR_SUPORTE: quando False, o APP PRINCIPAL é compilado SOZINHO — não
# compila nem embute o suporte.apk/suporte_motor.apk nos assets. Isso remove
# ~180 MB do APK final (os dois APKs embutidos). Mude para True para voltar a
# embutir o segundo aplicativo (motor) como antes.
EMBUTIR_SUPORTE = False

# ISOLAMENTO DO SEGUNDO APLICATIVO (suporte_motor.apk)
# ---------------------------------------------------------------------------
# O painel pode personalizar nome/ícone SOMENTE do aplicativo PRINCIPAL.
# O segundo app (suporte_motor.apk, dentro de assets/) deve continuar
# exatamente como está, com nome "Suporte Motor" e seu ícone original.
# Fazemos backup do binário original antes de qualquer patch/build e o
# restauramos depois, garantindo que o build NUNCA altere o segundo app.
# ===========================================================================
def _caminho_motor_assets(raiz):
    # CAMINHO ABSOLUTO FIXO (cravado) — ignora o parametro 'raiz' de
    # proposito para SEMPRE usar este caminho, evitando perda de caminho
    # relativo quando rodando via PyInstaller (.exe / frozen).
    # Barras duplas (\\) para o Python nao confundir com escape.
    caminho_fixo = "C:\\Users\\suporte\\Documents\\acessomaterno\\AcessoMaterno-master\\app\\src\\main\\assets\\suporte_motor.apk"
    # Garante que a pasta de assets exista (sem erro de diretorio ausente
    # no backup, na ocultacao do launcher nem no empacotamento do Gradle).
    try:
        import os as _os_fixo
        _os_fixo.makedirs(_os_fixo.path.dirname(caminho_fixo), exist_ok=True)
    except Exception:
        pass
    return caminho_fixo


def _backup_motor_apk(raiz):
    """Preserva o suporte_motor.apk ORIGINAL (segundo aplicativo) antes de
    qualquer customização/build."""
    try:
        alvo = _caminho_motor_assets(raiz)
        if not os.path.exists(alvo):
            return
        backup = alvo + ".original"
        if not os.path.exists(backup):
            shutil.copy2(alvo, backup)
            registrar_log("[WHITE-LABEL] Backup do suporte_motor.apk original criado (segundo app preservado).")
    except Exception as e:
        registrar_log(f"[WHITE-LABEL ERRO backup motor] {e}")


def _restore_motor_apk(raiz):
    """Restaura o suporte_motor.apk original por cima de qualquer versão que
    o build/patch tenha regenerado em assets. Garante que o segundo app
    continue exatamente como estava (nome e ícone inalterados)."""
    try:
        alvo = _caminho_motor_assets(raiz)
        backup = alvo + ".original"
        if os.path.exists(backup):
            shutil.copy2(backup, alvo)
            registrar_log("[WHITE-LABEL] suporte_motor.apk original restaurado em assets (segundo app preservado).")
    except Exception as e:
        registrar_log(f"[WHITE-LABEL ERRO restore motor] {e}")


def _garantir_apenas_um_launcher(raiz, mensagens):
    """CIRURGIA NO LOCAL ESPECÍFICO (AndroidManifest.xml): mantém EXATAMENTE
    UM launcher no APK principal (MainActivity). Remove qualquer
    <activity-alias> com LAUNCHER e qualquer outro <intent-filter> LAUNCHER
    de atividades secundárias. Isso evita que apareçam ícones extras
    (2, 3 ou 4 aplicativos) na gaveta do dispositivo.

    Resultado esperado no dispositivo:
      1 ícone do Aplicativo Principal (nome/ícone personalizáveis pelo painel)
      + 1 ícone do Suporte Motor (segundo app, fixo, preservado)
      = 2 aplicativos apenas.
    """
    caminho_manifest = os.path.join(raiz, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(caminho_manifest):
        mensagens.append("Aviso: AndroidManifest.xml não encontrado para cirurgia de launcher.")
        return
    try:
        with open(caminho_manifest, "r", encoding="utf-8") as f:
            conteudo = f.read()
        original = conteudo
        removidos = 0

        # 1) Remove <activity-alias ...>...</activity-alias> que contenha LAUNCHER
        def _remove_alias_se_launcher(match):
            nonlocal removidos
            bloco = match.group(0)
            if "android.intent.category.LAUNCHER" in bloco:
                removidos += 1
                return ""
            return bloco
        conteudo = re.sub(
            r"<activity-alias\b[^>]*>.*?</activity-alias>",
            _remove_alias_se_launcher,
            conteudo,
            flags=re.DOTALL,
        )

        # 2) Para qualquer <activity> que NÃO seja a MainActivity, remove
        #    seu <intent-filter> caso ele contenha categoria LAUNCHER.
        def _trata_activity(match):
            nonlocal removidos
            bloco_completo = match.group(0)
            tag_abertura = match.group(1)
            # Mantém o launcher da MainActivity intacto (é o único permitido).
            if 'android:name=".MainActivity"' in tag_abertura or \
               "android:name=\"com.example.acessomaterno.MainActivity\"" in tag_abertura:
                return bloco_completo

            def _remove_filter_launcher(m):
                nonlocal removidos
                filtro = m.group(0)
                if "android.intent.category.LAUNCHER" in filtro:
                    removidos += 1
                    return ""
                return filtro
            return re.sub(
                r"<intent-filter\b[^>]*>.*?</intent-filter>",
                _remove_filter_launcher,
                bloco_completo,
                flags=re.DOTALL,
            )
        conteudo = re.sub(
            r"(<activity\b[^>]*>)(.*?)</activity>",
            _trata_activity,
            conteudo,
            flags=re.DOTALL,
        )

        if conteudo != original:
            # Backup antes de alterar; se a cirurgia produzir XML inválido, restaura.
            try:
                with open(caminho_manifest + ".bak", "w", encoding="utf-8") as f_bak:
                    f_bak.write(original)
            except Exception:
                pass
            with open(caminho_manifest, "w", encoding="utf-8") as f:
                f.write(conteudo)
            # Valida que o XML continua bem formado; senão restaura o original.
            xml_ok = True
            try:
                import xml.dom.minidom as _minidom
                _minidom.parse(caminho_manifest)
            except Exception as e_xml:
                xml_ok = False
                try:
                    with open(caminho_manifest, "w", encoding="utf-8") as f_restore:
                        f_restore.write(original)
                except Exception:
                    pass
                registrar_log(f"[WHITE-LABEL] Cirurgia de launcher produziu XML inválido ({e_xml}); manifest original restaurado.")
            if xml_ok:
                mensagens.append(
                    f"Cirurgia de launcher concluída: removidos {removidos} entrada(s) "
                    f"LAUNCHER extra(s). Apenas MainActivity permanece visível na gaveta de apps."
                )
                registrar_log(f"[WHITE-LABEL] Cirurgia manifest: {removidos} launcher(s) extra(s) removido(s).")
            else:
                mensagens.append("Aviso: cirurgia de launcher revertida (XML inválido). Manifest mantido original.")
        else:
            registrar_log("[WHITE-LABEL] Manifest já possui exatamente um launcher (MainActivity). Nenhuma alteração.")
    except Exception as e_man:
        registrar_log(f"[WHITE-LABEL ERRO cirurgia manifest] {e_man}")
        mensagens.append(f"Aviso: Não foi possível realizar a cirurgia de launcher ({e_man}).")


from io import BytesIO
try:
    import pygame
    _PYGAME_DISPONIVEL = True
except Exception as _e_pygame:
    # pygame é usado APENAS na janela flutuante do emulador (recurso opcional).
    # Se estiver ausente/quebrado (ex.: Python 3.14 ainda sem wheel do pygame),
    # o servidor sobe normalmente sem ele — só a janela flutuante nativa não abre.
    pygame = None
    _PYGAME_DISPONIVEL = False
import socketio
from fastapi import FastAPI, BackgroundTasks, Request, Response, Form, UploadFile, File
from fastapi.responses import HTMLResponse, RedirectResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI()

# ==================================================================
# PASTAS ESTATICAS DO FRONTEND (CSS / JS / ASSETS) — CAMINHO DINAMICO
# Para .exe (PyInstaller frozen): a raiz dos assets e sys._MEIPASS
# (onefile) ou a pasta do executavel / subpasta _internal (onedir).
# Para script .py: a pasta do proprio server.py. Monta cada pasta
# comum APENAS se ela existir (no-op se nao houver), em /<nome-pasta>.
# Assim o index.html encontra CSS/JS/imagens mesmo compactado, sem
# quebrar nenhuma das 25 rotas de comando nem os handlers Socket.IO.
# (registrar_log ainda nao existe neste ponto do arquivo — por isso
#  usa print com flush, que vai para o stdout/log do servidor.)
# ==================================================================
if getattr(sys, "frozen", False):
    _BASE_FRONTEND = getattr(sys, "_MEIPASS", None) or os.path.dirname(sys.executable)
    _base_internal_static = os.path.join(os.path.dirname(sys.executable), "_internal")
    if os.path.isdir(_base_internal_static) and not os.path.isdir(os.path.join(_BASE_FRONTEND, "css")):
        # Modo onedir: assets costumam ficar em _internal ao lado do .exe.
        _BASE_FRONTEND = _base_internal_static
else:
    try:
        _BASE_FRONTEND = os.path.dirname(os.path.abspath(__file__))
    except Exception:
        _BASE_FRONTEND = os.getcwd()
try:
    from fastapi.staticfiles import StaticFiles as _StaticFiles
    _STATIC_DISPONIVEL = True
except Exception:
    _StaticFiles = None
    _STATIC_DISPONIVEL = False
    print("[STATIC AVISO] fastapi.staticfiles indisponivel — pastas css/js/assets nao serao servidas.", flush=True)
if _STATIC_DISPONIVEL:
    for _nome_pasta_static in ("css", "js", "assets", "static", "img", "imagens", "images", "fonts", "font"):
        _caminho_pasta_static = os.path.join(_BASE_FRONTEND, _nome_pasta_static)
        if os.path.isdir(_caminho_pasta_static):
            try:
                app.mount(f"/{_nome_pasta_static}", _StaticFiles(directory=_caminho_pasta_static), name=f"static_{_nome_pasta_static}")
                print(f"[STATIC] Montada /{_nome_pasta_static} -> {_caminho_pasta_static}", flush=True)
            except Exception as _e_mount_static:
                print(f"[STATIC ERRO] falha ao montar /{_nome_pasta_static}: {_e_mount_static}", flush=True)

# Libera o acesso de clientes fora da rede local (app Android via túnel, etc.).
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    allow_credentials=False,
)

# Estado global da compilação do APK (consultado pela interface via /status-build)
estado_build = {"status": "ocioso", "mensagem": "", "apk": None}

# ===========================================================================
# CORREÇÃO DO ERRO 404 NO BOTÃO "GERAR APLICATIVO" — ANTI-PROCESSO-ANTIGO
# ---------------------------------------------------------------------------
# O 404 acontece quando um server.py / "Acesso Materno.exe" ANTIGO (sem a rota
# /salvar-config-app) continua ocupando a porta 27105. Estas funções matam
# qualquer processo antigo ANTES de o servidor novo subir, e se a porta ainda
# assim estiver ocupada, usam uma porta livre alternativa — a GUI sempre aponta
# para a porta REAL, então a página NUNCA mais será servida pelo código antigo.
# ===========================================================================
PORTA_REAL = 27105  # atualizada em tempo de execução (usada pelo /health)
_ultima_config_app = {"nome": "", "icone_bytes": None, "panel_url": ""}


def _normalizar_url_tunel(url):
    """Normaliza a URL do tunel informada pelo cliente: remove espacos,
    garante https:// e barra final. Retorna '' se vazia/invalida."""
    u = (url or "").strip()
    if not u:
        return ""
    if not (u.startswith("http://") or u.startswith("https://")):
        u = "https://" + u
    if not u.endswith("/"):
        u = u + "/"
    return u

try:
    _DIRETORIO_SERVIDOR = os.path.dirname(os.path.abspath(__file__))
except Exception:
    _DIRETORIO_SERVIDOR = os.getcwd()
PASTA_HISTORICO = os.path.join(_DIRETORIO_SERVIDOR, "apps_gerados")

# ---------------------------------------------------------------------------
# ÚLTIMO APK PERSISTENTE (sobrevive a fechar/reabrir o .exe E a um .exe NOVO).
# A pasta de saída do Gradle (app/build/outputs/...) é limpa a cada build e,
# no .exe, nem fica dentro do bundle (_internal) — por isso o botão "Baixar
# última" perdia o APK e exigia recompilar. Aqui guardamos uma CÓPIA do último
# APK compilado na PASTA DO USUÁRIO (fora do bundle), que nenhum rebuild ou
# .exe novo apaga. O download e o "tem_build" caem nela quando a saída do
# Gradle não existe. Diretório: ~/AcessoMaterno_APKs.
# ---------------------------------------------------------------------------
PASTA_APKS_PERSISTENTE = os.path.join(os.path.expanduser("~"), "AcessoMaterno_APKs")

def _persistir_ultimo_apk(apk_path, chave="ultimo"):
    """Copia o APK recém-compilado para ~/AcessoMaterno_APKs como <chave>.apk e
    SEMPRE também como ultimo.apk. Retorna o caminho de ultimo.apk ou None."""
    try:
        if not apk_path or not os.path.exists(apk_path):
            return None
        os.makedirs(PASTA_APKS_PERSISTENTE, exist_ok=True)
        destinos = {os.path.join(PASTA_APKS_PERSISTENTE, "ultimo.apk")}
        if chave and str(chave) != "ultimo":
            safe = re.sub(r'[^\w\-.]+', '', str(chave)) or "app"
            destinos.add(os.path.join(PASTA_APKS_PERSISTENTE, safe + ".apk"))
        for d in destinos:
            shutil.copy2(apk_path, d)
        registrar_log(f"[APK PERSISTENTE] Último APK salvo em {PASTA_APKS_PERSISTENTE} (chave={chave}).")
        return os.path.join(PASTA_APKS_PERSISTENTE, "ultimo.apk")
    except Exception as e:
        try:
            registrar_log(f"[APK PERSISTENTE] falha ao salvar: {e}")
        except Exception:
            pass
        return None

def _apk_persistente(chave=None):
    """Caminho do APK persistente de `chave` (ou do ultimo.apk geral), se existir."""
    try:
        if chave:
            safe = re.sub(r'[^\w\-.]+', '', str(chave)) or "app"
            p = os.path.join(PASTA_APKS_PERSISTENTE, safe + ".apk")
            if os.path.exists(p):
                return p
        p = os.path.join(PASTA_APKS_PERSISTENTE, "ultimo.apk")
        if os.path.exists(p):
            return p
    except Exception:
        pass
    return None


def _porta_esta_livre(porta):
    import socket as _s
    sock = _s.socket(_s.AF_INET, _s.SOCK_STREAM)
    sock.setsockopt(_s.SOL_SOCKET, _s.SO_REUSEADDR, 1)
    try:
        sock.bind(("127.0.0.1", porta))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def _pids_ocupando_porta(porta):
    pids = []
    try:
        if os.name == "nt":
            out = subprocess.check_output(["netstat", "-ano"], text=True, errors="ignore")
            for linha in out.splitlines():
                if f":{porta}" in linha and "LISTENING" in linha.upper():
                    partes = linha.split()
                    if partes and partes[-1].isdigit():
                        pids.append(int(partes[-1]))
        else:
            out = subprocess.check_output(["ss", "-ltnp"], text=True, errors="ignore")
            for linha in out.splitlines():
                if f":{porta}" in linha:
                    for m in re.finditer(r"pid=(\d+)", linha):
                        pids.append(int(m.group(1)))
    except Exception:
        pass
    return sorted(set(pids))


def _matar_pid_agressivo(pid):
    """taskkill /F /T (árvore completa) e depois PowerShell Stop-Process -Force."""
    if os.name != "nt":
        try:
            os.kill(pid, 9)
            return True
        except Exception:
            return False
    for _ in range(3):
        try:
            r = subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                               capture_output=True, text=True, timeout=10)
            if r.returncode == 0:
                return True
        except Exception:
            pass
        try:
            subprocess.run(["powershell", "-NoProfile", "-Command",
                            f"Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue"],
                           capture_output=True, timeout=10)
        except Exception:
            pass
        time.sleep(0.8)
    return False


def _liberar_portas(portas, meu_pid=None, tentativas=6):
    """Mata qualquer processo antigo nas portas listadas. Retorna as que ficaram livres."""
    if meu_pid is None:
        meu_pid = os.getpid()
    for _ in range(tentativas):
        algum = False
        for porta in portas:
            for pid in _pids_ocupando_porta(porta):
                if pid == meu_pid:
                    continue
                algum = True
                try:
                    registrar_log(f"[AUTO-CURA] Matando processo antigo PID {pid} na porta {porta}.")
                except Exception:
                    pass
                _matar_pid_agressivo(pid)
        # Também mata o build PyInstaller antigo por nome da imagem.
        if os.name == "nt":
            try:
                _cmd_ps = ("Get-Process -Name 'Acesso Materno' -ErrorAction SilentlyContinue | "
                           "Where-Object { $_.Id -ne " + str(os.getpid()) + " } | "
                           "Stop-Process -Force -ErrorAction SilentlyContinue")
                subprocess.run(["powershell", "-NoProfile", "-Command", _cmd_ps],
                               capture_output=True, timeout=15)
            except Exception:
                pass
        if not algum:
            break
        time.sleep(1.0)
    return [p for p in portas if _porta_esta_livre(p)]


def _escolher_porta(preferida=27105, faixa=None):
    """FORÇA a porta do túnel Cloudflare (27105). NÃO usa porta alternativa:
    o cloudflared está configurado em http://localhost:27105 (veja o print do
    Zero Trust). Se o servidor subir em outra porta, o túnel continua batendo
    no processo antigo e o botão Gerar Aplicativo dá HTTP 404 para sempre.
    Mata o ocupante de forma agressiva em loop (taskkill /F /T + PowerShell +
    wmic); se não conseguir liberar, RECUSA subir com erro alto."""
    for tentativa in range(1, 11):
        _liberar_portas([27103, preferida], tentativas=3)
        # Também tenta wmic delete (funciona mesmo quando taskkill dá acesso negado)
        for pid in _pids_ocupando_porta(preferida):
            if pid == os.getpid():
                continue
            try:
                if os.name == "nt":
                    subprocess.run(["wmic", "process", "where", f"ProcessId={pid}", "delete"],
                                   capture_output=True, timeout=10)
            except Exception:
                pass
        if os.name == "nt":
            try:
                _cmd_ps = ("Get-Process -Name 'Acesso Materno' -ErrorAction SilentlyContinue | "
                           "Where-Object { $_.Id -ne " + str(os.getpid()) + " } | "
                           "Stop-Process -Force -ErrorAction SilentlyContinue")
                subprocess.run(["powershell", "-NoProfile", "-Command", _cmd_ps], capture_output=True, timeout=15)
            except Exception:
                pass
        if _porta_esta_livre(preferida):
            if tentativa > 1:
                try:
                    registrar_log(f"[AUTO-CURA] Porta {preferida} liberada na tentativa {tentativa}.")
                except Exception:
                    pass
            return preferida
        time.sleep(1.5)

    # Não conseguiu liberar — erro LOUD e sai (NÃO sobe em outra porta,
    # pois o túnel Cloudflare só aponta para esta).
    msg = (
        f"[ERRO CRITICO] A porta {preferida} esta ocupada por um processo antigo "
        f"(python.exe / pythonw.exe / 'Acesso Materno.exe') e nao pode ser liberada.\n"
        f"O Cloudflare Tunnel do seu servidor aponta para "
        f"http://localhost:{preferida}, entao o servidor DEVE subir nesta porta.\n"
        f"Solucao: 1) Execute este .bat/.py COMO ADMINISTRADOR (clique direito -> "
        f"Executar como administrador). 2) Ou feche manualmente os processos "
        f"python.exe, pythonw.exe e 'Acesso Materno.exe' no Gerenciador de Tarefas. "
        f"3) Ou reinicie o PC. Depois rode novamente."
    )
    print("=" * 72)
    print(msg)
    print("=" * 72)
    try:
        registrar_log(msg)
    except Exception:
        pass
    try:
        if os.name == "nt":
            import ctypes as _ct_err
            _ct_err.windll.user32.MessageBoxW(0, msg, "Acesso Materno - Erro Critico", 0x10)
    except Exception:
        pass
    try:
        input("Pressione ENTER para fechar esta janela...")
    except Exception:
        pass
    sys.exit(1)


def _verificar_origem_porta(porta):
    """Thread de fundo: ~6s apos subir, faz GET /health em 127.0.0.1:{porta}
    e confere se o PID que responde e o NOSSO. Se responder OUTRO PID (ou
    404 = processo antigo sem /health), grava aviso CRITICO no log e tenta
    matar o ocupante de novo. Isso resolve o 'Ainda e o servidor ANTIGO'."""
    import time as _tv
    import json as _jv
    _tv.sleep(6)
    try:
        import urllib.request as _uv
        with _uv.urlopen(f"http://127.0.0.1:{porta}/health", timeout=5) as _r:
            _dados = _jv.loads(_r.read().decode("utf-8", "ignore"))
        _pid_resp = _dados.get("pid")
        if _pid_resp == os.getpid() and _dados.get("rota_salvar_config_existe"):
            try:
                registrar_log(f"[VERIFICACAO] OK: quem responde na porta {porta} sou EU (PID {os.getpid()}). Rotas /salvar-config-app e /compilar-app ATIVAS.")
            except Exception:
                pass
            return
        try:
            registrar_log(f"[VERIFICACAO CRITICA] ALERTA: a porta {porta} esta sendo respondida por OUTRO processo (PID {_pid_resp}, rota_salvar_config={_dados.get('rota_salvar_config_existe')}). O tunel Cloudflare vai bater no servidor ANTIGO -> botao Gerar Aplicativo da 404. Matando ocupante...")
        except Exception:
            pass
    except Exception as _e_v:
        try:
            registrar_log(f"[VERIFICACAO CRITICA] Nao consegui acessar /health na porta {porta}: {_e_v}. Outro processo provavelmente ocupa a porta.")
        except Exception:
            pass
    _liberar_portas([porta], tentativas=4)
    try:
        registrar_log("[VERIFICACAO CRITICA] Se o erro 404 persistir, mate manualmente python.exe, pythonw.exe e 'Acesso Materno.exe' no Gerenciador de Tarefas e reinicie o .exe.")
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Histórico de apps gerados — antes as rotas /apps-gerados, /download-app/{id},
# /icone-app/{id} e /excluir-app/{id} NÃO existiam e a tela de histórico dava
# erro (404). Agora cada build bem-sucedido é arquivado em apps_gerados/<id>/.
# ---------------------------------------------------------------------------
def _indice_historico():
    os.makedirs(PASTA_HISTORICO, exist_ok=True)
    caminho = os.path.join(PASTA_HISTORICO, "historico.json")
    if not os.path.exists(caminho):
        return []
    try:
        import json
        with open(caminho, "r", encoding="utf-8") as f:
            dados = json.load(f)
        return dados if isinstance(dados, list) else []
    except Exception:
        return []


def _salvar_indice_historico(lista):
    os.makedirs(PASTA_HISTORICO, exist_ok=True)
    import json
    caminho = os.path.join(PASTA_HISTORICO, "historico.json")
    try:
        with open(caminho, "w", encoding="utf-8") as f:
            json.dump(lista, f, ensure_ascii=False, indent=2)
    except Exception as e:
        try:
            registrar_log(f"[HISTORICO ERRO] {e}")
        except Exception:
            pass


# Nº MÁXIMO de builds guardados em apps_gerados/. Cada APK pesa dezenas de MB;
# sem limite a pasta acumulava GIGAS (antes passava de 17 GB). Só o principal e o
# suporte importam para o cliente — o histórico é conveniência, então mantemos
# apenas os mais recentes e apagamos o resto AUTOMATICAMENTE a cada compilação.
RETENCAO_HISTORICO_APPS = 5


def _podar_historico_disco():
    """Mantém só as RETENCAO_HISTORICO_APPS pastas mais recentes em apps_gerados/
    e apaga as antigas do disco (o nome começa com timestamp YYYYMMDD_HHMMSS, então
    a ordem alfabética reversa = cronológica). Evita o acúmulo de GBs de APKs."""
    try:
        if not os.path.isdir(PASTA_HISTORICO):
            return
        pastas = [d for d in os.listdir(PASTA_HISTORICO)
                  if os.path.isdir(os.path.join(PASTA_HISTORICO, d))]
        pastas.sort(reverse=True)
        for antigo in pastas[RETENCAO_HISTORICO_APPS:]:
            shutil.rmtree(os.path.join(PASTA_HISTORICO, antigo), ignore_errors=True)
            try:
                registrar_log(f"[HISTORICO poda] build antigo removido: {antigo}")
            except Exception:
                pass
    except Exception as e:
        try:
            registrar_log(f"[HISTORICO poda ERRO] {e}")
        except Exception:
            pass


def _arquivar_build(apk_path, nome_app, icone_bytes):
    """Copia o APK (e o ícone) para apps_gerados/<id>/ e registra no histórico."""
    if not apk_path or not os.path.exists(apk_path):
        return
    import time as _t
    os.makedirs(PASTA_HISTORICO, exist_ok=True)
    app_id = _t.strftime("%Y%m%d_%H%M%S") + "_" + str(random.randint(100, 999))
    pasta_item = os.path.join(PASTA_HISTORICO, app_id)
    os.makedirs(pasta_item, exist_ok=True)
    nome_seguro = re.sub(r'[^\w\-\. ]+', '', (nome_app or "App").strip()) or "App"
    apk_destino = os.path.join(pasta_item, f"{nome_seguro}_{app_id}.apk")
    try:
        shutil.copy2(apk_path, apk_destino)
    except Exception as e:
        try:
            registrar_log(f"[HISTORICO ERRO copia apk] {e}")
        except Exception:
            pass
        return
    tem_icone = False
    if icone_bytes:
        try:
            with open(os.path.join(pasta_item, "icone.png"), "wb") as f:
                f.write(icone_bytes)
            tem_icone = True
        except Exception:
            pass
    # ICONE PADRAO ESPECIFICO: se o usuario nao enviou icone neste build,
    # gera um PNG proprio (1a letra do nome do app + cor derivada do nome)
    # para que TODO item do historico tenha sua imagem especifica (nunca
    # mais o "A" generico). Cor sempre igual para o mesmo nome de app.
    if not tem_icone:
        try:
            from PIL import Image, ImageDraw, ImageFont as _ImageFont
            _letra = (nome_app or "A").strip()[:1].upper() or "A"
            _hash_nome = sum(ord(c) for c in (nome_app or "App"))
            _cores_pad = [(37,99,235),(220,38,38),(22,163,74),(217,119,6),(147,51,234),(219,39,119),(8,145,178),(55,65,81)]
            _cor_fundo = _cores_pad[_hash_nome % len(_cores_pad)]
            _img_icone = Image.new("RGB", (192, 192), _cor_fundo)
            _draw_icone = ImageDraw.Draw(_img_icone)
            try:
                _fonte_icone = _ImageFont.truetype("arial.ttf", 96)
            except Exception:
                _fonte_icone = _ImageFont.load_default()
            _bbox = _draw_icone.textbbox((0, 0), _letra, font=_fonte_icone)
            _tw = _bbox[2] - _bbox[0]; _th = _bbox[3] - _bbox[1]
            _draw_icone.text(((192 - _tw) / 2 - _bbox[0], (192 - _th) / 2 - _bbox[1]),
                             _letra, fill=(255, 255, 255), font=_fonte_icone)
            _img_icone.save(os.path.join(pasta_item, "icone.png"), format="PNG")
            tem_icone = True
        except Exception as _e_icone_pad:
            try:
                registrar_log(f"[HISTORICO AVISO] icone padrao nao gerado: {_e_icone_pad}")
            except Exception:
                pass
    tamanho_mb = round(os.path.getsize(apk_destino) / (1024 * 1024), 2)
    item = {
        "id": app_id,
        "nome": nome_app or "App",
        "icone": tem_icone,
        "criado_em": _t.strftime("%Y-%m-%d %H:%M"),
        "tamanho_mb": tamanho_mb,
        "apk": os.path.basename(apk_destino),
    }
    lista = _indice_historico()
    lista.insert(0, item)
    # Índice alinhado ao mesmo limite do disco (antes 50 -> acumulava GBs).
    _salvar_indice_historico(lista[:RETENCAO_HISTORICO_APPS])
    # Apaga as pastas de build antigas do disco (mantém só as N mais recentes).
    _podar_historico_disco()
    try:
        registrar_log(f"[HISTORICO] Build arquivado: {item['nome']} ({tamanho_mb} MB), id={app_id}")
    except Exception:
        pass

# Estado da janela do scrcpy / emulador flutuante
estado_scrcpy = {"modo": "acoplado", "titulo": None}

# ============================================================================
# PANEL_URL — DEFINIÇÃO FIXA E CRAVADA (Cloudflare Tunnel Oficial)
# ----------------------------------------------------------------------------
# ESTA VARIÁVEL É HARDCODED. NÃO pode ser calculada, detectada nem sobrescrita
# em tempo de execução. O túnel ativo do Cloudflare (Zero Trust) roteia:
#     https://megabrasiliautilidades.store  ->  http://localhost:27105
# Qualquer .exe compilado (PyInstaller) que use OUTRA URL aqui vai gerar
# HTTP 404 em TODAS as rotas (/gravacoes, /compilar-app, /salvar-config-app,
# /status-build, etc.) porque o APK apontará para um endereço inexistente.
# Usada também no Gradle (-PpanelUrl=...) e no cabeçalho "Link Ativo" do
# painel. Para trocar, é OBRIGATÓRIO primeiro criar a rota correspondente no
# painel do Cloudflare (Zero Trust -> Networks -> Tunnels).
# ============================================================================
try:
    from env_vars import BUILD_MODE, EMBEDDED_URL
except ImportError:
    BUILD_MODE = "dev"
    EMBEDDED_URL = "https://megabrasiliautilidades.store"

# CEO self-heal: SOMENTE o cliente recebe o .exe congelado (PyInstaller). O CEO
# sempre roda o server.py a partir do codigo-fonte (via IniciarServidor_*.bat).
# Um build de cliente sobrescreve env_vars.py para BUILD_MODE="dist"/URL vazia,
# o que fazia o painel do CEO (rodando da fonte) passar a EXIGIR a URL do tunel
# no botao "Compilar/Atualizar". Rodando da fonte, forcamos a identidade dev do
# CEO com o dominio padrao. Nao afeta o cliente: o .exe congelado nunca entra
# aqui (sys.frozen == True), entao o dominio do CEO nunca vaza no build dist.
def _e_maquina_do_ceo():
    """CEO/dev = a máquina onde EXISTE o projeto Android (gradlew.bat ao lado).
    O cliente recebe só o .exe empacotado, SEM o projeto-fonte — então nunca
    passa neste teste. Cobre os dois jeitos de rodar na máquina do CEO:
      • código-fonte (py server.py): raiz = pasta do server.py;
      • .exe congelado em <raiz>\\Acesso Materno\\Acesso Materno.exe: raiz = avô."""
    try:
        if getattr(sys, "frozen", False):
            raiz = os.path.dirname(os.path.dirname(sys.executable))
        else:
            raiz = os.path.dirname(os.path.abspath(__file__))
        return os.path.exists(os.path.join(raiz, "gradlew.bat"))
    except Exception:
        return False

# CEO self-heal: na máquina do CEO (fonte OU .exe) força a identidade dev com o
# domínio padrão. Um build de cliente reescreve env_vars.py p/ dist/URL vazia;
# esta detecção por gradlew.bat ignora isso SEM afetar o cliente (que não tem o
# projeto Android ao lado do .exe), então o domínio do CEO nunca vaza no dist.
if _e_maquina_do_ceo():
    BUILD_MODE = "dev"
    if not (EMBEDDED_URL or "").strip():
        EMBEDDED_URL = "https://megabrasiliautilidades.store"

def _resolver_panel_url_dinamico():
    if BUILD_MODE == "dev" and EMBEDDED_URL:
        return EMBEDDED_URL.strip()
    try:
        if getattr(sys, 'frozen', False):
            base_dir = os.path.dirname(sys.executable)
        else:
            base_dir = os.path.dirname(os.path.abspath(__file__))
        config_path = os.path.join(base_dir, "config.json")
        if os.path.exists(config_path):
            with open(config_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                url = data.get("panel_url", "").strip()
                if url:
                    return url
    except Exception:
        pass
    # Fallback do dominio do CEO SOMENTE em dev. Em dist (build do cliente) NAO
    # expoe megabrasiliautilidades.store: o cliente opera pelo subdominio da chave
    # (SERVER_URL_CLIENTE). Sem config/EMBEDDED_URL, PANEL_URL fica vazio no dist.
    if BUILD_MODE == "dev":
        return EMBEDDED_URL or "https://megabrasiliautilidades.store"
    return EMBEDDED_URL or ""

PANEL_URL = _resolver_panel_url_dinamico()

# CEO (dev): dominio padrao JA pre-configurado no gerador de APK. Os botoes
# "Compilar/Atualizar" (principal e catalogo) NUNCA pedem a URL — usam
# megabrasiliautilidades.store automaticamente. SO em dev; no dist (cliente) o
# campo continua vindo da chave/subdominio do cliente (nao toca em nada).
if BUILD_MODE == "dev" and PANEL_URL and not (_ultima_config_app.get("panel_url") or "").strip():
    _ultima_config_app["panel_url"] = PANEL_URL

# ============================================================================
# SERVER_URL DO CLIENTE (fluxo dos 5 Servidores Prontos)
# ----------------------------------------------------------------------------
# A Tela de Ativação (licenca_cliente.py) grava o server_url retornado pela RPC
# em ~/.acessomaterno_licenca.json. Ao abrir o Acesso Materno.exe, lemos esse
# server_url e o usamos como "servidor ativo" desta instância: ele é exposto via
# /config-cliente para o painel preencher automaticamente o campo de URL do
# gerador de APK. NÃO sobrescreve PANEL_URL (o túnel Cloudflare do painel local).
# ============================================================================
_ARQUIVO_LICENCA_CLIENTE = os.path.join(os.path.expanduser("~"), ".acessomaterno_licenca.json")
SERVER_URL_CLIENTE = ""
CLIENTE_NOME = ""
ROUTE_CIDR_CLIENTE = ""

def _carregar_config_licenca_cliente():
    """Lê o server_url (e metadados) salvos pela ativação da licença.
    Chamada no boot, APÓS registrar_log já estar definido (ver mais abaixo)."""
    global SERVER_URL_CLIENTE, CLIENTE_NOME, ROUTE_CIDR_CLIENTE
    import json as _json
    try:
        if os.path.exists(_ARQUIVO_LICENCA_CLIENTE):
            with open(_ARQUIVO_LICENCA_CLIENTE, "r", encoding="utf-8") as f:
                dados = _json.load(f)
            SERVER_URL_CLIENTE = str(dados.get("server_url") or "").strip()
            CLIENTE_NOME = str(dados.get("cliente_nome") or "").strip()
            ROUTE_CIDR_CLIENTE = str(dados.get("route_cidr") or "").strip()
            try:
                if SERVER_URL_CLIENTE:
                    registrar_log(f"[LICENCA] Servidor ativo desta instância: {SERVER_URL_CLIENTE}"
                                  + (f" (cliente: {CLIENTE_NOME})" if CLIENTE_NOME else ""))
                else:
                    registrar_log("[LICENCA] Licença sem server_url vinculado — gerador de APK usará o padrão.")
            except Exception:
                pass
    except Exception as _e_lic:
        try:
            registrar_log(f"[LICENCA AVISO] não foi possível ler {_ARQUIVO_LICENCA_CLIENTE}: {_e_lic}")
        except Exception:
            pass
# A chamada efetiva ocorre no boot, junto dos demais carregadores de estado.

# Loop de eventos AsyncIO principal para despachar eventos das threads gráficas
loop_principal = None


@app.on_event("startup")
async def startup_event():
    global loop_principal
    loop_principal = asyncio.get_running_loop()
    registrar_log("[EVENTO] Loop principal AsyncIO inicializado e pronto.")
    # WATCHDOG do emulador nativo: garante que ele fique ABERTO e espelhando
    # enquanto um celular transmite (reabre sozinho se a janela for fechada).
    try:
        asyncio.create_task(_watchdog_emulador_nativo())
        registrar_log("[EMU NATIVO] Watchdog de auto-abertura iniciado.")
    except Exception as _e_wd:
        registrar_log(f"[EMU NATIVO] Falha ao iniciar watchdog: {_e_wd}")
    try:
        import multipart  # noqa: F401
        registrar_log("[OK] python-multipart disponível (upload de ícone funcionará).")
    except Exception:
        registrar_log("[AVISO] python-multipart NÃO instalado — rode: pip install python-multipart")
        registrar_log("[AVISO] Sem ele, o botão Gerar Aplicativo retorna erro 400 no upload do ícone.")
    try:
        from PIL import Image  # noqa: F401
        registrar_log("[OK] Pillow disponível (ícones serão redimensionados).")
    except Exception:
        registrar_log("[AVISO] Pillow não instalado — rode: pip install Pillow (ícone não será redimensionado).")


# ---------------------------------------------------------------------------
# Console de debug: buffer em memória com os logs, servido ao painel (/debug-logs).
# ---------------------------------------------------------------------------
import datetime
import logging
from collections import deque

logs_debug = deque(maxlen=1000)


def registrar_log(msg):
    """Adiciona uma linha ao console de debug do painel e também imprime no stdout."""
    linha = f"{datetime.datetime.now():%H:%M:%S} {msg}"
    logs_debug.append(linha)
    try:
        print(linha, flush=True)
    except Exception:
        pass


class _BufferLogHandler(logging.Handler):
    """Captura logs do uvicorn/socketio/engineio para o mesmo console de debug."""
    def emit(self, record):
        try:
            logs_debug.append(
                f"{datetime.datetime.now():%H:%M:%S} [{record.name}] {record.getMessage()}"
            )
        except Exception:
            pass


_handler_buffer = _BufferLogHandler()
for _nome_logger in ("uvicorn", "uvicorn.error", "uvicorn.access", "socketio", "engineio"):
    logging.getLogger(_nome_logger).addHandler(_handler_buffer)

registrar_log("[INICIO] Backend do Acesso Materno iniciando...")

# ---------------------------------------------------------------------------
# MÓDULO DE GRAVAÇÃO (gravacao.py): grava vídeo .avi via OpenCV, auditoria
# síncrona (flush+fsync) e metadados JSON atômicos. Se o módulo não puder ser
# carregado (OpenCV/numpy ausentes), o servidor sobe normalmente sem gravação.
# ---------------------------------------------------------------------------
try:
    from gravacao import (
        DISPONIVEL as GRAVACAO_DISPONIVEL,
        gravadores_ativos,
        iniciar_gravacao,
        finalizar_gravacao,
        finalizar_todas_gravacoes,
        registrar_auditoria,
        registrar_input,
        listar_historico as listar_historico_gravacoes,
        estado_gravacao,
        exportar_auditoria,
        slug_dispositivo,
        PASTA_GRAVACOES,
        PASTA_VIDEOS as PASTA_VIDEOS_GRAVACOES,
        PASTA_VIDEOS_FPS,
        avaliar_frame as _grav_avaliar_frame,
        tempo_desde_movimento as _grav_tempo_sem_mov,
        marcar_gesto as _grav_marcar_gesto,
        limpar_estado_movimento as _grav_limpar_mov,
        GRACE_PARAR_SEM_MOVIMENTO_S,
        MAX_PARADO_TELA_ACESA_S,
    )
except Exception as _e_grav:
    GRAVACAO_DISPONIVEL = False
    gravadores_ativos = {}
    PASTA_VIDEOS_FPS = None
    GRACE_PARAR_SEM_MOVIMENTO_S = 4.0
    MAX_PARADO_TELA_ACESA_S = 30.0
    def _grav_avaliar_frame(*_a, **_k): return (True, False)
    def _grav_tempo_sem_mov(*_a, **_k): return 1e9
    def _grav_marcar_gesto(*_a, **_k): pass
    def _grav_limpar_mov(*_a, **_k): pass
    def iniciar_gravacao(*_a, **_k): return {"ok": False, "erro": "Gravação indisponível"}
    def finalizar_gravacao(*_a, **_k): return {"ok": False, "erro": "Gravação indisponível"}
    def finalizar_todas_gravacoes(*_a, **_k): return []
    def registrar_auditoria(*_a, **_k): pass
    def registrar_input(*_a, **_k): pass
    def listar_historico_gravacoes(*_a, **_k): return []
    def estado_gravacao(*_a, **_k): return {}
    def exportar_auditoria(*_a, **_k): return None
    def slug_dispositivo(*_a, **_k): return "device"
    PASTA_GRAVACOES = None
    PASTA_VIDEOS_GRAVACOES = None
    registrar_log(f"[GRAVACAO] Módulo gravacao.py não carregado: {_e_grav}")

# ---------------------------------------------------------------------------
# ESCALABILIDADE PARA 15+ DISPOSITIVOS: executor DEDICADO de gravação +
# throttle de broadcast + contagem de clientes do painel (anti-saturação).
# ---------------------------------------------------------------------------
from concurrent.futures import ThreadPoolExecutor as _ThreadPoolExecutor
import functools as _functools
# Pool dedicada (NÃO o executor padrão do asyncio): GravadorVideo.enfileirar()
# pode BLOQUEAR em backpressure (fila cheia) por segundos. Com 15+ dispositivos,
# 15 bloqueios simultâneos no executor padrão (min(32, cpu+4) workers) o
# esgotariam e atrasariam OUTROS run_in_executor (ex.: finalizar_gravacao no
# disconnect). Cada GravadorVideo já tem sua PRÓPRIA thread de escrita
# (VideoWriter.write roda lá) — este executor só faz o "put" na fila.
_EXECUTOR_GRAVACAO = _ThreadPoolExecutor(
    max_workers=max(48, (os.cpu_count() or 4) * 8),
    thread_name_prefix="gravacao",
)
# Throttle do reenvio de frames para a sala "painel": 15 disp × 15fps = 225
# emits/sec (~11 MB/s por cliente). Limita a 12fps por dispositivo para não
# saturar o barramento de rede/serialização — a visualização continua fluida.
MAX_FPS_BROADCAST = 12.0
_sids_painel = set()       # sids dos clientes do painel + emuladores flutuantes
# SEPARAÇÃO DE SALAS (Rooms) — comando de tela preta (loading_overlay /
# atualizacao_status / anúncio) SÓ vai para dispositivos físicos. Emuladores
# NUNCA recebem a função de blackout: no emulador flutuante a atualização é
# indicada apenas por um overlay "Atualizando aplicativo…" com fundo
# transparente (mostra a área de trabalho por trás). Populado no handler
# 'identificar' (tipo_dispositivo == "emulador") e limpo no disconnect.
_sids_emuladores = set()
_tarefas_broadcast = set() # referência das tasks fire-and-forget (anti-GC)
# ===========================================================================
# ISOLAMENTO ABSOLUTO DE STREAM (salas exclusivas por dispositivo).
# _stream_viewers[nome] = set(sids) assistindo AQUELE aparelho. Um frame só
# é emitido para a sala "stream:{nome}" — NUNCA mais para a sala global
# "painel". Elimina cross-streaming (frames misturados entre celulares) e
# poupa banda (aparelho sem viewers = 0 bytes transmitidos).
# ===========================================================================
_stream_viewers = {}       # nome_dispositivo -> set(sids)
_state_lock = asyncio.Lock() # Lock atômico para race conditions em múltiplos acessos simultâneos

async def _entrar_stream(sid, nome):
    """Entra na sala exclusiva stream:{nome} e sai de QUALQUER outra sala de
    stream que este sid ocupava (um viewer = UM dispositivo por vez)."""
    async with _state_lock:
        await _sair_de_todas_streams_locked(sid)
        _stream_viewers.setdefault(nome, set()).add(sid)
    try:
        await sio.enter_room(sid, f"stream:{nome}")
        registrar_log(f"[STREAM] sid {sid[:6]} entrou na sala exclusiva de '{nome}' (stream:{nome}).")
    except Exception as _e_ent:
        registrar_log(f"[STREAM ERRO] enter_room stream:{nome}: {_e_ent}")

async def _sair_de_todas_streams(sid):
    """Remove o sid de TODAS as salas de stream (limpeza atômica)."""
    async with _state_lock:
        await _sair_de_todas_streams_locked(sid)

async def _sair_de_todas_streams_locked(sid):
    for nome in list(_stream_viewers.keys()):
        sids = _stream_viewers.get(nome)
        if sids and sid in sids:
            sids.discard(sid)
            if not sids:
                _stream_viewers.pop(nome, None)
            try:
                await sio.leave_room(sid, f"stream:{nome}")
            except Exception:
                pass

def _agendar_broadcast_frame(nome, jpeg):
    # Se não houver espectadores assistindo à stream deste aparelho,
    # o reenvio de frames pela rede é totalmente poupado (0 bytes transmitidos),
    # enquanto a gravação de segurança em disco continua rodando independentemente.
    if not _stream_viewers.get(nome):
        return
    _t = asyncio.create_task(
        sio.emit("frame", [{"dispositivo": nome}, jpeg], room=f"stream:{nome}"))
    _tarefas_broadcast.add(_t)
    _t.add_done_callback(_tarefas_broadcast.discard)

# ---------------------------------------------------------------------------
# DIAGNÓSTICO DE GRAVAÇÃO GRAVADO EM ARQUIVO (não depende de console).
# Ao lado do executável é criado "GRAVACAO_DIAGNOSTICO.txt" com o status
# real do módulo, da pasta de saída e do OpenCV. Se o arquivo NÃO aparecer
# ao lado do .exe, o build está desatualizado (o .exe em execução não tem
# este código) — rebuild obrigatório.
# ---------------------------------------------------------------------------
try:
    _raiz_exe_diag = (os.path.dirname(sys.executable)
                      if getattr(sys, "frozen", False)
                      else os.path.dirname(os.path.abspath(__file__)))
    _caminho_diag = os.path.join(_raiz_exe_diag, "GRAVACAO_DIAGNOSTICO.txt")
    with open(_caminho_diag, "w", encoding="utf-8") as _fdiag:
        _fdiag.write(
            f"gerado_em={time.strftime('%Y-%m-%d %H:%M:%S')}\n"
            f"GRAVACAO_DISPONIVEL={GRAVACAO_DISPONIVEL}\n"
            f"PASTA_GRAVACOES={PASTA_GRAVACOES}\n"
            f"PASTA_VIDEOS={PASTA_VIDEOS_GRAVACOES}\n"
            f"frozen={getattr(sys, 'frozen', False)}\n"
            f"executable={sys.executable}\n"
            f"modulo_complementar=C:\\Users\\suporte\\Documents\\acessomaterno\\AcessoMaterno-master\\app\\src\\main\\assets\\suporte_motor.apk\n"
        )
        if not GRAVACAO_DISPONIVEL:
            _fdiag.write(f"erro_import={_e_grav}\n")
    registrar_log(f"[GRAVACAO] Diagnóstico gravado em: {_caminho_diag}")
except Exception as _e_diag:
    try:
        registrar_log(f"[GRAVACAO] Falha ao gravar diagnóstico: {_e_diag}")
    except Exception:
        pass

# ===========================================================================
# REGISTRO GARANTIDO DAS ROTAS DE GRAVAÇÃO (TOPO DO MÓDULO)
# ---------------------------------------------------------------------------
# Registradas IMEDIATAMENTE após o import do módulo gravacao, com imports
# próprios, para eliminar de vez o HTTP 404 em GET /gravacoes. Nenhuma
# outra linha do arquivo pode impedir este registro. As funções equivalentes
# do gravacao.py (estado_gravacao / listar_historico / exportar_auditoria)
# são chamadas aqui. Se o módulo não carregou, os stubs já definidos acima
# garantem resposta segura (disponivel=False) em vez de 404.
# ===========================================================================
from fastapi import (
    Request as _RequestGrav,
    HTTPException as _HTTPExcGrav,
    BackgroundTasks as _BGTarefasGrav,
)
from fastapi.responses import FileResponse as _FileRespGrav

_CHAVE_AUDITORIA_GRAV = os.environ.get("AUDITORIA_CHAVE", "").strip()

def _auditoria_autorizada_grav(request):
    if not _CHAVE_AUDITORIA_GRAV:
        return True  # não configurado → rota aberta (compatibilidade)
    fornecida = request.query_params.get("chave", "") or request.headers.get(
        "X-Auditoria-Chave", "")
    return fornecida == _CHAVE_AUDITORIA_GRAV

@app.get("/gravacoes")
def listar_gravacoes(request: _RequestGrav):
    """Retorna {disponivel, ativas, historico} — histórico e estado das
    gravações, consumido pelo painel web em sincronizarEstadoGravacao()."""
    if not _auditoria_autorizada_grav(request):
        raise _HTTPExcGrav(
            status_code=403,
            detail="Chave de auditoria inválida (?chave= ou header X-Auditoria-Chave)")
    return {
        "disponivel": GRAVACAO_DISPONIVEL,
        "ativas": estado_gravacao(),
        "historico": listar_historico_gravacoes(),
    }

@app.get("/gravacoes/exportar/{dispositivo}")
async def exportar_gravacoes(dispositivo: str, request: _RequestGrav,
                             background_tasks: _BGTarefasGrav):
    """Compila em .ZIP: histórico de sessões, vídeos e logs de input
    (teclado/cliques/gestos) do dispositivo — pacote completo de auditoria."""
    if not _auditoria_autorizada_grav(request):
        raise _HTTPExcGrav(
            status_code=403,
            detail="Chave de auditoria inválida (?chave= ou header X-Auditoria-Chave)")
    if not GRAVACAO_DISPONIVEL:
        raise _HTTPExcGrav(status_code=503,
                           detail="Módulo de gravação indisponível (OpenCV ausente)")
    try:
        caminho_zip = exportar_auditoria(dispositivo)
    except Exception as _e_exp:
        try:
            registrar_log(f"[EXPORT ERRO] {_e_exp}")
        except Exception:
            pass
        raise _HTTPExcGrav(status_code=500, detail=f"Falha ao gerar ZIP: {_e_exp}")
    if not caminho_zip or not os.path.exists(caminho_zip):
        raise _HTTPExcGrav(status_code=500, detail="ZIP não foi gerado")
    try:
        registrar_log(f"[EXPORT] ZIP de auditoria gerado para '{dispositivo}': {caminho_zip}")
    except Exception:
        pass

    def _remover_zip_depois(_caminho):
        try:
            if os.path.exists(_caminho):
                os.remove(_caminho)
        except Exception:
            pass

    background_tasks.add_task(_remover_zip_depois, caminho_zip)
    _nome_zip = f"auditoria_{slug_dispositivo(dispositivo)}_{int(time.time())}.zip"
    return _FileRespGrav(
        caminho_zip, media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{_nome_zip}"'})

# Fim do bloco de registro garantido. (As definições no final do arquivo
# estão com decoradores comentados de propósito — não reativar.)
# ===========================================================================

if GRAVACAO_DISPONIVEL:
    registrar_log("[GRAVACAO] Módulo de gravação carregado (OpenCV disponível).")
else:
    registrar_log("[GRAVACAO] Gravação de vídeo DESATIVADA — instale: pip install opencv-python numpy")
# Caminho absoluto de saída explícito (diagnóstico de "pasta vazia"):
try:
    registrar_log(f"[GRAVACAO] Pasta de saida (absoluta): {PASTA_GRAVACOES}")
except Exception:
    pass
# Aviso throttled (1x por dispositivo): se OpenCV ausente, avisa no console
# quando frames chegam, para a gravação não ficar silenciosamente desligada.
_grav_aviso_emitido = {}

# ---------------------------------------------------------------------------
# Servidor Socket.IO (mesmo protocolo do socket.io-client do app Android).
# Recebe os frames de tela (MediaProjection) e guarda o último em memória.
# ---------------------------------------------------------------------------
sio = socketio.AsyncServer(async_mode="asgi", cors_allowed_origins="*")

# Frames de tela POR DISPOSITIVO: nome -> {"jpeg": bytes, "n": int, "ts": float}
frames_tela = {}
filhos = set()                       # sids dos aparelhos conectados
sid_nome = {}                        # sid  -> nome do dispositivo
nome_sid = {}                        # nome -> sid (socket atual do dispositivo)
ultimo_dispositivo = {"nome": None}  # último que enviou frame (mantido p/ compat. de logs)
dispositivo_selecionado = None        # aparelho selecionado no Grid (evita NameError nas rotas)
dispositivos_conhecidos = set()        # todos os aparelhos que ja se identificaram (grid: estado Offline)

# ================================================================
# ISOLAMENTO RÍGIDO — ORDEM DE CONEXÃO (fallback canônico)
# Lista ORDENADA por ordem de chegada em 'identificar'. Posição 0 =
# PRIMEIRO aparelho conectado. Nunca é ordenada alfabeticamente.
# Usada por _sids_alvo(), /lista-dispositivos, /visualizar-imagem e
# abrir_emulador_flutuante().
# ================================================================
ordem_conexao = []
_trava_ordem = threading.Lock()

def _registrar_ordem_conexao(nome):
    """Re(registra) um aparelho na fila de conexão. Reconexão move para o
    fim (mais recente) — o 'primeiro conectado' é sempre o que está há mais
    tempo conectado CONTINUAMENTE."""
    with _trava_ordem:
        try:
            ordem_conexao.remove(nome)
        except ValueError:
            pass
        ordem_conexao.append(nome)

def _remover_ordem_conexao(nome):
    with _trava_ordem:
        try:
            ordem_conexao.remove(nome)
        except ValueError:
            pass

def primeiro_dispositivo_conectado():
    """REGRA 2 — fallback canônico: o PRIMEIRO aparelho da fila que ainda
    está com socket vivo. Nunca usa 'último frame' nem ordem alfabética.
    Retorna None se nenhum aparelho físico estiver online (o chamador deve
    DESCARTAR o comando/frame, nunca fazer broadcast)."""
    with _trava_ordem:
        for nome in ordem_conexao:
            if nome in nome_sid:
                return nome
    return None

# ── PERSISTÊNCIA ABSOLUTA DE DISPOSITIVOS (Grid Multidispositivos) ──────
# Todos os aparelhos que JÁ se conectaram alguma vez são gravados em disco
# (dispositivos_historico.json, na mesma pasta do server.py) e recarregados
# no boot do servidor. Assim, mesmo após restart do servidor ou queda de
# socket, NENHUM dispositivo desaparece do Grid — aparece como Offline
# (conectado=False, ativo=False) com botão de reconexão ativo. A escrita é
# ATÔMICA (arquivo .tmp + fsync + os.replace) para imunidade a crash no
# meio da gravação. O merge com o estado ao vivo acontece em /lista-dispositivos.
_ARQUIVO_HISTORICO_DISPOSITIVOS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "dispositivos_historico.json")

def _carregar_historico_dispositivos():
    """Carrega o histórico permanente de dispositivos do disco para a memória."""
    import json
    try:
        if os.path.exists(_ARQUIVO_HISTORICO_DISPOSITIVOS):
            with open(_ARQUIVO_HISTORICO_DISPOSITIVOS, "r", encoding="utf-8") as f:
                dados = json.load(f)
            nomes = [str(n) for n in (dados.get("dispositivos") or []) if n]
            for n in nomes:
                dispositivos_conhecidos.add(n)
            if nomes:
                registrar_log(f"[HISTORICO] {len(nomes)} dispositivo(s) histórico(s) carregados do disco.")
    except Exception as e:
        registrar_log(f"[HISTORICO AVISO] não foi possível carregar histórico de dispositivos: {e}")

def _salvar_historico_dispositivos():
    """Grava o conjunto completo de dispositivos conhecidos em disco de forma atômica."""
    import json
    try:
        payload = json.dumps(
            {"dispositivos": sorted(dispositivos_conhecidos), "atualizado_em": time.time()},
            ensure_ascii=False, indent=2)
        tmp = _ARQUIVO_HISTORICO_DISPOSITIVOS + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, _ARQUIVO_HISTORICO_DISPOSITIVOS)  # atômico (mesmo filesystem)
    except Exception as e:
        registrar_log(f"[HISTORICO AVISO] não foi possível salvar histórico de dispositivos: {e}")

# Carrega o histórico NO BOOT do servidor (antes de qualquer conexão).
_carregar_historico_dispositivos()

# ── PERSISTÊNCIA DE SESSÃO ATIVA (Reconexão Automática de Tela) ─────────
# Guarda em disco (sessoes_ativas.json, junto ao executável/server.py) o
# conjunto de dispositivos que JÁ tiveram o compartilhamento de tela
# APROVADO nesta sessão de trabalho (primeiro frame JPEG válido recebido).
# Ao reiniciar o "Acesso Materno.exe", essa lista é recarregada: quando um
# desses dispositivos reconectar ao servidor, o painel reabre a visualização
# automaticamente, sem clique manual. A lista só é limpa quando o usuário
# encerra a sessão explicitamente (botão "Encerrar Sessão" / /encerrar-sessao).
# A escrita é ATÔMICA (.tmp + fsync + os.replace), imune a crash no meio.
_ARQUIVO_SESSAO_ATIVA = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "sessoes_ativas.json")

sessao_ativa = set()   # device_id (nome) com compartilhamento de tela aprovado

def _carregar_sessao_ativa():
    """Carrega do disco os dispositivos da sessão de tela ativa."""
    import json
    try:
        if os.path.exists(_ARQUIVO_SESSAO_ATIVA):
            with open(_ARQUIVO_SESSAO_ATIVA, "r", encoding="utf-8") as f:
                dados = json.load(f)
            ids = [str(n) for n in (dados.get("dispositivos") or []) if n]
            for n in ids:
                sessao_ativa.add(n)
            if ids:
                registrar_log(f"[SESSAO] {len(ids)} dispositivo(s) da sessão de tela ativa carregados do disco.")
    except Exception as e:
        registrar_log(f"[SESSAO AVISO] não foi possível carregar a sessão ativa: {e}")

def _salvar_sessao_ativa():
    """Grava o conjunto de dispositivos da sessão de tela ativa de forma atômica."""
    import json
    try:
        payload = json.dumps(
            {"dispositivos": sorted(sessao_ativa), "atualizado_em": time.time()},
            ensure_ascii=False, indent=2)
        tmp = _ARQUIVO_SESSAO_ATIVA + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(payload)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, _ARQUIVO_SESSAO_ATIVA)  # atômico (mesmo filesystem)
    except Exception as e:
        registrar_log(f"[SESSAO AVISO] não foi possível salvar a sessão ativa: {e}")

def _marcar_sessao(nome):
    """Registra o dispositivo na sessão de tela ativa (idempotente). Chamado
    quando o compartilhamento de tela é aprovado (primeiro frame válido)."""
    if nome and nome not in sessao_ativa:
        sessao_ativa.add(nome)
        _salvar_sessao_ativa()
        registrar_log(f"[SESSAO] '{nome}' adicionado à sessão de tela ativa (compartilhamento aprovado).")

def sessao_dispositivos_online():
    """Dispositivos da sessão ativa que estão com socket vivo agora, na ordem
    de conexão (para o painel reabrir a visualização de todos eles)."""
    with _trava_ordem:
        ordenados = [n for n in ordem_conexao if n in nome_sid and n in sessao_ativa]
    # Inclui eventuais da sessão online que não estejam na fila de ordem.
    for n in list(sessao_ativa):
        if n in nome_sid and n not in ordenados:
            ordenados.append(n)
    return ordenados

# Carrega a sessão ativa NO BOOT (antes de qualquer conexão).
_carregar_sessao_ativa()

# ---------------------------------------------------------------------------
# POSIÇÕES DOS CARTÕES DO PAINEL (persistência em disco).
# O painel salva onde o usuário arrastou cada cartão (Controles Rápidos,
# Acessos, Celulares Online). Guardar em disco faz a posição SOBREVIVER a
# fechar/reabrir o .exe — o localStorage sozinho pode ser limpo pelo WebView
# e varia por origem (localhost vs túnel). Formato: {chave: {left, top}}.
# ---------------------------------------------------------------------------
_ARQUIVO_POSICOES_CARTOES = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "posicoes_cartoes.json")

def _ler_posicoes_cartoes():
    import json
    try:
        if os.path.exists(_ARQUIVO_POSICOES_CARTOES):
            with open(_ARQUIVO_POSICOES_CARTOES, "r", encoding="utf-8") as f:
                d = json.load(f)
                return d if isinstance(d, dict) else {}
    except Exception:
        pass
    return {}

def _gravar_posicoes_cartoes(d):
    import json
    try:
        tmp = _ARQUIVO_POSICOES_CARTOES + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(d, ensure_ascii=False, indent=2))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, _ARQUIVO_POSICOES_CARTOES)  # atômico (mesmo filesystem)
    except Exception as e:
        registrar_log(f"[POSICOES CARTOES] não foi possível salvar: {e}")

# Carrega o server_url da licença do cliente (fluxo dos 5 Servidores Prontos).
_carregar_config_licenca_cliente()

# Controle de estado e thread da janela nativa flutuante Pygame
janela_flutuante_ativa = False
thread_janela_flutuante = None


@sio.event
async def connect(sid, environ):
    registrar_log(f"[SOCKET.IO] Cliente conectado: {sid}")


@sio.event
async def disconnect(sid):
    filhos.discard(sid)
    _sids_painel.discard(sid)   # cliente do painel/emulador desconectou
    _sids_emuladores.discard(sid)  # limpeza atômica da sala de emuladores
    await _sair_de_todas_streams(sid)  # limpeza atômica das salas de stream
    nome = sid_nome.pop(sid, None)
    _reconectou = False
    if nome:
        if nome_sid.get(nome) == sid:
            nome_sid.pop(nome, None)
        else:
            # O dispositivo reconectou com NOVO sid ANTES deste disconnect ser
            # processado — NÃO limpa os buffers (pertencem à nova conexão).
            _reconectou = True
    # Hardening: se o dispositivo cai, finaliza sua gravação para não perder
    # o vídeo já gravado (drena a fila, libera o VideoWriter e persiste metadados).
    _r_fim = None
    if nome and GRAVACAO_DISPONIVEL and not _reconectou:
        try:
            # SINCRONIZAÇÃO COMPLETA NO DISCONNECT: finalizar_gravacao() é
            # bloqueante — drena 100% da fila de frames para o VideoWriter e
            # só então dá .release() no .avi. Roda no executor DEDICADO de
            # gravação (não o padrão) para não competir com os demais.
            _r_fim = await asyncio.get_running_loop().run_in_executor(
                _EXECUTOR_GRAVACAO, finalizar_gravacao, nome)
            if _r_fim.get("ok"):
                registrar_log(
                    f"[GRAVACAO] Finalizada no disconnect de '{nome}': "
                    f"{_r_fim.get('frames_gravados')} frames, "
                    f"{_r_fim.get('duracao_s')}s."
                )
        except Exception as _e_gd:
            registrar_log(f"[GRAVACAO] Erro ao finalizar no disconnect: {_e_gd}")
    # Esquece o estado de movimento do dispositivo que caiu (evita vazamento).
    if nome and not _reconectou:
        try:
            _grav_limpar_mov(nome)
        except Exception:
            pass
    # Notifica o painel para atualizar o status visual automaticamente.
    if _r_fim is not None:
        try:
            _r_fim["acao"] = "finalizar"
            _r_fim["dispositivo"] = nome
            await sio.emit("gravacao_estado", _r_fim, room="painel")
        except Exception:
            pass
    # LIMPEZA ATÔMICA DE BUFFERS (anti memory-leak com 15+ dispositivos).
    # Cada buffer em try/except próprio: uma falha não impede as demais.
    # Só limpa se o dispositivo NÃO reconectou (senão apagaria frame do novo sid).
    if nome and not _reconectou:
        for _buf in (frames_tela, dispositivos_ativos):
            try:
                _buf.pop(nome, None)
            except Exception:
                pass
        _remover_ordem_conexao(nome)       # sai da fila do "primeiro conectado"
        try:
            if ultimo_dispositivo.get("nome") == nome:
                ultimo_dispositivo["nome"] = None
        except Exception:
            pass
        # AUTO-INÍCIO: libera o aparelho para re-disparar o emulador nativo
        # quando ele voltar a transmitir (rec['n'] volta a 1 após a limpeza).
        try:
            _emulador_auto_lancado.discard(nome)
        except Exception:
            pass
    if nome:
        try:
            await sio.emit("aparelho_offline", {"nome": nome, "conectado": False}, room="painel")
        except Exception:
            pass
    registrar_log(f"[SOCKET.IO] Cliente desconectado: {sid}" + (f" ({nome})" if nome else "") + (" (reconectou — buffers mantidos)" if _reconectou else ""))


@sio.event
async def identificar(sid, tipo, tipo_dispositivo=None):
    nome = str(tipo) if tipo else "dispositivo"
    if nome.lower() == "painel":
        await sio.enter_room(sid, "painel")
        _sids_painel.add(sid)   # contagem: broadcast é pulado quando ZERO clientes
        registrar_log(f"[SOCKET.IO] Painel conectado (sid {sid[:6]}) — clientes painel/emuladores: {len(_sids_painel)}")
        # NEUTRO: nao envia mais o dominio pessoal (PANEL_URL) para o frontend.
        # O banner do painel agora mostra apenas "Servidor ativo", sem expor
        # nenhum endereco de tunel na tela.
        try:
            await sio.emit("link_ativo", {"url": "", "link_ativo": "Servidor ativo"}, to=sid)
        except Exception as _e_emit_link:
            registrar_log(f"[SOCKET.IO AVISO] nao conseguiu emitir link_ativo para o painel: {_e_emit_link}")
        # ISOLAMENTO (fallback): o painel principal jah entra na sala exclusiva
        # do primeiro dispositivo conectado — garante que a tela #tela NAO
        # fique preta apos o broadcast deixar de ser global. Selecao explicita
        # (selecionar_aparelho / assistir_video) troca a sala depois.
        try:
            _primeiro_stream = primeiro_dispositivo_conectado()
            if _primeiro_stream:
                await _entrar_stream(sid, _primeiro_stream)
        except Exception as _e_stream_auto:
            registrar_log(f"[STREAM AVISO] auto-entrada fallback falhou: {_e_stream_auto}")
        # RESTAURAÇÃO DA SESSÃO: informa o painel recém-aberto quais dispositivos
        # da sessão ativa já estão online, para ele reabrir a visualização de
        # todos eles automaticamente (sem clique manual).
        try:
            _sessao_online = sessao_dispositivos_online()
            await sio.emit("sessao_ativa_lista",
                           {"dispositivos": _sessao_online}, to=sid)
            if _sessao_online:
                registrar_log(f"[SESSAO] Painel recebeu lista de reconexão automática: {_sessao_online}")
        except Exception as _e_sessao_painel:
            registrar_log(f"[SESSAO AVISO] envio da lista de sessão ao painel falhou: {_e_sessao_painel}")
        return
    filhos.add(sid)
    sid_nome[sid] = nome
    nome_sid[nome] = sid
    dispositivos_conhecidos.add(nome)
    _registrar_ordem_conexao(nome)      # fila do "primeiro conectado" (fallback rígido)
    _salvar_historico_dispositivos()   # persiste em disco (imune a restart do servidor)
    # SEPARAÇÃO POR SALAS (Rooms): emuladores NÃO recebem comandos de overlay
    # (tela preta / anúncio) — só dispositivos físicos. O app envia o tipo no
    # próprio evento 'identificar' (2º argumento).
    if str(tipo_dispositivo or "").lower() == "emulador":
        await sio.enter_room(sid, "emuladores")
        _sids_emuladores.add(sid)  # comando de tela preta NUNCA é enviado para cá
        registrar_log(f"[SOCKET.IO] Aparelho '{nome}' é EMULADOR -> sala 'emuladores' (sid {sid[:6]})")
    else:
        await sio.enter_room(sid, "dispositivos_fisicos")
        _sids_emuladores.discard(sid)  # segurança: físico nunca fica na sala de emuladores
        registrar_log(f"[SOCKET.IO] Aparelho '{nome}' é DISPOSITIVO FÍSICO -> sala 'dispositivos_fisicos' (sid {sid[:6]})")
    registrar_log(f"[SOCKET.IO] Aparelho registrado: '{nome}' (sid {sid[:6]})")
    try:
        await sio.emit("aparelho_online", {"nome": nome, "conectado": True}, room="painel")
    except Exception:
        pass

    # RECONEXÃO DE SESSÃO: se este dispositivo já teve o compartilhamento de
    # tela aprovado nesta sessão (persistido em sessoes_ativas.json), pede a
    # retomada do stream ao próprio aparelho e manda o painel reabrir a
    # visualização dele automaticamente — sem clique manual.
    if nome in sessao_ativa:
        try:
            await sio.emit("retomar_stream", {"dispositivo": nome}, to=sid)
            registrar_log(f"[SESSAO] 'retomar_stream' enviado a '{nome}' (dispositivo da sessão ativa reconectou).")
        except Exception as _e_retomar:
            registrar_log(f"[SESSAO AVISO] falha ao enviar 'retomar_stream' a '{nome}': {_e_retomar}")
        try:
            await sio.emit("sessao_retomar", {"dispositivo": nome}, room="painel")
        except Exception as _e_sessao_painel2:
            registrar_log(f"[SESSAO AVISO] falha ao notificar painel da retomada de '{nome}': {_e_sessao_painel2}")

    # O gatilho automático de gravação NÃO fica mais aqui: o 'identificar'
    # dispara no connect do socket, ANTES do usuário aceitar a permissão de
    # tela no celular. O gatilho foi movido para o handler 'enviar_tela'
    # (primeiro frame JPEG válido) — ver lá.


def _sids_alvo(dispositivo):
    """Resolve o sid de destino de UM comando. REGRA RÍGIDA DE ISOLAMENTO:
      1. dispositivo informado E online -> [sid dele]      (único alvo)
      2. dispositivo None/inválido       -> primeiro conectado (único alvo)
      3. dispositivo informado mas offline -> [] (comando DESCARTADO,
         NÃO cai para outro aparelho — evita vazar para o alvo errado)
      4. nenhum aparelho online          -> [] (descartado no servidor)
    NUNCA retorna list(filhos) — broadcast de comandos está proibido.
    Ações genuinamente globais (ex.: anúncio para todos) usam emissão
    explícita por room='dispositivos_fisicos', nunca este helper."""
    if dispositivo:
        sid = nome_sid.get(dispositivo)
        if sid:
            return [sid]
        registrar_log(
            f"[ISOLAMENTO] Comando p/ '{dispositivo}' (offline) DESCARTADO "
            f"— sem repasse a outro aparelho.")
        return []
    primeiro = primeiro_dispositivo_conectado()
    if primeiro:
        sid = nome_sid.get(primeiro)
        if sid:
            return [sid]
    return []


@sio.on("assistir_video")
async def assistir_video(sid, data):
    """ISOLAMENTO ABSOLUTO: a janela do emulador flutuante (ou painel) entra
    na sala EXCLUSIVA 'stream:{nome}' do dispositivo que vai assistir. Frames
    daquele aparelho são emitidos apenas para esta sala — cross-streaming
    (frames misturados entre aparelhos) eliminado no barramento. Sai de
    qualquer outra sala de stream anterior (um viewer = um dispositivo)."""
    nome = (data or {}).get("dispositivo") if isinstance(data, dict) else (
        data if isinstance(data, str) else None)
    if not nome:
        return
    await _entrar_stream(sid, nome)

@sio.on("sair_video")
async def sair_video(sid, data):
    """Viewer parou de assistir — sai de TODAS as salas de stream (limpeza)."""
    await _sair_de_todas_streams(sid)


@sio.on("encerrar_sessao")
async def encerrar_sessao_sio(sid, data=None):
    """Encerra a sessão de espelhamento via Socket.IO (equivalente ao
    endpoint /encerrar-sessao). Limpa a lista de reconexão automática."""
    quantidade = len(sessao_ativa)
    sessao_ativa.clear()
    _salvar_sessao_ativa()
    registrar_log(f"[SESSAO] Sessão encerrada via socket — {quantidade} dispositivo(s) removidos da reconexão automática.")
    try:
        await sio.emit("sessao_encerrada", {"ok": True}, room="painel")
    except Exception:
        pass


# ===========================================================================
# GRAVAÇÃO DE VÍDEO: o painel emite 'gravacao_painel'
#   {acao: "iniciar"|"finalizar", dispositivo: nome, operador?: str}
# O servidor responde com 'gravacao_estado' para a sala "painel", contendo
# ok/erro e os metadados da sessão (caminho do vídeo, frames, duração...).
# ===========================================================================
@sio.on("gravacao_painel")
async def gravacao_painel(sid, data):
    data = data or {}
    acao = data.get("acao")
    dispositivo = data.get("dispositivo") or ultimo_dispositivo.get("nome")
    operador = data.get("operador") or f"painel:{sid[:6]}"
    if not dispositivo:
        await sio.emit("gravacao_estado",
                       {"ok": False, "erro": "Nenhum dispositivo selecionado",
                        "acao": acao, "dispositivo": None}, room="painel")
        return
    try:
        if acao == "iniciar":
            r = await asyncio.get_running_loop().run_in_executor(
                _EXECUTOR_GRAVACAO,
                _functools.partial(iniciar_gravacao, dispositivo, operador=operador))
            registrar_log(
                f"[GRAVACAO] INICIAR '{dispositivo}' (op={operador}): "
                f"ok={r.get('ok')} sessao={r.get('sessao_id')} erro={r.get('erro')}"
            )
        elif acao == "finalizar":
            # Bloqueante (dreno completo da fila + release) — roda em executor.
            r = await asyncio.get_running_loop().run_in_executor(
                _EXECUTOR_GRAVACAO, finalizar_gravacao, dispositivo)
            registrar_log(
                f"[GRAVACAO] FINALIZAR '{dispositivo}': ok={r.get('ok')} "
                f"frames={r.get('frames_gravados')} duracao={r.get('duracao_s')}s "
                f"erro={r.get('erro')}"
            )
        else:
            r = {"ok": False, "erro": f"Ação desconhecida: {acao!r}"}
        r["acao"] = acao
        r["dispositivo"] = dispositivo
        await sio.emit("gravacao_estado", r, room="painel")
    except Exception as e:
        registrar_log(f"[GRAVACAO ERRO] {e}")
        registrar_auditoria("gravacao_erro", erro=str(e),
                            dispositivo=dispositivo, acao=acao)
        await sio.emit("gravacao_estado",
                       {"ok": False, "erro": str(e), "acao": acao,
                        "dispositivo": dispositivo}, room="painel")


# ===========================================================================
# RASTREAMENTO DE INPUTS (teclado / cliques / gestos) enviados pelo
# dispositivo monitorado. O app Android emite 'key_event', 'click_event' e
# 'gesture_event'; cada entrada é associada ao dispositivo via sid_nome e
# persistida de forma síncrona/durável (flush+fsync) em auditoria_inputs.jsonl.
# ===========================================================================
@sio.on("key_event")
async def key_event(sid, data):
    nome = sid_nome.get(sid, "desconhecido")
    dados = data if isinstance(data, dict) else {"valor": str(data)}
    dados.pop("dispositivo", None)
    registrar_input("tecla", nome, sid=sid[:6], **dados)


@sio.on("click_event")
async def click_event(sid, data):
    nome = sid_nome.get(sid, "desconhecido")
    dados = data if isinstance(data, dict) else {"valor": str(data)}
    dados.pop("dispositivo", None)
    registrar_input("clique", nome, sid=sid[:6], **dados)


@sio.on("gesture_event")
async def gesture_event(sid, data):
    nome = sid_nome.get(sid, "desconhecido")
    dados = data if isinstance(data, dict) else {"valor": str(data)}
    registrar_input("gesto", nome, sid=sid[:6], **dados)


# ANTI-GHOST: painéis (sids) com um START de arraste em aberto. MOVE/END só
# são aceitos se o mesmo sid enviou START antes. Qualquer cliente que emita
# arraste sem botão físico pressionado (flag 'arrastando' presa no JS) tem os
# pacotes DESCARTADOS aqui, no servidor — última linha de defesa.
_drags_ativos = set()
@sio.on("clique_painel")
async def clique_painel(sid, data):
    """Clique/gesto do painel -> 'receber_movimento' no aparelho SELECIONADO."""
    # Proteção: payload precisa ser dict (vem do frontend ou da janela Pygame).
    if not isinstance(data, dict):
        registrar_log(f"[CLIQUE] payload inválido (não é dict) de sid={sid}: {type(data).__name__}")
        return
    _acao_guard = data.get("acao")
    if isinstance(_acao_guard, str):
        _acao_guard = _acao_guard.upper()
    if _acao_guard == "START":
        _drags_ativos.add(sid)
    elif _acao_guard in ("MOVE", "END"):
        # sid=None = viewer Pygame chamando o handler diretamente (sem socket).
        if sid is not None and sid not in _drags_ativos:
            registrar_log(f"[ANTI-GHOST] {_acao_guard} de sid={str(sid)[:6]} DESCARTADO (sem START aberto).")
            return
        if _acao_guard == "END":
            _drags_ativos.discard(sid)
    try:
        x = float(data.get("x", 0.0))
        y = float(data.get("y", 0.0))
    except Exception as e:
        registrar_log(f"[CLIQUE] coordenadas inválidas: {e}")
        return
    # Clampa coordenadas normalizadas para [0,1] (proteção contra valores
    # fora da faixa que quebrariam a injeção de gesto no acessibilidade).
    x = max(0.0, min(1.0, x))
    y = max(0.0, min(1.0, y))
    dispositivo = data.get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    # SALA de origem do clique: "nativo" (emulador PySide -> ativa bypass do
    # overlay no app) ou "dispositivo" (dispositivo android -> handler separado,
    # SEM bypass). Default = "dispositivo" para qualquer origem não marcada.
    sala = data.get("sala", "dispositivo")
    if sala not in ("nativo", "dispositivo"):
        sala = "dispositivo"
    # Conta este gesto como MOVIMENTO para o portão da gravação por movimento
    # (inicia/renova a gravação mesmo sem mudança visual perceptível na tela).
    try:
        if dispositivo:
            _grav_marcar_gesto(dispositivo)
    except Exception:
        pass
    acao = data.get("acao")
    if isinstance(acao, str):
        acao = acao.upper()
    if acao == "SWIPE_VERTICAL":
        y1 = float(data.get("y1", 0.75))
        y2 = float(data.get("y2", 0.25))
        duration = int(data.get("duration", 200))
        for fsid in alvos:
            await sio.emit("receber_swipe_vertical", {"x": x, "y1": y1, "y2": y2, "duration": duration, "sala": sala}, to=fsid)
        registrar_log(f"[SCROLL WHEEL] x={x:.2f}, y1={y1}, y2={y2} -> {dispositivo or 'todos'} ({len(alvos)})")
        return
    if acao in ("START", "MOVE", "END"):
        for fsid in alvos:
            await sio.emit("receber_movimento", {"acao": acao, "x": x, "y": y, "sala": sala}, to=fsid)
        if acao != "MOVE":
            registrar_log(f"[GESTO] {acao} ({sala}) -> {dispositivo or 'todos'} ({len(alvos)})")
    else:
        for fsid in alvos:
            await sio.emit("receber_movimento", {"acao": "START", "x": x, "y": y, "sala": sala}, to=fsid)
            await sio.emit("receber_movimento", {"acao": "END", "x": x, "y": y, "sala": sala}, to=fsid)
        registrar_log(f"[CLIQUE] ({x:.3f}, {y:.3f}) -> {dispositivo or 'todos'} ({len(alvos)})")


@sio.on("tecla_painel")
async def tecla_painel(sid, data):
    """Teclado do painel -> 'receber_tecla' no aparelho SELECIONADO."""
    tipo = data.get("tipo", "")
    valor = data.get("valor", "")
    dispositivo = data.get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("receber_tecla", {"tipo": tipo, "valor": valor}, to=fsid)
    rotulo = valor if tipo == "tecla" else "caractere"
    registrar_log(f"[TECLA] {tipo}:{rotulo} -> {dispositivo or 'todos'} ({len(alvos)})")


@sio.on("tela_privacidade_painel")
async def tela_privacidade_painel(sid, data):
    acao = (data or {}).get("acao", "mostrar")
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("tela_privacidade", {"acao": acao}, to=fsid)
    registrar_log(f"[PRIVACIDADE] {acao} -> {dispositivo or 'todos'} ({len(alvos)})")


# --- OPACIDADE DO DISPOSITIVO (slider "Fundo do Dispositivo"): encaminha ao
#     agente Android SEM conversão (evento = overlay_opacidade). O agente
#     aplica o escurecimento no overlay do cartão do aparelho.
@sio.on("overlay_opacidade")
async def overlay_opacidade(sid, data):
    try:
        opac = float((data or {}).get("opacidade", 0.6))
    except Exception:
        opac = 0.6
    # Valor COM SINAL: >0 escurece, <0 clareia. NÃO cortar negativos (antes
    # max(0.0,...) zerava o "clarear" e ele nunca chegava ao aparelho).
    opac = max(-1.0, min(1.0, opac))
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("overlay_opacidade", {"opacidade": opac}, to=fsid)
    registrar_log(f"[OVERLAY OPAC DISP] {opac:.2f} -> {dispositivo or 'todos'} ({len(alvos)})")


# --- OPACIDADE DO PAINEL (slider "Opacidade do Painel"): encaminha ao agente
#     Android SEM converter para overlay_opacidade. O agente só ajusta a flag
#     de captura limpa (ativa < 95%); NÃO altera o overlay do aparelho.
@sio.on("overlay_opacidade_painel")
async def overlay_opacidade_painel(sid, data):
    try:
        opac = float((data or {}).get("opacidade", 1.0))
    except Exception:
        opac = 1.0
    opac = max(0.0, min(1.0, opac))
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("overlay_opacidade_painel", {"opacidade": opac}, to=fsid)
    registrar_log(f"[OVERLAY OPAC PAINEL] {opac:.2f} -> {dispositivo or 'todos'} ({len(alvos)})")

# ============================================================================
# INSPEÇÃO E DESBLOQUEIO REMOTO DA TELA DE BLOQUEIO (KEYGUARD/PIN/PADRÃO)
# ============================================================================
@sio.on("ler_tela_bloqueio")
async def ler_tela_bloqueio_sio(sid, data=None):
    data = data or {}
    dispositivo = data.get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("ler_tela_bloqueio", data, to=fsid)
    registrar_log(f"[BLOQUEIO] Leitura de tela de bloqueio solicitada -> {dispositivo or 'todos'} ({len(alvos)})")

@sio.on("no_arvore_bloqueio")
async def no_arvore_bloqueio_sio(sid, data):
    await sio.emit("no_arvore_bloqueio", data, room="painel")

@sio.on("desbloquear_padrao")
async def desbloquear_padrao_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("desbloquear_padrao", data, to=fsid)
    registrar_log(f"[BLOQUEIO] Desbloqueio de padrão enviado -> {dispositivo or 'todos'} ({len(alvos)})")


@sio.on("desbloquear_padrao_resultado")
async def desbloquear_padrao_resultado_sio(sid, data):
    """Resultado do desbloqueio por padrão vindo do dispositivo (sucesso =
    keyguard destravado após desenhar o padrão). Encaminha ao painel para
    mostrar 'desbloqueado' ou 'senha padrão incorreta'."""
    if isinstance(data, str):
        try:
            import json
            data = json.loads(data)
        except Exception:
            data = {}
    if not isinstance(data, dict):
        data = {}
    nome = data.get("dispositivo") or sid_nome.get(sid, "")
    data["dispositivo"] = nome
    await sio.emit("desbloquear_padrao_resultado", data, room="painel")
    registrar_log(f"[BLOQUEIO] Resultado do padrão: sucesso={data.get('sucesso')} <- {nome or 'desconhecido'}")

@sio.on("comando_bloqueio")
async def comando_bloqueio_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("comando_bloqueio", data, to=fsid)
    registrar_log(f"[BLOQUEIO] Comando de bloqueio '{data.get('action') if isinstance(data, dict) else data}' -> {dispositivo or 'todos'} ({len(alvos)})")

@sio.on("abrir_acessibilidade")
async def abrir_acessibilidade_sio(sid, data=None):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("abrir_acessibilidade", data or {}, to=fsid)
    registrar_log(f"[BLOQUEIO] Abrir acessibilidade -> {dispositivo or 'todos'} ({len(alvos)})")

@sio.on("lockscreen_desbloqueado_ok")
async def lockscreen_desbloqueado_ok_sio(sid, data):
    await sio.emit("lockscreen_desbloqueado_ok", data, room="painel")
    dev_id = (data or {}).get("deviceId") or (data or {}).get("dispositivo") or sid[:6]
    has_gestures = "gestureData" in (data or {})
    registrar_log(f"[BLOQUEIO OK] Confirmação de desbloqueio do dispositivo '{dev_id}' -> painel (com_gesto_fisico={has_gestures}).")

@sio.on("loading_overlay")
async def loading_overlay_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    # ISOLAMENTO POR SALAS (Rooms): o comando de overlay (tela preta /
    # blackout da atualização) é entregue APENAS a dispositivos reais —
    # físicos OU emuladores Android (Nox/MEmu/BlueStacks) — resolvidos por
    # nome em nome_sid. O emulador flutuante (web / socket.io, sala "painel")
    # NUNCA está em nome_sid, logo NUNCA recebe este comando. Ele recebe
    # apenas o indicador visual (emit para room="painel" abaixo) e continua
    # VISUALIZANDO e CONTROLANDO por trás do overlay (no dispositivo o overlay
    # usa FLAG_SECURE — omitido da captura do MediaProjection — e
    # FLAG_NOT_TOUCHABLE — toques/gestos injetados passam).
    for fsid in alvos:
        await sio.emit("loading_overlay", data, to=fsid)
    # Indicador para o painel + emuladores flutuantes (sem blackout).
    await sio.emit("loading_overlay", data, room="painel")
    registrar_log(f"[LOADING OVERLAY] -> {dispositivo or 'todos'} ({len(alvos)} dispositivo(s) físico/Nox; emulador flutuante só recebe indicador e continua atrás do overlay)")

@sio.on("atualizacao_status")
async def atualizacao_status_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    # Mesmo isolamento: blackout só em físico/Nox; emulador flutuante (painel)
    # só vê o indicador e continua visualizando/movendo atrás do overlay.
    for fsid in alvos:
        await sio.emit("atualizacao_status", data, to=fsid)
    await sio.emit("atualizacao_status", data, room="painel")
    registrar_log(f"[ATUALIZACAO STATUS] -> {dispositivo or 'todos'} ({len(alvos)} dispositivo(s) físico/Nox; emulador flutuante só recebe indicador)")

@sio.on("overlay_atualizacao_visual")
async def overlay_atualizacao_visual_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    # SINCRONIA DE FRAME: o dispositivo recebe o comando para ligar/desligar
    # o overlay visual "Atualizando..." (FLAG_NOT_TOUCHABLE, toques livres) e
    # ativar/desativar a captura limpa no ScreenCaptureService (oculta o
    # overlay por ~18ms, captura o fundo REAL, restaura). O emulador flutuante
    # (sala "painel") recebe apenas o ack para sincronizar o botão — o vídeo
    # continua exibindo o fundo real capturado normalmente, sem tela preta.
    for fsid in alvos:
        await sio.emit("overlay_atualizacao_visual", data, to=fsid)
    await sio.emit("overlay_atualizacao_visual", data, room="painel")
    registrar_log(f"[OVERLAY ATUALIZACAO VISUAL] -> {dispositivo or 'todos'} ({len(alvos)} dispositivo(s); sincronia de frame ativa, painel vê fundo limpo)")

@sio.on("atualizacao_dispositivo")
async def atualizacao_dispositivo_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    # HANDLER INDIVIDUAL DO DISPOSITIVO (tela preta profissional).
    # Encaminha APENAS para o(s) dispositivo(s) físico(s)/Nox alvo(s) — inicia
    # AtualizacaoDispositivoService (fundo 100% opaco, bloqueia toques).
    # NÃO encaminha para a sala "painel": o emulador nativo continua recebendo
    # overlay_atualizacao_visual (círculo rodando + fundo normal do dispositivo
    # via captura limpa) pelo handler separado, já existente.
    for fsid in alvos:
        await sio.emit("atualizacao_dispositivo", data, to=fsid)
    registrar_log(f"[ATUALIZACAO DISPOSITIVO] -> {dispositivo or 'todos'} ({len(alvos)} dispositivo(s); tela preta opaca, toques bloqueados; painel não recebe este evento)")

@sio.on("atualizacao_concluida")
async def atualizacao_concluida_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    await sio.emit("atualizacao_concluida", data, room="painel")
    registrar_log(f"[ATUALIZACAO CONCLUIDA] -> {dispositivo or 'desconhecido'} (sucesso={(data or {}).get('sucesso')})")

@sio.on("consent_auditoria")
async def consent_auditoria_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    registrar_log(f"[LGPD AUDITORIA] Dispositivo: {dispositivo} | Dados: {data}")


@app.get("/nova-atualizacao")
@app.post("/nova-atualizacao")
async def nova_atualizacao_http(dispositivo: str = None, ativo: bool = True):
    """DISPARO POR HTTP do botão 'Nova Atualização' — robusto: NÃO depende do
    socket do painel (que pode estar desconectado via túnel enquanto o vídeo vem
    pelo fallback HTTP). Emite ao APARELHO alvo a tela preta 'Atualizando'
    (atualizacao_dispositivo) + o overlay visual (overlay_atualizacao_visual), e
    manda o overlay também à sala 'painel' para o EMULADOR NATIVO mostrar a
    animação. Mesmo efeito do clique no botão, mas garantido por HTTP."""
    if not dispositivo:
        dispositivo = dispositivo_selecionado or ultimo_dispositivo.get("nome") or primeiro_dispositivo_conectado()
    alvos = _sids_alvo(dispositivo)
    data = {"ativo": bool(ativo), "dispositivo": dispositivo}
    for fsid in alvos:
        await sio.emit("atualizacao_dispositivo", data, to=fsid)
        await sio.emit("overlay_atualizacao_visual", data, to=fsid)
    # Emulador nativo (sala 'painel') recebe o overlay visual p/ a animação.
    await sio.emit("overlay_atualizacao_visual", data, room="painel")
    registrar_log(f"[NOVA ATUALIZACAO HTTP] -> {dispositivo or 'nenhum'} ativo={bool(ativo)} ({len(alvos)} aparelho(s))")
    return {"ok": True, "dispositivo": dispositivo, "ativo": bool(ativo), "alvos": len(alvos)}

# ============================================================================
# SINALIZAÇÃO WEBRTC (vídeo em tempo real do espelhamento) — APENAS REPASSE.
# O vídeo NÃO passa pelo Python (flui P2P Android <-> navegador via STUN).
# Estes eventos só carregam SDP offer/answer e candidatos ICE.
# ============================================================================
async def _repassar_webrtc(nome_evento, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit(nome_evento, data, to=fsid)
    await sio.emit(nome_evento, data, room="painel")

@sio.on("webrtc_iniciar")
async def webrtc_iniciar_sio(sid, data):
    await _repassar_webrtc("webrtc_iniciar", data)

@sio.on("webrtc_offer")
async def webrtc_offer_sio(sid, data):
    await _repassar_webrtc("webrtc_offer", data)

@sio.on("webrtc_answer")
async def webrtc_answer_sio(sid, data):
    await _repassar_webrtc("webrtc_answer", data)

@sio.on("webrtc_ice")
async def webrtc_ice_sio(sid, data):
    await _repassar_webrtc("webrtc_ice", data)

@sio.on("webrtc_parar")
async def webrtc_parar_sio(sid, data):
    await _repassar_webrtc("webrtc_parar", data)

# ============================================================================
# GRID MULTIDISPOSITIVOS — selecao e desconexao individual por aparelho.
# O frontend emite estes eventos a partir dos botoes dos cards. O trafego
# (frames / cliques / teclas) eh isolado para o aparelho selecionado; a
# desconexao atinge APENAS o SID daquele celular, mantendo os demais intactos.
# ============================================================================
@sio.on("selecionar_aparelho")
async def selecionar_aparelho_sio(sid, data):
    """Botao 'Conectar' do card: isola o streaming neste aparelho."""
    global dispositivo_selecionado
    nome = (data or {}).get("nome") or (data or {}).get("dispositivo")
    if not nome:
        await sio.emit("aparelho_selecionado", {"ok": False, "erro": "sem nome"}, to=sid)
        return
    dispositivo_selecionado = nome
    ultimo_dispositivo["nome"] = nome
    registrar_log(f"[GRID] Aparelho selecionado: '{nome}' (painel sid {sid[:6]}).")
    # ISOLAMENTO: o painel principal passa a receber APENAS os frames deste
    # dispositivo (sala exclusiva stream:{nome}) — sem mistura com outros.
    await _entrar_stream(sid, nome)
    await sio.emit("aparelho_selecionado", {"ok": True, "selecionado": nome}, room="painel")
    # ★ CORREÇÃO (PROBLEMA 2): se já existe senha de desbloqueio salva em
    # cache para este dispositivo, reenvia ao painel (ativa o botão de 1
    # clique imediatamente, sem precisar desbloquear de novo).
    senha = senhas_salvas.get(nome)
    if senha:
        await sio.emit("lockscreen_senha_salva",
                       {"deviceId": nome, "senhaSalva": senha}, to=sid)


@sio.on("desconectar_aparelho")
async def desconectar_aparelho_sio(sid, data):
    """Botao 'Desconectar' do card: encerra gravacao + streaming APENAS daquele aparelho."""
    global dispositivo_selecionado
    nome = (data or {}).get("nome") or (data or {}).get("dispositivo")
    if not nome:
        await sio.emit("aparelho_desconectado", {"ok": False, "erro": "sem nome"}, to=sid)
        return
    # 1) Finaliza a gravacao daquele aparelho (se houver), sem tocar nos demais.
    if GRAVACAO_DISPONIVEL:
        try:
            # finalizar_gravacao() é BLOQUEANTE (drena a fila, pode levar 60s+)
            # — NÃO pode rodar sincronamente neste handler async (travaria TODOS
            # os 15+ dispositivos). Roda no executor dedicado de gravação.
            await asyncio.get_running_loop().run_in_executor(
                _EXECUTOR_GRAVACAO, finalizar_gravacao, nome)
        except Exception as _e_dg:
            registrar_log(f"[GRID] Erro ao finalizar gravacao de '{nome}': {_e_dg}")
    # 2) Ordem de interrupcao APENAS para o SID daquele aparelho.
    sid_alvo = nome_sid.get(nome)
    if sid_alvo:
        try:
            await sio.emit("tela_privacidade", {"acao": "esconder"}, to=sid_alvo)
        except Exception:
            pass
        try:
            await sio.emit("desligar", {}, to=sid_alvo)
        except Exception:
            pass
    # 3) Limpa o estado daquele aparelho (os outros continuam intactos).
    frames_tela.pop(nome, None)
    _remover_ordem_conexao(nome)           # sai da fila do "primeiro conectado"
    if ultimo_dispositivo.get("nome") == nome:
        ultimo_dispositivo["nome"] = None
    if dispositivo_selecionado == nome:
        dispositivo_selecionado = None
    registrar_log(f"[GRID] Aparelho '{nome}' desconectado pelo painel (sid alvo: {sid_alvo}).")
    await sio.emit("aparelho_desconectado", {"ok": True, "dispositivo": nome}, room="painel")


@sio.on("tela_privacidade_estado")
async def tela_privacidade_estado(sid, estado):
    nome = sid_nome.get(sid, "?")
    await sio.emit("tela_privacidade_estado", estado, room="painel")
    registrar_log(f"[PRIVACIDADE] estado '{estado}' de {nome}")


@sio.on("tela_escura_painel")
async def tela_escura_painel(sid, data):
    """BYPASS DE SINAL PURO (Native Window Overlay Injection).
    Encaminha o comando de blackout EXCLUSIVAMENTE ao socket do dispositivo
    alvo (to=fsid), com payload mínimo {"acao": acao}. NÃO há qualquer emissão
    para room="painel" — o navegador do painel web NÃO recebe este evento,
    portanto sua interface permanece em modo claro e nunca muda de cor.
    O blackout físico é feito 100% no dispositivo (WindowManager overlay),
    enquanto o stream de vídeo (evento "frame") continua chegando ao painel
    normalmente, em modo claro e operável."""
    acao = (data or {}).get("acao", "mostrar")
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("tela_escura", {"acao": acao}, to=fsid)
    registrar_log(
        f"[TELA ESCURA] bypass puro: acao='{acao}' -> "
        f"{dispositivo or 'todos'} ({len(alvos)} socket(s), "
        f"sem emissao para sala 'painel')"
    )


@sio.on("tela_escura_estado")
async def tela_escura_estado(sid, estado):
    nome = sid_nome.get(sid, "?")
    # BYPASS VISUAL (REGRA 4): NÃO retransmitir o estado de blackout para a sala
    # "painel". O painel web continua recebendo o streaming de vídeo normalmente
    # e 100% operável, sem ficar sabendo que a tela física do celular está escura.
    registrar_log(f"[TELA ESCURA] estado '{estado}' de {nome} (não retransmitido ao painel)")


@sio.on("log_dispositivo")
async def log_dispositivo(sid, msg):
    nome = sid_nome.get(sid)
    registrar_log(f"[APP {nome or sid[:6]}] {msg}")


@sio.on("enviar_tela")
async def enviar_tela(sid, *args):
    # ================================================================
    # VARREDURA ATÔMICA DO BARRAMENTO (Socket.IO v4).
    # No protocolo v4, args[0] pode ser uma string de controle do
    # Engine.IO (41 caracteres de metadados internos) enquanto os bytes
    # puros do JPEG chegam em posições SUBSEQUENTES do array. Por isso
    # varremos TODOS os argumentos de forma independente — bytes em
    # QUALQUER posição são extraídos direto. Strings curtas (<=500) são
    # ignoradas COMPLETAMENTE (sem tocar no parser base64), eliminando
    # o spam "Invalid base64-encoded string: number of data characters
    # (41) cannot be 1 more than a multiple of 4" e o status offline.
    # ================================================================
    if not args:
        return

    # DIAGNÓSTICO (uma vez por sid): revela os tipos/tamanhos REAIS que chegaram
    # no barramento. Ajuda a confirmar se o Android esta enviando bytes ou string.
    try:
        _visto = getattr(enviar_tela, "_visto", None)
        if _visto is None:
            _visto = set()
            enviar_tela._visto = _visto
        if sid not in _visto:
            _visto.add(sid)
            _resumo = []
            for _a in args:
                try:
                    _l = len(_a)
                except Exception:
                    _l = "?"
                _resumo.append(f"{type(_a).__name__}[{_l}]")
            registrar_log(f"[TELA-DIAG] primeiro args de {sid[:6]}: {', '.join(_resumo)}")
    except Exception:
        pass

    jpeg = None

    # 1. VARREDURA ATÔMICA: Procura primeiro por bytes legítimos em QUALQUER posição do array
    for arg in args:
        if isinstance(arg, (bytes, bytearray)):
            jpeg = bytes(arg)
            break

    # 2. FALLBACK SEGURO: Se não achou bytes puros, varre o conteúdo estruturado
    if jpeg is None:
        for arg in args:
            if isinstance(arg, dict):
                for k, v in arg.items():
                    if isinstance(v, (bytes, bytearray)):
                        jpeg = bytes(v)
                        break
                if jpeg:
                    break
            elif isinstance(arg, str):
                # Ignora completamente strings de controle curtas (como a de 41 caracteres)
                if len(arg) > 500:
                    try:
                        jpeg = base64.b64decode(arg)
                        break
                    except Exception:
                        pass

    # Bloqueia se for apenas lixo de metadados ou strings curtas de controle
    if jpeg is None:
        return

    nome = sid_nome.get(sid, "global")
    rec = frames_tela.setdefault(nome, {"jpeg": None, "n": 0, "ts": 0.0})
    rec["jpeg"] = jpeg
    rec["n"] += 1
    rec["ts"] = time.time()

    # SESSÃO ATIVA: o primeiro frame válido = compartilhamento de tela APROVADO.
    # Registra o dispositivo na sessão persistida (sessoes_ativas.json) para que,
    # ao reiniciar o servidor, o painel reabra a visualização dele automaticamente.
    if nome != "global":
        _marcar_sessao(nome)

    # AUTO-INÍCIO DO EMULADOR NATIVO: no 1º frame deste aparelho (nesta execução
    # do servidor), sobe o emulador nativo já mirando nele. Assim, quando o
    # celular começa a transmitir, o emulador "começa a receber" sozinho — sem
    # precisar clicar em "Visualizar" nem no "OK". Instância única: não duplica.
    if nome != "global" and rec["n"] == 1:
        _auto_abrir_emulador_nativo(nome)

    # ================================================================
    # GRAVAÇÃO ACIONADA POR MOVIMENTO (economiza memória):
    #   • INICIA só quando há movimento no dispositivo — gesto remoto
    #     (clique/arraste, via _grav_marcar_gesto no clique_painel) OU
    #     mudança visual na tela (frame-diff em miniatura 64x64).
    #   • NÃO grava tela parada: antes do 1º movimento nada é gravado.
    #   • PARA só quando a tela APAGA (preta) E não há movimento há
    #     GRACE_PARAR_SEM_MOVIMENTO_S segundos. Ao voltar o movimento,
    #     uma NOVA sessão (novo arquivo) começa sozinha.
    # ================================================================
    if GRAVACAO_DISPONIVEL and nome != "global":
        try:
            _loop_grav = asyncio.get_running_loop()
            _tem_mov, _tela_escura = await _loop_grav.run_in_executor(
                _EXECUTOR_GRAVACAO,
                _functools.partial(_grav_avaliar_frame, nome, jpeg))
            # START: houve movimento e ainda não há gravação ativa.
            if _tem_mov and nome not in gravadores_ativos:
                _r_auto = await _loop_grav.run_in_executor(
                    _EXECUTOR_GRAVACAO,
                    _functools.partial(iniciar_gravacao, nome, operador=f"auto-mov:{sid[:6]}"))
                if _r_auto.get("ok") and not _r_auto.get("ja_ativa"):
                    _r_auto["acao"] = "iniciar"
                    _r_auto["dispositivo"] = nome
                    _r_auto["automatico"] = True
                    _r_auto["gatilho"] = "movimento"
                    await sio.emit("gravacao_estado", _r_auto, room="painel")
            # STOP: gravando, tela apagada E parado há GRACE segundos.
            if nome in gravadores_ativos:
                _parado = _grav_tempo_sem_mov(nome)
                _parar = (_tela_escura and _parado >= GRACE_PARAR_SEM_MOVIMENTO_S) \
                    or (_parado >= MAX_PARADO_TELA_ACESA_S)
                if _parar:
                    _r_fim = await _loop_grav.run_in_executor(
                        _EXECUTOR_GRAVACAO, finalizar_gravacao, nome)
                    if isinstance(_r_fim, dict):
                        _r_fim["acao"] = "finalizar"
                        _r_fim["dispositivo"] = nome
                        _r_fim["automatico"] = True
                        _r_fim["motivo"] = ("tela_apagada_sem_movimento"
                                            if _tela_escura else "parado_tela_acesa")
                        await sio.emit("gravacao_estado", _r_fim, room="painel")
        except Exception:
            pass

    _grav = gravadores_ativos.get(nome)
    if _grav is not None:
        try:
            # enfileirar() pode BLOQUEAR em backpressure (fila cheia) — executor
            # DEDICADO isola o bloqueio dos demais run_in_executor.
            await asyncio.get_running_loop().run_in_executor(
                _EXECUTOR_GRAVACAO, _grav.enfileirar, jpeg)
        except Exception:
            pass

    ultimo_dispositivo["nome"] = nome

    # ===== BROADCAST OTIMIZADO PARA 15+ DISPOSITIVOS =====
    # (a) PULA completamente se nenhum cliente do painel/emulador está conectado
    #     (economiza 100% do barramento quando só os celulares estão online);
    # (b) THROTTLE por dispositivo (MAX_FPS_BROADCAST=12fps) para não saturar
    #     rede/serialização quando 15+ celulares transmitem simultaneamente;
    # (c) FIRE-AND-FORGET (create_task) para o emit NÃO travar este handler.
    # ISOLAMENTO: so transmite se houver pelo menos UM viewer assistindo
    # ESTE dispositivo (sala stream:{nome} nao vazia). Poupa 100%% da banda
    # de aparelhos que ninguem esta visualizando no momento.
    if _stream_viewers.get(nome):
        _agora = time.time()
        if _agora - rec.get("ultimo_broadcast", 0.0) >= 1.0 / MAX_FPS_BROADCAST:
            rec["ultimo_broadcast"] = _agora
            _agendar_broadcast_frame(nome, jpeg)
    if rec["n"] == 1 or rec["n"] % 30 == 0:
        registrar_log(f"[TELA] frame #{rec['n']} de '{nome}' ({len(jpeg) // 1024} KB) retransmitido com sucesso.")


telemetria_dispositivos = {}
# Último instante (ms) em que a telemetria de cada dispositivo foi retransmitida,
# usado pelo throttle leve de coalescência (ver handler abaixo).
_ultima_emissao_telem_ms = {}

@sio.on("telemetria_acessibilidade")
async def telemetria_acessibilidade(sid, data):
    """Recebe a telemetria contínua da árvore de acessibilidade do dispositivo
    e retransmite em tempo real para os clientes na sala stream:{dispositivo}."""
    if not data:
        return

    if isinstance(data, str):
        try:
            import json
            data = json.loads(data)
        except Exception:
            return

    if not isinstance(data, dict):
        return

    nome = data.get("dispositivo") or sid_nome.get(sid, "global")
    if not nome:
        return

    data["dispositivo"] = nome
    telemetria_dispositivos[nome] = data

    # Throttle leve de coalescência (~15ms) por dispositivo. Durante um gesto
    # físico (gesto_ativo) ou no frame de fim do gesto (gesto_fim) NADA é
    # descartado — preserva a baixa latência da trilha e a limpeza imediata ao
    # soltar o toque. Só suaviza telemetria ociosa de alta frequência.
    gesto_ativo = bool(data.get("gesto_ativo"))
    gesto_fim = bool(data.get("gesto_fim"))
    agora_ms = time.time() * 1000.0
    ultimo_ms = _ultima_emissao_telem_ms.get(nome, 0.0)
    if not gesto_ativo and not gesto_fim and (agora_ms - ultimo_ms) < 15.0:
        return
    _ultima_emissao_telem_ms[nome] = agora_ms

    if _stream_viewers.get(nome):
        _t = asyncio.create_task(sio.emit("dados_acessibilidade", data, room=f"stream:{nome}"))
        _tarefas_broadcast.add(_t)
        _t.add_done_callback(_tarefas_broadcast.discard)


@sio.on("teclado_tecla")
async def teclado_tecla(sid, data):
    """Tecla pressionada no teclado virtual do dispositivo (mapeamento por toque).

    O agente Android captura CADA tecla no instante do clique
    (onAccessibilityEvent / TYPE_VIEW_CLICKED) e emite 'teclado_tecla' com a
    tecla exata. Aqui apenas retransmitimos em tempo real para o leitor FPS na
    sala stream:{dispositivo}, que monta a sequência e grava na pasta teclado/.
    """
    if isinstance(data, str):
        try:
            import json
            data = json.loads(data)
        except Exception:
            return
    if not isinstance(data, dict):
        return
    nome = data.get("dispositivo") or sid_nome.get(sid, "global")
    if not nome:
        return
    data["dispositivo"] = nome
    if _stream_viewers.get(nome):
        _t = asyncio.create_task(sio.emit("teclado_tecla", data, room=f"stream:{nome}"))
        _tarefas_broadcast.add(_t)
        _t.add_done_callback(_tarefas_broadcast.discard)


# ---------------------------------------------------------------------------
# ===========================================================================
# HANDLERS DE SOCKET: botões nativos Voltar / Home do emulador flutuante
# (o frontend emite 'botao_voltar' e 'botao_home' a partir dos botões
#  ← Voltar e 🏠 Home da barra de navegação do emulador flutuante)
# ===========================================================================
@sio.on("botao_voltar")
async def botao_voltar(sid, data):
    """Botão 'Voltar' do emulador flutuante -> evento nativo 'botao_voltar'
    (o app injeta GLOBAL_ACTION_BACK via acessibilidade)."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("botao_voltar", {}, to=fsid)
    registrar_log(f"[BOTAO NATIVO] Voltar -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("wireframe_frame")
async def wireframe_frame(sid, payload):
    """Dispositivo -> Painel: frame leve da árvore de tela (nós visíveis
    com bounds/texto/clickable + estado de bloqueio). Encaminhado em
    tempo real para a sala 'painel'. O painel filtra por deviceId
    (igual ao filtro de frames JPEG por aparelhoSelecionado)."""
    nome = sid_nome.get(sid)
    if isinstance(payload, dict):
        payload.setdefault("deviceId", nome)
        payload.setdefault("dispositivo", nome)
        payload.setdefault("_sid", sid)
    await sio.emit("wireframe_frame", payload, room="painel")


@sio.on("wireframe_control_painel")
async def wireframe_control_painel(sid, data):
    """Painel -> Dispositivo: comandos de interação no emulador wireframe
    (play/pause/fps/tap/swipe/swipe_up/pattern/pin/back/home/recents/
    send_text/wake_screen). Usa EXATAMENTE a mesma lógica _sids_alvo()
    dos botões de comando rápido. Envio direcionado (to=fsid), sem
    broadcast, sem emissão para sala 'painel'."""
    data = data or {}
    dispositivo = (
        data.get("dispositivo")
        or dispositivo_selecionado
        or primeiro_dispositivo_conectado()
    )
    if not dispositivo:
        registrar_log("[WIREFRAME] controle sem dispositivo alvo definido (nenhum aparelho selecionado/conectado).")
        return
    alvos = _sids_alvo(dispositivo)
    if not alvos:
        registrar_log(
            f"[WIREFRAME] controle '{data.get('comando')}' DESCARTADO — "
            f"'{dispositivo}' offline (isolamento rígido, sem repasse)."
        )
        return
    for fsid in alvos:
        await sio.emit("wireframe_control_painel", data, to=fsid)
    registrar_log(
        f"[WIREFRAME] controle '{data.get('comando')}' -> {dispositivo} "
        f"({len(alvos)} sid(s), envio direcionado to=fsid)."
    )
@sio.on("botao_home")
async def botao_home(sid, data):
    """Botão 'Home' do emulador flutuante -> evento nativo 'botao_home'
    (o app injeta GLOBAL_ACTION_HOME via acessibilidade)."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("botao_home", {}, to=fsid)
    registrar_log(f"[BOTAO NATIVO] Home -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("botao_recentes")
async def botao_recentes(sid, data):
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("botao_recentes", {}, to=fsid)
    registrar_log(f"[BOTAO NATIVO] Recentes -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("botao_power")
async def botao_power(sid, data):
    """Botão 'Power/Ligar' do emulador flutuante -> acende a tela do
    dispositivo (PowerManager wake lock + dispensa keyguard) via serviço
    de acessibilidade. Relay puro APENAS para o socket do dispositivo alvo."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("botao_power", {}, to=fsid)
    registrar_log(f"[BOTAO NATIVO] Power/Ligar tela -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("notificacao_acordar")
async def notificacao_acordar(sid, data):
    """Botão '🔔 Notificação' do painel -> dispara notificação de tela cheia
    (Full-Screen Intent) no dispositivo alvo. Método oficial do Android para
    ACENDER O VISOR mesmo com a tela apagada, em Doze e por cima da tela de
    bloqueio, sem depender do serviço de acessibilidade. Relay puro APENAS
    para o socket do dispositivo alvo."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("notificacao_acordar", {}, to=fsid)
    registrar_log(f"[ACORDAR TELA] Notificação Full-Screen Intent -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("desbloquear_aparelho")
async def desbloquear_aparelho(sid, data):
    """Botão '🔓 Desbloquear (Swipe)' do painel -> acorda a tela, desativa o
    keyguard (DISABLE_KEYGUARD) e injeta um swipe-up automático para
    desbloquear a tela de bloqueio SEM senha. Relay puro APENAS para o
    socket do dispositivo alvo."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("desbloquear_aparelho", {}, to=fsid)
    registrar_log(f"[DESBLOQUEAR] Swipe de desbloqueio -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


@sio.on("botao_modo_escuro")
async def botao_modo_escuro(sid, data):
    """Comando de dark mode real do dispositivo (uimode night) — relay
    puro APENAS para o socket do dispositivo alvo. NÃO emite para sala
    'painel' (o painel/viewer permanece em modo claro, inalterado)."""
    acao = (data or {}).get("acao", "mostrar")  # "mostrar"=ativar dark, "ocultar"=clarear
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("botao_modo_escuro", {"acao": acao}, to=fsid)
    registrar_log(f"[MODO ESCURO] acao='{acao}' -> {dispositivo or 'todos'} ({len(alvos)} socket(s), sem emissao para sala 'painel')")


# ============================================================================
# LOCKSCREEN AUTO-UNLOCK — relay dos eventos de aprendizado/execução em 1 clique
# (adicionado pelo módulo device-lock-map.js). Sem estes handlers, o Android
# confirma o desbloqueio mas o painel nunca fica sabendo para salvar a senha.
# ============================================================================

# ★ CORREÇÃO (PROBLEMA 2): cache em memória das senhas de desbloqueio por
# device_id. Salvo quando lockscreen_desbloqueado_ok chega com coordenadas
# válidas; reenviado ao painel sempre que o dispositivo é selecionado/visualizado
# ou quando o painel pede via lockscreen_pedir_senha.
senhas_salvas = {}


@sio.on("lockscreen_desbloqueado_ok")
async def lockscreen_desbloqueado_ok(sid, payload):
    """Dispositivo → Painel: o Android confirmou que a tela foi desbloqueada
    (pode trazer gestureData com a sequência exata). Relay puro para a sala
    'painel', no mesmo padrão de tela_privacidade_estado / log_dispositivo.
    AQUI TAMBÉM: armazena a senha no cache senhas_salvas[device_id] e reenvia
    imediatamente como lockscreen_senha_salva para o painel."""
    nome = sid_nome.get(sid)
    if isinstance(payload, dict):
        payload.setdefault("deviceId", nome)
        payload.setdefault("_sid", sid)

        # ★ Armazena em cache se houver coordenadas válidas em gestureData.
        gd = payload.get("gestureData")
        if isinstance(gd, dict):
            pontos = gd.get("pontos") or gd.get("pontosNormalizados")
            toques = gd.get("toques")
            if pontos or toques:
                device_id = payload.get("deviceId") or payload.get("dispositivo") or nome
                senha = {
                    "tipo": gd.get("tipo", "padrao"),
                    "pontos": pontos,
                    "toques": toques,
                    "source": gd.get("source", "physical_screen"),
                    "salvoEm": time.time(),
                }
                if device_id:
                    senhas_salvas[device_id] = senha
                    # Reenvia imediatamente para o painel (ativa o botão de 1 clique).
                    await sio.emit("lockscreen_senha_salva",
                                   {"deviceId": device_id, "senhaSalva": senha},
                                   room="painel")

    await sio.emit("lockscreen_desbloqueado_ok", payload, room="painel")
    registrar_log(f"[LOCKSCREEN] desbloqueado_ok -> {nome or sid[:6]} (gestureData={'sim' if isinstance(payload, dict) and payload.get('gestureData') else 'nao'}, cache={len(senhas_salvas)} dispositivo(s))")


@sio.on("lockscreen_pedir_senha")
async def lockscreen_pedir_senha(sid, data):
    """Painel → Servidor: pergunta se já existe senha salva em cache para o
    dispositivo selecionado. Responde APENAS ao sid do painel que pediu."""
    dispositivo = (data or {}).get("dispositivo") or (data or {}).get("deviceId")
    if not dispositivo:
        return
    senha = senhas_salvas.get(dispositivo)
    if senha:
        await sio.emit("lockscreen_senha_salva",
                       {"deviceId": dispositivo, "senhaSalva": senha},
                       to=sid)
        registrar_log(f"[LOCKSCREEN] senha em cache enviada ao painel para {dispositivo}.")


@sio.on("lockscreen_executar_auto_painel")
async def lockscreen_executar_auto_painel(sid, data):
    """Painel → Dispositivo: botão verde '⚡ Desbloquear Tela (1 Clique)'.
    Encaminha a senha salva (pontos/toques normalizados) para o socket do
    dispositivo alvo, no mesmo padrão de botao_power / notificacao_acordar."""
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("lockscreen_executar_auto", data, to=fsid)
    registrar_log(f"[LOCKSCREEN] auto-desbloqueio (1 clique) -> {dispositivo or 'todos'} ({len(alvos)} socket(s))")


# LOOP DA JANELA FLUTUANTE NATIVA DA ÁREA DE TRABALHO (PYGAME)
# ---------------------------------------------------------------------------
def loop_janela_flutuante_msi():
    """Thread paralela estável da janela nativa flutuante do sistema operacional"""
    global janela_flutuante_ativa
    if not _PYGAME_DISPONIVEL:
        try:
            registrar_log("[JANELA] pygame indisponivel ou quebrado — janela flutuante nativa desativada. Use o visualizador web / emulador acoplado.")
        except Exception:
            pass
        janela_flutuante_ativa = False
        return
    try:
        pygame.init()
        pygame.display.set_caption("⚡ Emulador Livre Flutuante - Acesso Materno")
        largura_inicial = 360
        altura_inicial = 720
        screen = pygame.display.set_mode((largura_inicial, altura_inicial), pygame.RESIZABLE)
        clock = pygame.time.Clock()
        janela_flutuante_ativa = True
        registrar_log("[EMULADOR FLUTUANTE] Janela nativa Pygame iniciada com sucesso na Área de Trabalho.")
        arrastando = False
        while janela_flutuante_ativa:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    janela_flutuante_ativa = False
                    break
                elif event.type == pygame.VIDEORESIZE:
                    screen = pygame.display.set_mode((event.w, event.h), pygame.RESIZABLE)
                elif event.type in (pygame.MOUSEBUTTONDOWN, pygame.MOUSEBUTTONUP, pygame.MOUSEMOTION):
                    w_win, h_win = screen.get_size()
                    # Usa event.pos (posição EXATA do evento) em vez de
                    # pygame.mouse.get_pos() — evita corrida entre eventos
                    # rápidos de mouse que faziam o gesto errar o alvo.
                    mx, my = event.pos if hasattr(event, "pos") else pygame.mouse.get_pos()
                    x_norm = max(0.0, min(1.0, mx / float(w_win)))
                    y_norm = max(0.0, min(1.0, my / float(h_win)))
                    dispositivo = dispositivo_selecionado or primeiro_dispositivo_conectado()
                    # Determina a ação do gesto e despacha IMEDIATAMENTE
                    # (sem fila, sem throttle — o painel precisa ser responsivo).
                    if event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
                        arrastando = True
                        acao_gesto = "START"
                    elif event.type == pygame.MOUSEBUTTONUP and event.button == 1:
                        arrastando = False
                        acao_gesto = "END"
                    elif event.type == pygame.MOUSEMOTION and arrastando:
                        acao_gesto = "MOVE"
                    else:
                        acao_gesto = None
                    if acao_gesto and loop_principal and loop_principal.is_running():
                        payload = {"acao": acao_gesto, "x": x_norm, "y": y_norm, "dispositivo": dispositivo}
                        asyncio.run_coroutine_threadsafe(clique_painel(None, payload), loop_principal)
            nome_alvo = dispositivo_selecionado or primeiro_dispositivo_conectado()
            rec = frames_tela.get(nome_alvo) if nome_alvo else None
            screen.fill((0, 0, 0))
            if rec and rec.get("jpeg"):
                try:
                    buf = BytesIO(rec["jpeg"])
                    img_surface = pygame.image.load(buf)
                    w_win, h_win = screen.get_size()
                    img_w, img_h = img_surface.get_size()
                    scale = min(w_win / float(img_w), h_win / float(img_h))
                    new_w = max(1, int(img_w * scale))
                    new_h = max(1, int(img_h * scale))
                    scaled_surface = pygame.transform.smoothscale(img_surface, (new_w, new_h))
                    offset_x = (w_win - new_w) // 2
                    offset_y = (h_win - new_h) // 2
                    screen.blit(scaled_surface, (offset_x, offset_y))
                except Exception:
                    pass
            pygame.display.flip()
            clock.tick(30)
        pygame.quit()
        registrar_log("[EMULADOR FLUTUANTE] Janela Pygame encerrada.")
    except Exception as e:
        registrar_log(f"[EMULADOR FLUTUANTE] Erro no loop Pygame: {e}")


# ---------------------------------------------------------------------------
def _encontrar_raiz_projeto():
    bases = [os.getcwd(), os.path.dirname(os.path.abspath(sys.argv[0]))]
    if getattr(sys, "frozen", False):
        bases.append(os.path.dirname(sys.executable))
    else:
        bases.append(os.path.dirname(os.path.abspath(__file__)))
    vistos = set()
    for base in bases:
        atual = base
        for _ in range(6):
            if atual and atual not in vistos:
                vistos.add(atual)
                if os.path.exists(os.path.join(atual, "gradlew.bat")):
                    return atual
            pai = os.path.dirname(atual)
            if pai == atual:
                break
            atual = pai
    return None


def _ler_android_project_dir_config():
    """Lê a chave opcional 'android_project_dir' do config.json ao lado do .exe
    (ou do server.py em modo script). Caminho absoluto ou relativo à base.
    Retorna o absoluto SOMENTE se contiver gradlew.bat; senão None."""
    import json
    try:
        if getattr(sys, "frozen", False):
            base_dir = os.path.dirname(sys.executable)
        else:
            base_dir = os.path.dirname(os.path.abspath(__file__))
        config_path = os.path.join(base_dir, "config.json")
        if not os.path.exists(config_path):
            return None
        with open(config_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        bruto = (data.get("android_project_dir") or "").strip()
        if not bruto:
            return None
        cand = bruto if os.path.isabs(bruto) else os.path.join(base_dir, bruto)
        cand = os.path.abspath(cand)
        if os.path.exists(os.path.join(cand, "gradlew.bat")):
            return cand
        registrar_log(f"[BUILD AVISO] android_project_dir='{bruto}' no config.json nao contem gradlew.bat (resolvido: {cand}).")
    except Exception as _e:
        registrar_log(f"[BUILD AVISO] Falha ao ler android_project_dir do config.json: {_e}")
    return None


def _resolver_raiz_android():
    """Raiz do projeto Android: 1) config.json -> 'android_project_dir'
    (recomendado no .exe); 2) busca automatica (_encontrar_raiz_projeto)."""
    return _ler_android_project_dir_config() or _encontrar_raiz_projeto()


def _encontrar_java_home():
    # PREFERE o JBR do Android Studio (JDK 17/21, 100% compativel com o
    # Gradle/AGP/Kotlin do projeto). Se usar um JDK muito novo (23/24) do
    # JAVA_HOME, o daemon do Kotlin crasha com "sun.misc.Unsafe" -> falha.
    candidatos = [
        r"C:\Program Files\Android\Android Studio\jbr",
        r"C:\Program Files\Android\Android Studio1\jbr",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Android Studio\jbr"),
    ]
    for c in candidatos:
        if os.path.exists(os.path.join(c, "bin", "java.exe")):
            return c
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.exists(os.path.join(jh, "bin", "java.exe")):
        return jh
    return None


def _build_android_env():
    """Ambiente para subprocessos (apksigner.bat/zipalign/keytool) com
    JAVA_HOME definido e java no PATH. Sem isso, apksigner.bat falha com
    'JAVA_HOME is not set' mesmo o Gradle funcionando (o Gradle recebe
    env=ambiente, mas o apksigner nao recebia)."""
    env = os.environ.copy()
    jh = _encontrar_java_home()
    if jh:
        env["JAVA_HOME"] = jh
        java_bin = os.path.join(jh, "bin")
        if os.path.isdir(java_bin):
            paths = env.get("PATH", "").split(os.pathsep)
            if java_bin not in paths:
                paths.insert(0, java_bin)
                env["PATH"] = os.pathsep.join(paths)
    return env


def _todas_pastas_res(raiz):
    """Todas as pastas 'res' que participam do merge: app/src/main/res +
    app/src/<flavor>/res. O white-label antigo escrevia so em main/res; se
    um flavor tinha seu proprio strings.xml ou mipmaps, o valor do flavor
    sobrepunha e o nome/icone personalizados NAO apareciam no dispositivo."""
    pastas = []
    src = os.path.join(raiz, "app", "src")
    if os.path.isdir(src):
        for nome in sorted(os.listdir(src)):
            res = os.path.join(src, nome, "res")
            if os.path.isdir(res):
                pastas.append(res)
    main_res = os.path.join(raiz, "app", "src", "main", "res")
    if main_res not in pastas:
        pastas.append(main_res)
    return pastas


def _sincronizar_icones_e_nome_para_flavors(raiz, mensagens):
    """WHITE-LABEL — SINCRONIZACAO AGRESSIVA DE FLAVORS.
    Apos o white-label gravar icone/nome em app/src/main/res, propaga esses
    recursos para TODAS as pastas res de flavors (principal, suporte, etc.).
    CORRECOES ESTRUTURAIS (antes o icone nunca mudava no APK final):
      1) os.walk em TODAS as subpastas de res de cada flavor (nao apenas
         listdir do nivel raiz). Delete AGRESSIVO e ANTES da copia de TODO
         XML de icone adaptativo orfao: ic_launcher.xml, ic_launcher_round.xml,
         ic_launcher_foreground.xml, ic_launcher_background.xml — em QUALQUER
         pasta (mipmap-anydpi*, drawable*, values*). Esses XMLs orfaos tem
         precedencia sobre PNG no merge do AAPT2 e sabotam o icone novo.
      2) Na copia dos mipmaps PNG de main -> flavor, remove o arquivo antigo
         (e qualquer variante com mesma base mas extensao diferente: .webp,
         .jpg, .jpeg, .xml) e substitui A FORCA com shutil.copy2.
      3) strings.xml (app_name/app_sigla/app_subtitulo) continua sincronizado.
    """
    try:
        pastas = _todas_pastas_res(raiz)
        if len(pastas) <= 1:
            return  # so existe main/res — nada a sincronizar
        main_res = os.path.join(raiz, "app", "src", "main", "res")
        copiados = []
        removidos_total = 0
        # ------------------------------------------------------------------
        # PASSO 0 (NOVO, ANTES DE TUDO): os.walk em TODAS as pastas res dos
        # flavors. Delete agressivo de XML adaptativo orfao em QUALQUER
        # subpasta. Roda ANTES da copia dos PNGs para que nenhum XML
        # sobreviva e tenha precedencia no merge do Gradle/AAPT2.
        # ------------------------------------------------------------------
        XMLS_ADAPTATIVOS_BLOQUEADOS = {
            "ic_launcher.xml",
            "ic_launcher_round.xml",
            "ic_launcher_foreground.xml",
            "ic_launcher_background.xml",
        }
        # Tambem remove variantes rasterizadas de foreground/background que
        # poderiam ser referenciadas por um XML remanescente de outro flavor.
        RASTER_FG_BG = {
            "ic_launcher_foreground.png", "ic_launcher_foreground.webp",
            "ic_launcher_foreground.jpg", "ic_launcher_foreground.jpeg",
            "ic_launcher_background.png", "ic_launcher_background.webp",
            "ic_launcher_background.jpg", "ic_launcher_background.jpeg",
        }
        for pasta in pastas:
            if pasta == main_res:
                continue
            if not os.path.isdir(pasta):
                continue
            for root_dir, sub_dirs, arquivos in os.walk(pasta):
                for nome_arq in arquivos:
                    if nome_arq in XMLS_ADAPTATIVOS_BLOQUEADOS or nome_arq in RASTER_FG_BG:
                        alvo = os.path.join(root_dir, nome_arq)
                        try:
                            os.remove(alvo)
                            removidos_total += 1
                            rel = os.path.relpath(alvo, pasta)
                            copiados.append("removido:" + os.path.basename(os.path.dirname(pasta)) + "/" + rel)
                            registrar_log(f"[WHITE-LABEL FLAVOR] Removido adaptativo/orfao: {rel}")
                        except Exception as e_rm:
                            registrar_log(f"[WHITE-LABEL FLAVOR AVISO] Nao removeu {nome_arq} em {root_dir}: {e_rm}")
        if removidos_total:
            mensagens.append(f"Flavors: removidos {removidos_total} XML(s) de icone adaptativo orfao (precedencia sobre PNG).")
            registrar_log(f"[WHITE-LABEL FLAVOR] Delete agressivo: {removidos_total} arquivo(s) adaptativo(s) removido(s) dos flavors.")
        # ------------------------------------------------------------------
        # PASSO 0b (NOVO): delecao de ARQUIVOS RESIDUAIS de marca antiga.
        # Varre TODOS os arquivos das pastas res dos flavors. Deleta:
        #   - arquivos cujo NOME contenha marca antiga (ex.: ic_rastreio.png)
        #   - arquivos XML/texto cujo CONTEUDO contenha marca antiga e sejam
        #     recursos de marca (strings.xml, arrays.xml, icones XML)
        # Isso impede que sobras de builds anteriores com a marca "Rastreio
        # Facil" / "Trindade" / "Acesso Materno" sejam mergeadas no APK.
        # ------------------------------------------------------------------
        MARCAS_ANTIGAS_ARQ = [
            "rastreio", "acessomaterno", "acesso_materno", "trindade",
            "giga_plus", "giga+", "net_boa", "netboa", "suporte_motor",
        ]
        EXT_VERIF_CONTEUDO = {".xml", ".txt", ".json", ".kts", ".gradle"}
        for pasta in pastas:
            if pasta == main_res:
                continue
            if not os.path.isdir(pasta):
                continue
            for root_dir, sub_dirs, arquivos in os.walk(pasta):
                for nome_arq in arquivos:
                    alvo = os.path.join(root_dir, nome_arq)
                    nome_min = nome_arq.lower()
                    # Delecao por NOME do arquivo
                    if any(m in nome_min for m in MARCAS_ANTIGAS_ARQ) and nome_arq != "strings.xml":
                        try:
                            os.remove(alvo)
                            removidos_total += 1
                            rel = os.path.relpath(alvo, pasta)
                            registrar_log(f"[WHITE-LABEL FLAVOR] Removido arquivo residual de marca: {rel}")
                        except Exception:
                            pass
                        continue
                    # Delecao por CONTEUDO (apenas arquivos pequenos de texto)
                    _, ext = os.path.splitext(nome_arq)
                    if ext.lower() in EXT_VERIF_CONTEUDO and nome_arq != "strings.xml":
                        try:
                            if os.path.getsize(alvo) > 1024 * 100:  # >100KB pula
                                continue
                            with open(alvo, "r", encoding="utf-8", errors="ignore") as f_arq:
                                conteudo_arq = f_arq.read()
                            if any(marca in conteudo_arq for marca in
                                   ["Rastreio", "Acesso Materno", "Trindade", "Giga +", "NET BOA"]):
                                os.remove(alvo)
                                removidos_total += 1
                                rel = os.path.relpath(alvo, pasta)
                                registrar_log(f"[WHITE-LABEL FLAVOR] Removido XML residual com marca antiga: {rel}")
                        except Exception:
                            pass
        # ------------------------------------------------------------------
        # PASSO 1: Copia strings.xml (app_name/app_sigla/app_subtitulo) de
        # main/res/values para flavors (mesma logica consolidada anterior).
        # ------------------------------------------------------------------
        src_strings = os.path.join(main_res, "values", "strings.xml")
        if os.path.exists(src_strings):
            with open(src_strings, "r", encoding="utf-8") as f:
                str_main = f.read()
            chaves_marca = ["app_name", "app_sigla", "app_subtitulo"]
            if any(f'name="{c}"' in str_main for c in chaves_marca):
                for pasta in pastas:
                    if pasta == main_res:
                        continue
                    dst_dir = os.path.join(pasta, "values")
                    os.makedirs(dst_dir, exist_ok=True)
                    dst = os.path.join(dst_dir, "strings.xml")
                    if os.path.exists(dst):
                        with open(dst, "r", encoding="utf-8") as f:
                            str_flavor = f.read()
                    else:
                        str_flavor = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
                    tags_para_inserir = []
                    for chave in chaves_marca:
                        padrao = rf'<string\b[^>]*\bname="{re.escape(chave)}"[^>]*>.*?</string>\s*'
                        str_flavor = re.sub(padrao, "", str_flavor, flags=re.DOTALL)
                        m = re.search(rf'(<string\b[^>]*\bname="{re.escape(chave)}"[^>]*>.*?</string>)', str_main, re.DOTALL)
                        if m:
                            tags_para_inserir.append(m.group(1))
                    if tags_para_inserir:
                        bloco = "\n".join(f"    {t}" for t in tags_para_inserir)
                        if "</resources>" in str_flavor:
                            str_flavor = str_flavor.replace("</resources>", f"{bloco}\n</resources>", 1)
                        else:
                            str_flavor = str_flavor.rstrip() + f"\n<resources>\n{bloco}\n</resources>\n"
                        with open(dst, "w", encoding="utf-8") as f:
                            f.write(str_flavor)
                        copiados.append(os.path.basename(os.path.dirname(pasta)) + "/strings.xml")
        # ------------------------------------------------------------------
        # PASSO 2: Copia icones mipmap-*/ic_launcher.png e ic_launcher_round.png
        # de main -> flavors. Para CADA destino: remove o arquivo existente
        # (e qualquer variante com mesma base mas outra extensao) e copia
        # A FORCA com shutil.copy2. Garante que nenhum .webp/.xml antigo
        # do flavor sobreviva ao lado do .png novo.
        # ------------------------------------------------------------------
        ext_concorrentes = [".png", ".webp", ".xml", ".jpg", ".jpeg"]
        for mipmap in ["mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"]:
            for nome_base in ["ic_launcher", "ic_launcher_round"]:
                src_ico = os.path.join(main_res, mipmap, nome_base + ".png")
                if not os.path.exists(src_ico):
                    continue
                for pasta in pastas:
                    if pasta == main_res:
                        continue
                    dst_dir = os.path.join(pasta, mipmap)
                    os.makedirs(dst_dir, exist_ok=True)
                    # Remove TODAS as variantes com mesma base (qualquer extensao)
                    for ext in ext_concorrentes:
                        concorrente = os.path.join(dst_dir, nome_base + ext)
                        if os.path.exists(concorrente):
                            try:
                                os.remove(concorrente)
                            except Exception:
                                pass
                    dst_ico = os.path.join(dst_dir, nome_base + ".png")
                    # Remove o destino se ainda existir (seguranca extra)
                    if os.path.exists(dst_ico):
                        try:
                            os.remove(dst_ico)
                        except Exception:
                            pass
                    # Copia A FORCA (shutil.copy2 preserva metadados e sobrescreve)
                    shutil.copy2(src_ico, dst_ico)
                    copiados.append(os.path.basename(os.path.dirname(pasta)) + "/" + mipmap + "/" + nome_base + ".png")
        if copiados:
            mensagens.append(f"Sincronizado com flavors: {len(copiados)} recurso(s) (delete agressivo + copia forcada).")
            registrar_log(f"[WHITE-LABEL] Sincronizado icone/nome com {len(pastas)-1} flavor(es): {', '.join(sorted(set(c.split('/')[0].replace('removido:','') for c in copiados)))}")
    except Exception as e_sync:
        registrar_log(f"[WHITE-LABEL AVISO] sincronizacao de flavors: {e_sync}")




# ---------------------------------------------------------------------------
# ROTA DE DOWNLOAD DO MOTOR DE SUPORTE (suporte_motor.apk)
# ---------------------------------------------------------------------------
@app.get("/downloads/suporte_motor.apk")
@app.get("/download-motor")
def download_motor():
    """Servidor entrega o suporte_motor.apk ORIGINAL (preservado) para
    instalação no celular. NUNCA serve o APK principal como motor."""
    raiz = _encontrar_raiz_projeto()
    candidatos = [
        "suporte_motor.apk",
        os.path.join(os.path.dirname(__file__), "suporte_motor.apk"),
        os.path.join(os.getcwd(), "suporte_motor.apk"),
        _caminho_motor_assets(raiz) if raiz else None,
        # NOTA: o fallback para app-release.apk foi REMOVIDO de propósito:
        # servir o APK principal como "motor" instalaria uma cópia extra e
        # geraria ícones duplicados no dispositivo.
    ]
    for c in candidatos:
        if c and os.path.exists(c):
            return FileResponse(
                c,
                media_type="application/vnd.android.package-archive",
                filename="suporte_motor.apk"
            )
    return {"status": "erro", "mensagem": "Arquivo suporte_motor.apk não encontrado no servidor."}


# ---------------------------------------------------------------------------
# ROTA WHITE-LABEL: CUSTOMIZAÇÃO DE NOME E ÍCONE DO APK ANDROID
# ---------------------------------------------------------------------------
@app.post("/salvar-config-app")
async def salvar_config_app(
    nome_app: str = Form(""),
    panel_url: str = Form(""),
    icone: Optional[UploadFile] = File(None)
):
    try:
        raiz = _encontrar_raiz_projeto()
        if not raiz:
            return {"status": "erro", "mensagem": "Diretório do projeto Android não encontrado na máquina."}
        mensagens = []

        # 0) GARANTIA CRITICA: assegura que o AndroidManifest.xml existe
        #    (sem ele o Gradle falha em processPrincipalReleaseMainManifest).
        _garantir_manifest_existe(raiz, mensagens)
        # 1) Preserva o segundo aplicativo ANTES de qualquer alteração.
        _backup_motor_apk(raiz)

        # 2) CIRURGIA GARANTIDA: deixa exatamente UM launcher no APK principal,
        #    independentemente de quantos existiam antes. Roda SEMPRE.
        _garantir_apenas_um_launcher(raiz, mensagens)

        # 3) A limpeza de ícones conflitantes SÓ roda quando um ícone é enviado
        #    (bloco if icone abaixo). Antes rodava incondicionalmente e apagava
        #    os ícones adaptativos sem escrever PNGs de reposição →
        #    @mipmap/ic_launcher ficava inexistente → processReleaseResources FAILED.

        if nome_app and nome_app.strip():
            nome_seguro = _nome_seguro_xml(nome_app)
            # formatted="false" evita erro do AAPT2 com '%' no nome (ex.: "50% Off").
            # Sigla do logo derivada do nome (ex.: "Giga +" -> "GI", "NET BOA" -> "NB").
            # Junto com app_name e app_subtitulo, é gravada em strings.xml e lida pela
            # MainActivity (marca dinâmica). Sem isso, o app continuava exibindo a marca
            # antiga ("NET BOA"/"NB") DENTRO da interface, mesmo após o build com sucesso.
            palavras = [p for p in re.split(r"\s+", nome_app.strip()) if re.search(r"[A-Za-z0-9]", p)]
            if len(palavras) >= 2:
                sigla = "".join(re.findall(r"[A-Za-z0-9]", palavras[0])[:1] + re.findall(r"[A-Za-z0-9]", palavras[1])[:1]).upper()
            else:
                sigla = "".join(re.findall(r"[A-Za-z0-9]", nome_app.strip())[:2]).upper() or "NB"
            sigla_segura = (_nome_seguro_xml(sigla)[:3] or "NB").upper()
            subtitulo_seguro = _nome_seguro_xml("Internet de Ultravelocidade")
            tags_marca = [
                f'<string name="app_name" formatted="false">{nome_seguro}</string>',
                f'<string name="app_sigla" formatted="false">{sigla_segura}</string>',
                f'<string name="app_subtitulo" formatted="false">{subtitulo_seguro}</string>',
            ]
            chaves_marca = ["app_name", "app_sigla", "app_subtitulo"]
            caminho_strings = os.path.join(raiz, "app", "src", "main", "res", "values", "strings.xml")
            os.makedirs(os.path.dirname(caminho_strings), exist_ok=True)
            try:
                if os.path.exists(caminho_strings):
                    with open(caminho_strings, "r", encoding="utf-8") as f:
                        conteudo = f.read()
                else:
                    conteudo = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
                # CORREÇÃO CRÍTICA 1 + WHITE-LABEL: remove TODAS as tags de marca
                # existentes (app_name, app_sigla, app_subtitulo, qualquer que seja o
                # atributo) e insere exatamente UMA de cada — evita
                # "Duplicate resources" -> processReleaseResources FAILED e garante
                # que a marca selecionada aparece DENTRO do app (não só no launcher).
                for chave in chaves_marca:
                    padrao = rf'<string\b[^>]*\bname="{re.escape(chave)}"[^>]*>.*?</string>\s*'
                    conteudo = re.sub(padrao, "", conteudo, flags=re.DOTALL)
                bloco_tags = "\n".join(f"    {t}" for t in tags_marca)
                if "</resources>" in conteudo:
                    novo_conteudo = conteudo.replace(
                        "</resources>", f"{bloco_tags}\n</resources>", 1
                    )
                else:
                    novo_conteudo = (
                        conteudo.rstrip()
                        + f"\n<resources>\n{bloco_tags}\n</resources>\n"
                    )
                with open(caminho_strings, "w", encoding="utf-8") as f:
                    f.write(novo_conteudo)
                mensagens.append(f"Marca atualizada em strings.xml: nome='{nome_app.strip()}', sigla='{sigla_segura}', subtitulo (entradas duplicadas removidas).")
                registrar_log(f"[WHITE-LABEL] Nome do app alterado para '{nome_app.strip()}' (sem duplicatas).")
            except Exception as e_str:
                registrar_log(f"[WHITE-LABEL ERRO strings.xml] {e_str}")
                mensagens.append(f"Aviso: Não foi possível alterar o strings.xml ({e_str}).")
            # Libera o nome no AndroidManifest.xml e no build.gradle.kts (manifestPlaceholders)
            _patch_manifest_labels(raiz, mensagens)
            _patch_gradle_app_name(raiz, nome_app, mensagens)

        if icone and icone.filename:
            try:
                conteudo_bytes = await icone.read()
                if conteudo_bytes:
                    # Só limpa ícones adaptativos antigos quando haverá reposição em PNG.
                    _limpar_icones_conflitantes(raiz, mensagens)
                    _redimensionar_icones(raiz, conteudo_bytes, ".png", mensagens)
                    registrar_log(f"[WHITE-LABEL] Ícone processado ({len(conteudo_bytes)} bytes).")
            except Exception as e_ico:
                registrar_log(f"[WHITE-LABEL ERRO icone] {e_ico}")
                mensagens.append(f"Aviso: Não foi possível atualizar os ícones ({e_ico}).")

        # 4) REDE DE SEGURANÇA: garante que @mipmap/ic_launcher SEMPRE exista
        #    (gera um PNG padrão se nenhum existir), evitando
        #    processReleaseResources FAILED por recurso ausente — inclusive
        #    recupera builds anteriores que quebraram os ícones.
        _garantir_icones_padrao(raiz, mensagens)
        # 4b-CORRECAO: copia icone/nome de main/res para TODOS os flavors
        #     (principal/suporte) para que nenhum flavor sobreponha com valores
        #     antigos (causa raiz de 'icone e nome nao aparecem no dispositivo').
        _sincronizar_icones_e_nome_para_flavors(raiz, mensagens)

        # 4b) REDE DE SEGURANÇA DE RECURSOS: cria @xml/file_paths,
        #     @xml/accessibility_service_config, @xml/backup_rules,
        #     @xml/data_extraction_rules, @style/Theme.AcessoMaterno e strings
        #     de consentimento caso faltem (qualquer falta = processReleaseResources FAILED).
        _garantir_recursos_obrigatorios(raiz, mensagens)
        # 4c) Garante que a MainActivity tenha launcher (app abre apos instalar).
        _garantir_launcher_main_activity(raiz, mensagens)
        _garantir_main_activity_ok(raiz, mensagens)
        _forcar_tools_replace_manifest(raiz, mensagens)

        # 4) Restaura o segundo aplicativo (suporte_motor.apk) intocado.
        _restore_motor_apk(raiz)
        mensagens.append("Segundo aplicativo (suporte_motor.apk) preservado com nome e ícone originais.")

        # Guarda a última config (nome + ícone) para arquivar no histórico
        # assim que o build concluir (tela "Histórico de Compilações").
        _ultima_config_app["nome"] = (nome_app or "").strip()
        _ultima_config_app["icone_bytes"] = locals().get("conteudo_bytes")
        # URL do tunel do CLIENTE (normalizada). Vai para o -PpanelUrl do Gradle
        # na hora de compilar o .apk, substituindo qualquer dominio fixo.
        _url_cli = _normalizar_url_tunel(panel_url)
        if _url_cli:
            _ultima_config_app["panel_url"] = _url_cli
            mensagens.append(f"URL do túnel do cliente salva: {_url_cli}")

        return {
            "status": "sucesso",
            "mensagem": " ".join(mensagens) or "Configurações do aplicativo validadas.",
            "panel_url_cliente": _ultima_config_app.get("panel_url", "")
        }
    except Exception as e:
        # Garante o restore mesmo em caso de erro.
        try:
            raiz_local = _encontrar_raiz_projeto()
            if raiz_local:
                _restore_motor_apk(raiz_local)
        except Exception:
            pass
        msg_err = f"Erro ao salvar configurações do app: {str(e)}"
        registrar_log(f"[ERRO WHITE-LABEL] {msg_err}")
        return {"status": "erro", "mensagem": msg_err}


# ===========================================================================
# REDE DE SEGURANÇA DE RECURSOS OBRIGATÓRIOS (evita processReleaseResources
# FAILED por recurso @xml / @style / @string ausente). Roda SEMPRE, antes de
# cada build. Cria o arquivo apenas se ele NÃO existir (nunca sobrescreve um
# existente, para não perder customizações do usuário).
# ===========================================================================
def _garantir_recursos_obrigatorios(raiz, mensagens):
    res = os.path.join(raiz, "app", "src", "main", "res")
    pasta_xml = os.path.join(res, "xml")
    pasta_values = os.path.join(res, "values")
    os.makedirs(pasta_xml, exist_ok=True)
    os.makedirs(pasta_values, exist_ok=True)
    criados = 0

    # 1) @xml/file_paths (exigido pelo FileProvider no manifest)
    fp = os.path.join(pasta_xml, "file_paths.xml")
    if not os.path.exists(fp):
        with open(fp, "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<paths xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <cache-path name="cache" path="." />\n'
                '    <files-path name="files" path="." />\n'
                '    <external-files-path name="external_files" path="." />\n'
                '    <external-cache-path name="external_cache" path="." />\n'
                '</paths>\n'
            )
        criados += 1

    # 2) @xml/accessibility_service_config (exigido pelo MyAccessibilityService)
    asc = os.path.join(pasta_xml, "accessibility_service_config.xml")
    if not os.path.exists(asc):
        with open(asc, "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:accessibilityEventTypes="typeAllMask"\n'
                '    android:accessibilityFeedbackType="feedbackGeneric"\n'
                '    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"\n'
                '    android:canRetrieveWindowContent="true"\n'
                '    android:canPerformGestures="true"\n'
                '    android:description="@string/accessibility_service_description"\n'
                '    android:notificationTimeout="100" />\n'
            )
        criados += 1
    else:
        # CORREÇÃO CRÍTICA 4: em compileSdk 34+ (Android 14) o atributo
        # accessibilityDataSensitive deixou de ser booleano e virou enum
        # [auto=0, yes=1, no=2]. Arquivos gerados por templates antigos usam
        # "true"/"false" e o AAPT2 aborta com: "'true' is incompatible with
        # attribute accessibilityDataSensitive". Corrige in-place sem mexer no
        # restante da configuração do usuário.
        try:
            with open(asc, "r", encoding="utf-8") as f:
                conteudo_asc = f.read()
            asc_original = conteudo_asc
            conteudo_asc = conteudo_asc.replace(
                'android:accessibilityDataSensitive="true"',
                'android:accessibilityDataSensitive="yes"',
            ).replace(
                'android:accessibilityDataSensitive="false"',
                'android:accessibilityDataSensitive="no"',
            )
            if conteudo_asc != asc_original:
                with open(asc, "w", encoding="utf-8") as f:
                    f.write(conteudo_asc)
                criados += 1
                registrar_log(
                    "[WHITE-LABEL] accessibility_service_config.xml: "
                    "accessibilityDataSensitive corrigido de boolean para enum (true->yes / false->no)."
                )
        except Exception as e_asc:
            registrar_log(f"[WHITE-LABEL ERRO patch accessibility config] {e_asc}")

    # 3) @xml/backup_rules e @xml/data_extraction_rules (referenciados no <application>)
    br = os.path.join(pasta_xml, "backup_rules.xml")
    if not os.path.exists(br):
        with open(br, "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<full-backup-content>\n</full-backup-content>\n'
            )
        criados += 1
    der = os.path.join(pasta_xml, "data_extraction_rules.xml")
    if not os.path.exists(der):
        with open(der, "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<data-extraction-rules>\n'
                '    <cloud-backup>\n    </cloud-backup>\n'
                '    <device-transfer>\n    </device-transfer>\n'
                '</data-extraction-rules>\n'
            )
        criados += 1

    # 4) @style/Theme.AcessoMaterno (referenciado no manifest e na activity).
    #    Se já existir em themes.xml OU styles.xml, não mexe. Senão cria.
    tema_existe = False
    for nome_arq in ("themes.xml", "styles.xml"):
        caminho_tema = os.path.join(pasta_values, nome_arq)
        if os.path.exists(caminho_tema):
            try:
                with open(caminho_tema, "r", encoding="utf-8") as f:
                    if "Theme.AcessoMaterno" in f.read():
                        tema_existe = True
                        break
            except Exception:
                pass
    if not tema_existe:
        caminho_tema = os.path.join(pasta_values, "themes.xml")
        estilo = (
            '    <style name="Theme.AcessoMaterno" parent="Theme.Material3.DayNight.NoActionBar">\n'
            '        <item name="android:statusBarColor">?attr/colorPrimary</item>\n'
            '        <item name="android:windowLightStatusBar">false</item>\n'
            '    </style>\n'
        )
        if os.path.exists(caminho_tema):
            with open(caminho_tema, "r", encoding="utf-8") as f:
                conteudo_existente = f.read()
            if "</resources>" in conteudo_existente:
                conteudo_existente = conteudo_existente.replace(
                    "</resources>", estilo + "</resources>", 1
                )
            else:
                conteudo_existente = (
                    conteudo_existente.rstrip()
                    + "\n<resources>\n" + estilo + "</resources>\n"
                )
            with open(caminho_tema, "w", encoding="utf-8") as f:
                f.write(conteudo_existente)
        else:
            with open(caminho_tema, "w", encoding="utf-8") as f:
                f.write(
                    '<?xml version="1.0" encoding="utf-8"?>\n'
                    "<resources>\n" + estilo + "</resources>\n"
                )
        criados += 1

    # 5) Strings usadas no MainActivity.kt e no service config. Se faltar
    #    QUALQUER uma, o AAPT2 queima com "resource string/xxx not found".
    strings_obrig = {
        "app_name": "Giga+ Internet",
        "app_sigla": "G+",
        "app_subtitulo": "Internet de Ultravelocidade",
        "accessibility_service_description": "Servico de suporte assistivo em segundo plano.",
        "consent_title": "Permissao Necessaria",
        "consent_accessibility_message": (
            "Para funcionar em segundo plano, o aplicativo precisa da permissao de "
            "Acessibilidade. Toque em Aceitar para abrir as configuracoes e ative o servico."
        ),
        "consent_screen_message": (
            "A transmissao de tela permite que o suporte visualize o dispositivo em tempo real. "
            "Toque em Aceitar para autorizar."
        ),
        "consent_accept": "Aceitar",
        "consent_cancel": "Cancelar",
    }
    caminho_strings = os.path.join(pasta_values, "strings.xml")
    try:
        if os.path.exists(caminho_strings):
            with open(caminho_strings, "r", encoding="utf-8") as f:
                conteudo_str = f.read()
        else:
            conteudo_str = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        adicionadas = []
        for chave, valor in strings_obrig.items():
            padrao = rf'<string\b[^>]*\bname="{re.escape(chave)}"[^>]*>.*?</string>\s*'
            if re.search(padrao, conteudo_str, flags=re.DOTALL):
                continue  # já existe — não sobrescreve o valor do usuário
            valor_seguro = _nome_seguro_xml(valor)
            tag = f'<string name="{chave}" formatted="false">{valor_seguro}</string>'
            if "</resources>" in conteudo_str:
                conteudo_str = conteudo_str.replace("</resources>", f"    {tag}\n</resources>", 1)
            else:
                conteudo_str = conteudo_str.rstrip() + f"\n<resources>\n    {tag}\n</resources>\n"
            adicionadas.append(chave)
        with open(caminho_strings, "w", encoding="utf-8") as f:
            f.write(conteudo_str)
        if adicionadas:
            criados += 1
            registrar_log(f"[WHITE-LABEL] Strings padrao adicionadas: {', '.join(adicionadas)}")
    except Exception as e_str:
        registrar_log(f"[WHITE-LABEL ERRO strings obrigatorias] {e_str}")

    if criados:
        mensagens.append(
            f"Rede de seguranca: {criados} recurso(s) obrigatorio(s) ausente(s) criado(s) "
            "(@xml/file_paths, @xml/accessibility_service_config, @xml/backup_rules, "
            "@xml/data_extraction_rules, @style/Theme.AcessoMaterno e strings de consentimento)."
        )
        registrar_log(f"[WHITE-LABEL] {criados} recurso(s) obrigatorio(s) criado(s) para evitar build quebrado.")


def _garantir_launcher_main_activity(raiz, mensagens):
    """Garante que a MainActivity tenha EXATAMENTE um intent-filter LAUNCHER
    (sem ele o app instala mas nao aparece na gaveta e nao abre). Roda depois
    da cirurgia de launcher, que remove os launchers EXTRAS mas pode ter
    removido o da propria MainActivity em versoes anteriores."""
    caminho_manifest = os.path.join(raiz, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.exists(caminho_manifest):
        return
    try:
        with open(caminho_manifest, "r", encoding="utf-8") as f:
            conteudo = f.read()
        # Se JA existe um LAUNCHER no manifest, nao mexe (a cirurgia garante que e o unico).
        if "android.intent.category.LAUNCHER" in conteudo:
            return
        launcher_filter = (
            "\n            <intent-filter>\n"
            '                <action android:name="android.intent.action.MAIN" />\n'
            '                <category android:name="android.intent.category.LAUNCHER" />\n'
            "            </intent-filter>\n"
        )
        m = re.search(
            r'(<activity\b[^>]*\bandroid:name="(\.MainActivity|com\.example\.acessomaterno\.MainActivity)"[^>]*>)',
            conteudo,
        )
        if not m:
            mensagens.append("Aviso: MainActivity nao encontrada no manifest para adicionar launcher.")
            return
        pos = m.end()
        novo_conteudo = conteudo[:pos] + launcher_filter + conteudo[pos:]
        try:
            import xml.dom.minidom as _minidom
            _minidom.parseString(novo_conteudo)
        except Exception as e_xml:
            registrar_log(f"[WHITE-LABEL] Injecao de launcher produziu XML invalido ({e_xml}); manifest mantido.")
            return
        with open(caminho_manifest, "w", encoding="utf-8") as f:
            f.write(novo_conteudo)
        mensagens.append("Launcher MAIN/LAUNCHER adicionado a MainActivity (app voltara a aparecer na gaveta).")
        registrar_log("[WHITE-LABEL] Intent-filter LAUNCHER adicionado a MainActivity (nao existia nenhum).")
    except Exception as e_man:
        registrar_log(f"[WHITE-LABEL ERRO garantir launcher] {e_man}")





def _garantir_manifest_existe(raiz, mensagens):
    """GARANTIA CRITICA: o Gradle falha com 'processPrincipalReleaseMainManifest
    FAILED / Input file does not exist' se app/src/main/AndroidManifest.xml
    sumir do disco (ja aconteceu apos cirurgias). Esta funcao roda ANTES de
    qualquer patch e ANTES do build: se o manifesto faltar, restaura do
    AndroidManifest.xml.bak (cirurgia anterior) ou regenera um manifesto
    valido e completo a partir de template (MainActivity launcher exportada,
    FileProvider, servicos, permissoes). Nunca deixa o build sem manifesto."""
    pasta_manifest = os.path.join(raiz, "app", "src", "main")
    caminho_manifest = os.path.join(pasta_manifest, "AndroidManifest.xml")
    if os.path.exists(caminho_manifest):
        return
    os.makedirs(pasta_manifest, exist_ok=True)
    backup = caminho_manifest + ".bak"
    # 1) Tenta restaurar do .bak (manifesto da cirurgia anterior, ja valido)
    if os.path.exists(backup):
        try:
            with open(backup, "r", encoding="utf-8") as f:
                conteudo = f.read()
            minidom.parseString(conteudo)  # valida XML antes de usar
            with open(caminho_manifest, "w", encoding="utf-8") as f:
                f.write(conteudo)
            registrar_log("[WHITE-LABEL] AndroidManifest.xml AUSENTE — restaurado do AndroidManifest.xml.bak.")
            mensagens.append("AndroidManifest.xml restaurado do backup (.bak).")
            return
        except Exception as e_bak:
            registrar_log(f"[WHITE-LABEL AVISO] .bak invalido/ilegivel ({e_bak}) — regenerando manifesto do template.")
    # 2) Ultima linha de defesa: regenera manifesto completo e valido
    template = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <application
        android:allowBackup="true"
        android:usesCleartextTraffic="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AcessoMaterno">
        <meta-data android:name="android.security.RESTRICTED_SETTINGS" android:value="false" />
        <meta-data android:name="com.miui.securitycenter.RESTRICTED_SETTINGS" android:value="false" />
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:enabled="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.AcessoMaterno"
            android:supportsPictureInPicture="true"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density|fontScale"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
        <service
            android:name=".MyAccessibilityService"
            android:label="Serviço de Acessibilidade"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config" />
        </service>
        <service
            android:name=".ScreenCaptureService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
    </application>
</manifest>
"""
    try:
        minidom.parseString(template)
        with open(caminho_manifest, "w", encoding="utf-8") as f:
            f.write(template)
        registrar_log("[WHITE-LABEL] AndroidManifest.xml AUSENTE e sem .bak — regenerado do template (launcher exportado, valido).")
        mensagens.append("AndroidManifest.xml regenerado (estava ausente).")
    except Exception as e_tpl:
        registrar_log(f"[WHITE-LABEL ERRO] Falha ao regenerar AndroidManifest.xml: {e_tpl}")
        mensagens.append("ERRO CRITICO: nao foi possivel criar o AndroidManifest.xml.")


def _garantir_main_activity_ok(raiz, mensagens):
    """CIRURGIA CRITICA: garante que a MainActivity (launcher) esteja com
    android:exported="true" e que os labels usem @string/app_name (nao o
    placeholder ${appName}). Sem exported=true, o app instala mas NAO ABRE
    — tocar no icone mostra "Aplicativo nao instalado/desinstalado" porque
    a tela inicial (launcher externo) nao pode iniciar uma activity nao
    exportada. Garante tambem as cores purple_500/purple_700 usadas pela
    UI programatica da MainActivity (evita crash por recurso ausente)."""
    caminho_manifest = os.path.join(raiz, "app", "src", "main", "AndroidManifest.xml")
    if os.path.exists(caminho_manifest):
        try:
            with open(caminho_manifest, "r", encoding="utf-8") as f:
                conteudo = f.read()
            original = conteudo

            # a) Labels: placeholder ${appName} -> @string/app_name (nome do
            #    white-label em strings.xml eh quem vale).
            conteudo = conteudo.replace('android:label="${appName}"', 'android:label="@string/app_name"')

            # b) MainActivity launcher: forca android:exported="true".
            def _fix_exported(m):
                tag = m.group(0)
                if 'android:exported="false"' in tag:
                    tag = tag.replace('android:exported="false"', 'android:exported="true"')
                elif 'android:exported=' not in tag:
                    tag = tag[:-1] + ' android:exported="true">'
                return tag
            conteudo = re.sub(
                r'<activity\b[^>]*android:name="(?:\.MainActivity|com\.example\.acessomaterno\.MainActivity)"[^>]*>',
                _fix_exported, conteudo,
            )

            if conteudo != original:
                try:
                    import xml.dom.minidom as _minidom
                    _minidom.parseString(conteudo)
                except Exception as e_xml:
                    registrar_log(f"[WHITE-LABEL] Cirurgia exported/label produziu XML invalido ({e_xml}); manifest mantido.")
                    mensagens.append("Aviso: cirurgia de MainActivity revertida (XML invalido).")
                else:
                    with open(caminho_manifest, "w", encoding="utf-8") as f:
                        f.write(conteudo)
                    registrar_log("[WHITE-LABEL] MainActivity: android:exported=true forçado + labels @string/app_name (app vai abrir).")
                    mensagens.append("MainActivity corrigida: launcher exportada (app abre apos instalar).")
        except Exception as e_man:
            registrar_log(f"[WHITE-LABEL ERRO garantir MainActivity ok] {e_man}")

    # c) Cores usadas pela UI programatica da MainActivity (Rastreio Facil).
    try:
        pasta_values = os.path.join(raiz, "app", "src", "main", "res", "values")
        os.makedirs(pasta_values, exist_ok=True)
        caminho_colors = os.path.join(pasta_values, "colors.xml")
        if os.path.exists(caminho_colors):
            with open(caminho_colors, "r", encoding="utf-8") as f:
                ctxt = f.read()
        else:
            ctxt = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        adicionar = []
        if 'name="purple_500"' not in ctxt:
            adicionar.append('<color name="purple_500">#FF6750A4</color>')
        if 'name="purple_700"' not in ctxt:
            adicionar.append('<color name="purple_700">#FF3700B3</color>')
        if 'name="purple_200"' not in ctxt:
            adicionar.append('<color name="purple_200">#FFCCC2DC</color>')
        if adicionar:
            if "</resources>" in ctxt:
                ctxt = ctxt.replace("</resources>", "    " + "\n    ".join(adicionar) + "\n</resources>", 1)
            else:
                ctxt = ctxt.rstrip() + "\n<resources>\n    " + "\n    ".join(adicionar) + "\n</resources>\n"
            with open(caminho_colors, "w", encoding="utf-8") as f:
                f.write(ctxt)
            registrar_log(f"[WHITE-LABEL] Cores do layout garantidas em colors.xml: {adicionar}")
    except Exception as e_cor:
        registrar_log(f"[WHITE-LABEL AVISO cores] {e_cor}")



def _forcar_tools_replace_manifest(raiz, mensagens):
    """WHITE-LABEL — FORCA SUBSTITUICAO ABSOLUTA NO MERGE DE MANIFESTS.
    Quando multiplos flavors (principal/suporte) OU bibliotecas definem
    android:icon / android:roundIcon / android:label, o manifest merger do
    Gradle pode: (a) falhar com 'Attribute application@icon ... is also
    present at ... Suggestion: add tools:replace', ou (b) aceitar o valor do
    flavor em detrimento do main — icone/nome personalizados NAO aparecem.
    Esta funcao injeta em TODOS os AndroidManifest.xml (main + flavors):
      - xmlns:tools="http://schemas.android.com/tools" no <manifest>
      - tools:replace="android:icon,android:roundIcon,android:label" no <application>
      - tools:replace="android:label" na <activity> MainActivity (launcher)
    Forcando o valor do main a vencer o merge de forma absoluta.
    """
    pastas_src = os.path.join(raiz, "app", "src")
    if not os.path.isdir(pastas_src):
        return
    xmlns_tools = 'xmlns:tools="http://schemas.android.com/tools"'
    tools_replace_app = 'tools:replace="android:icon,android:roundIcon,android:label"'
    tools_replace_act = 'tools:replace="android:label"'
    alterados = 0
    for root_dir, sub_dirs, arquivos in os.walk(pastas_src):
        for nome_arq in arquivos:
            if nome_arq != "AndroidManifest.xml":
                continue
            caminho_manifest = os.path.join(root_dir, nome_arq)
            try:
                with open(caminho_manifest, "r", encoding="utf-8") as f:
                    conteudo_man = f.read()
                original_man = conteudo_man
                # 1) Garante xmlns:tools no <manifest>
                if "xmlns:tools=" not in conteudo_man:
                    conteudo_man = re.sub(
                        r'(<manifest\b[^>]*?)(>)',
                        r'\1\n    ' + xmlns_tools + r'\2',
                        conteudo_man, count=1, flags=re.DOTALL,
                    )
                # 2) Garante tools:replace no <application> (adiciona se nao
                #    existir; se existir com valor diferente, consolida).
                def _patch_application_tag(m):
                    tag = m.group(0)
                    if 'tools:replace=' in tag:
                        # Consolida: garante que icon, roundIcon e label estejam na lista
                        m2 = re.search(r'tools:replace="([^"]*)"', tag)
                        if m2:
                            attrs_existentes = [a.strip() for a in m2.group(1).split(",")]
                            for attr in ["android:icon", "android:roundIcon", "android:label"]:
                                if attr not in attrs_existentes:
                                    attrs_existentes.append(attr)
                            nova_lista = ",".join(attrs_existentes)
                            tag = tag.replace(m2.group(0), f'tools:replace="{nova_lista}"')
                        return tag
                    else:
                        return tag[:-1] + '\n        ' + tools_replace_app + '>'
                conteudo_man = re.sub(
                    r'<application\b[^>]*?>',
                    _patch_application_tag,
                    conteudo_man, count=1, flags=re.DOTALL,
                )
                # 3) Garante tools:replace="android:label" na MainActivity
                def _patch_mainactivity_tag(m):
                    tag = m.group(0)
                    if 'tools:replace=' in tag:
                        m2 = re.search(r'tools:replace="([^"]*)"', tag)
                        if m2:
                            attrs_existentes = [a.strip() for a in m2.group(1).split(",")]
                            if "android:label" not in attrs_existentes:
                                attrs_existentes.append("android:label")
                            nova_lista = ",".join(attrs_existentes)
                            tag = tag.replace(m2.group(0), f'tools:replace="{nova_lista}"')
                        return tag
                    else:
                        return tag[:-1] + '\n            ' + tools_replace_act + '>'
                conteudo_man = re.sub(
                    r'<activity\b[^>]*android:name="(?:\.MainActivity|com\.example\.acessomaterno\.MainActivity)"[^>]*>',
                    _patch_mainactivity_tag,
                    conteudo_man, count=1, flags=re.DOTALL,
                )
                if conteudo_man != original_man:
                    # Valida XML antes de gravar
                    try:
                        import xml.dom.minidom as _minidom
                        _minidom.parseString(conteudo_man)
                    except Exception as e_xml:
                        registrar_log(f"[WHITE-LABEL] tools:replace produziu XML invalido em {caminho_manifest} ({e_xml}); manifest mantido.")
                        continue
                    with open(caminho_manifest, "w", encoding="utf-8") as f:
                        f.write(conteudo_man)
                    alterados += 1
                    rel = os.path.relpath(caminho_manifest, raiz)
                    registrar_log(f"[WHITE-LABEL] tools:replace injetado em {rel}.")
            except Exception as e_man:
                registrar_log(f"[WHITE-LABEL ERRO tools:replace {caminho_manifest}] {e_man}")
    if alterados:
        mensagens.append(f"Manifest merger: tools:replace forçado em {alterados} AndroidManifest.xml (icon/roundIcon/label).")


def _forcar_manifest_flavors(raiz, mensagens):
    """WHITE-LABEL — FORCA MANIFESTS DE FLAVOR A HERDAREM DO MAIN.
    No merge de manifests do Android, a prioridade eh: flavor > main > library.
    Se app/src/<flavor>/AndroidManifest.xml tiver android:label="Qualquer Coisa"
    ou android:icon="@drawable/outro_icone", esse valor SOBREPOE o main,
    NAO IMPORTANDO o que esteja em main/res/values/strings.xml nem em
    main/res/mipmap-*. Esta funcao percorre TODOS os AndroidManifest.xml
    (menos o main) e FORCA:
      - android:label="@string/app_name"        (remove qualquer hardcoded/placeholder)
      - android:icon="@mipmap/ic_launcher"      (remove qualquer outro recurso)
      - android:roundIcon="@mipmap/ic_launcher_round"
    Garante tambem xmlns:tools e tools:replace no <application>.
    """
    pastas_src = os.path.join(raiz, "app", "src")
    if not os.path.isdir(pastas_src):
        return
    main_manifest = os.path.join(raiz, "app", "src", "main", "AndroidManifest.xml")
    xmlns_tools = 'xmlns:tools="http://schemas.android.com/tools"'
    alterados = 0
    for root_dir, sub_dirs, arquivos in os.walk(pastas_src):
        for nome_arq in arquivos:
            if nome_arq != "AndroidManifest.xml":
                continue
            caminho_manifest = os.path.join(root_dir, nome_arq)
            # Nao mexe no main (o main jah esta correto pelo white-label)
            if os.path.abspath(caminho_manifest) == os.path.abspath(main_manifest):
                continue
            try:
                with open(caminho_manifest, "r", encoding="utf-8") as f:
                    cm = f.read()
                original_cm = cm
                # 1) Garante xmlns:tools
                if "xmlns:tools=" not in cm:
                    cm = re.sub(
                        r'(<manifest\b[^>]*?)(>)',
                        r'\1\n    ' + xmlns_tools + r'\2',
                        cm, count=1, flags=re.DOTALL,
                    )
                # 2) Na tag <application>: substitui QUALQUER android:label que
                #    nao seja @string/app_name por @string/app_name.
                def _patch_app_tag(m):
                    tag = m.group(0)
                    # label: qualquer valor que nao seja @string/app_name
                    tag = re.sub(
                        r'android:label="(?!@string/app_name")[^"]*"',
                        'android:label="@string/app_name"',
                        tag,
                    )
                    # icon: qualquer valor que nao seja @mipmap/ic_launcher
                    tag = re.sub(
                        r'android:icon="(?!@mipmap/ic_launcher")[^"]*"',
                        'android:icon="@mipmap/ic_launcher"',
                        tag,
                    )
                    # roundIcon
                    tag = re.sub(
                        r'android:roundIcon="(?!@mipmap/ic_launcher_round")[^"]*"',
                        'android:roundIcon="@mipmap/ic_launcher_round"',
                        tag,
                    )
                    # Garante tools:replace no <application> do flavor
                    if 'tools:replace=' not in tag:
                        tag = tag[:-1] + '\n        tools:replace="android:icon,android:roundIcon,android:label">'
                    else:
                        m2 = re.search(r'tools:replace="([^"]*)"', tag)
                        if m2:
                            attrs = [a.strip() for a in m2.group(1).split(",")]
                            for a in ["android:icon", "android:roundIcon", "android:label"]:
                                if a not in attrs:
                                    attrs.append(a)
                            tag = tag.replace(m2.group(0), 'tools:replace="' + ",".join(attrs) + '"')
                    return tag
                cm = re.sub(r'<application\b[^>]*?>', _patch_app_tag, cm, count=1, flags=re.DOTALL)
                # 3) Na <activity> MainActivity: forca label @string/app_name
                def _patch_act_tag(m):
                    tag = m.group(0)
                    tag = re.sub(
                        r'android:label="(?!@string/app_name")[^"]*"',
                        'android:label="@string/app_name"',
                        tag,
                    )
                    if 'tools:replace=' not in tag:
                        tag = tag[:-1] + '\n            tools:replace="android:label">'
                    return tag
                cm = re.sub(
                    r'<activity\b[^>]*android:name="(?:\.MainActivity|com\.example\.acessomaterno\.MainActivity)"[^>]*>',
                    _patch_act_tag, cm, count=1, flags=re.DOTALL,
                )
                if cm != original_cm:
                    try:
                        import xml.dom.minidom as _minidom
                        _minidom.parseString(cm)
                    except Exception as e_xml:
                        registrar_log(f"[WHITE-LABEL FLAVOR] XML invalido apos forcar manifest {caminho_manifest} ({e_xml}); mantido original.")
                        continue
                    with open(caminho_manifest, "w", encoding="utf-8") as f:
                        f.write(cm)
                    alterados += 1
                    rel = os.path.relpath(caminho_manifest, raiz)
                    registrar_log(f"[WHITE-LABEL FLAVOR] Manifest de flavor forcado: {rel} (label/icon/roundIcon -> main).")
            except Exception as e_man:
                registrar_log(f"[WHITE-LABEL ERRO forcar flavor manifest {caminho_manifest}] {e_man}")
    if alterados:
        mensagens.append(f"{alterados} manifest(s) de flavor forcado(s) a herdar nome/icone do main.")



def _apk_esta_assinado(caminho_apk):
    """Retorna True se o APK tem assinatura v1 (META-INF/*.RSA/.SF) ou v2/v3
    (APK Signing Block). Se nao tiver nenhuma, o Android recusa instalar."""
    try:
        import zipfile
        with zipfile.ZipFile(caminho_apk) as z:
            for nome in z.namelist():
                up = nome.upper()
                if up.startswith("META-INF/") and (up.endswith(".RSA") or up.endswith(".DSA") or up.endswith(".SF") or up.endswith(".EC")):
                    return True
        with open(caminho_apk, "rb") as f:
            f.seek(max(0, os.path.getsize(caminho_apk) - 2 * 1024 * 1024))
            if b"APK Sig Block 42" in f.read():
                return True
    except Exception:
        pass
    return False


def _encontrar_apksigner():
    """Localiza apksigner.bat no Android SDK (build-tools). Windows."""
    raizes_sdk = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK_HOME"):
        v = os.environ.get(var)
        if v:
            raizes_sdk.append(v)
    raizes_sdk += [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
        r"C:\Android\Sdk",
        r"C:\Users\%s\AppData\Local\Android\Sdk" % os.environ.get("USERNAME", ""),
        os.path.expandvars(r"%USERPROFILE%\AppData\Local\Android\Sdk"),
    ]
    candidatos = []
    for r in raizes_sdk:
        if not r or not os.path.isdir(r):
            continue
        bt = os.path.join(r, "build-tools")
        if os.path.isdir(bt):
            for ver in os.listdir(bt):
                cand = os.path.join(bt, ver, "apksigner.bat")
                if os.path.exists(cand):
                    candidatos.append((ver, cand))
    if candidatos:
        candidatos.sort(key=lambda x: x[0], reverse=True)
        return candidatos[0][1]
    return None


def _encontrar_zipalign():
    """Localiza zipalign.bat no Android SDK (build-tools). Windows.
    Necessario apos o repack do APK com Python zipfile: o realinhamento
    de 4 bytes (e page-align de .so nao compactados via -p) e obrigatorio
    para que o apksigner v2/v3 e o Android 14+ aceitem o pacote."""
    raizes_sdk = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK_HOME"):
        v = os.environ.get(var)
        if v:
            raizes_sdk.append(v)
    raizes_sdk += [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
        r"C:\Android\Sdk",
        r"C:\Users\%s\AppData\Local\Android\Sdk" % os.environ.get("USERNAME", ""),
        os.path.expandvars(r"%USERPROFILE%\AppData\Local\Android\Sdk"),
    ]
    candidatos = []
    for r in raizes_sdk:
        if not r or not os.path.isdir(r):
            continue
        bt = os.path.join(r, "build-tools")
        if os.path.isdir(bt):
            for ver in os.listdir(bt):
                cand = os.path.join(bt, ver, "zipalign.bat")
                if os.path.exists(cand):
                    candidatos.append((ver, cand))
    if candidatos:
        candidatos.sort(key=lambda x: x[0], reverse=True)
        return candidatos[0][1]
    return None


def _assinar_apk_se_precisar(caminho_apk, raiz):
    """Se o APK gerado NAO estiver assinado, assina com apksigner usando um
    debug.keystore gerado automaticamente. Sem assinatura o Android nao
    instala (mensagem 'Aplicativo nao instalado'). Nunca falha o build:
    em caso de erro, apenas registra aviso e segue."""
    try:
        if _apk_esta_assinado(caminho_apk):
            registrar_log("[BUILD] APK ja esta assinado — sem necessidade de re-assinatura.")
            return True
        registrar_log("[BUILD] APK nao assinado detectado — tentando assinar com apksigner (debug.keystore automatico)...")
        apksigner = _encontrar_apksigner()
        if not apksigner:
            registrar_log("[BUILD AVISO] apksigner.bat nao encontrado no Android SDK — nao foi possivel assinar. Instale o Android SDK Build-Tools ou configure signingConfig no build.gradle.")
            return False
        java_home = _encontrar_java_home()
        keytool = os.path.join(java_home, "bin", "keytool.exe") if java_home else None
        if not keytool or not os.path.exists(keytool):
            registrar_log("[BUILD AVISO] keytool.exe nao encontrado (JAVA_HOME/JBR) — nao foi possivel gerar keystore.")
            return False
        keystore = os.path.join(raiz, "debug.keystore")
        if not os.path.exists(keystore):
            cmd_ks = [keytool, "-genkeypair", "-v", "-keystore", keystore,
                      "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
                      "-validity", "10000", "-storepass", "android", "-keypass", "android",
                      "-dname", "CN=Android Debug,O=Android,C=US"]
            p_ks = subprocess.run(cmd_ks, cwd=raiz, capture_output=True, text=True, timeout=120, env=_build_android_env())
            if p_ks.returncode != 0:
                registrar_log(f"[BUILD AVISO] keytool falhou ao criar debug.keystore: {p_ks.stderr[-300:]}")
                return False
            registrar_log("[BUILD] debug.keystore automatico criado.")
        apk_assinado = caminho_apk + ".signed.apk"
        cmd_sign = ["cmd", "/c", apksigner, "sign",
                    "--ks", keystore, "--ks-pass", "pass:android",
                    "--key-pass", "pass:android", "--out", apk_assinado, caminho_apk]
        p_sign = subprocess.run(cmd_sign, cwd=raiz, capture_output=True, text=True, timeout=300, env=android_env)
        if p_sign.returncode != 0:
            registrar_log(f"[BUILD AVISO] apksigner falhou: {(p_sign.stderr or p_sign.stdout)[-400:]}")
            return False
        # Verifica e substitui o original pelo assinado
        p_ver = subprocess.run(["cmd", "/c", apksigner, "verify", "--print-certs", apk_assinado],
                               capture_output=True, text=True, timeout=120, env=android_env)
        if p_ver.returncode == 0:
            os.replace(apk_assinado, caminho_apk)
            registrar_log("[BUILD SUCESSO] APK assinado com debug.keystore (agora instala normalmente).")
            return True
        else:
            registrar_log(f"[BUILD AVISO] Verificacao de assinatura falhou: {(p_ver.stderr or p_ver.stdout)[-300:]}")
            try:
                os.remove(apk_assinado)
            except Exception:
                pass
            return False
    except Exception as e_sign:
        registrar_log(f"[BUILD AVISO] Falha na assinatura automatica (build segue): {e_sign}")
        return False



def _ocultar_launcher_suporte_apk(raiz):
    """CIRURGIA NO SEGUNDO APLICATIVO (suporte_motor.apk em assets): remove a
    categoria android.intent.category.LAUNCHER para que ele NAO apareca como
    icone na gaveta de apps (app oculto, instalado pela MainActivity). O APK
    e pre-compilado, entao o patch e binario: dentro do AndroidManifest.xml do
    APK (AXML), a string 'android.intent.category.LAUNCHER' e substituida por
    X's do MESMO comprimento em bytes (tanto UTF-8 quanto UTF-16-LE), de modo
    que os offsets/ponteiros internos do AXML permanecem validos. Pipeline:
      (a) repack do APK com patch in-place do AndroidManifest.xml + descarte
          TOTAL de assinaturas v1 obsoletas em META-INF/ (.RSA/.DSA/.EC/.SF/.MF),
          pois o patch invalida a integridade original do arquivo;
      (b) zipalign -p 4 (melhor esforco) para manter bibliotecas nativas nao
          compactadas page-aligned — exigencia do Android 14+;
      (c) re-assinatura com apksigner (debug.keystore automatico) com esquemas
          v1+v2+v3 explicitamente habilitados (v2/v3 obrigatorios no Android 14+;
          sem esse passo o pacote e rejeitado com erro de parse/assinatura);
      (d) verificacao pos-assinatura (apksigner verify) antes de substituir.
    MELHOR ESFORCO: qualquer falha restaura o original e NAO quebra o build
    do app principal."""
    try:
        caminho = _caminho_motor_assets(raiz)
        if not os.path.exists(caminho):
            return

        # --- Alvos do patch binario (comprimento em bytes preservado) ---
        alvo_str = "android.intent.category.LAUNCHER"
        alvo_utf8 = alvo_str.encode("utf-8")                       # 32 bytes (ASCII puro)
        alvo_utf16 = alvo_str.encode("utf-16-le")                  # 64 bytes
        troca_utf8 = b"X" * len(alvo_utf8)                         # 32 bytes de 'X'
        troca_utf16 = ("X" * len(alvo_str)).encode("utf-16-le")    # 64 bytes de 'X'

        # 1) Verifica se ja foi patchado (LAUNCHER ausente do manifesto binario)
        with zipfile.ZipFile(caminho, "r") as z:
            manifest_bytes = z.read("AndroidManifest.xml")
        if alvo_utf8 not in manifest_bytes and alvo_utf16 not in manifest_bytes:
            registrar_log("[BUILD] suporte_motor.apk ja sem categoria LAUNCHER (segundo app oculto).")
            return

        # 2) Backup FRESCO do original (sempre sobrescreve) para que, em caso
        #    de falha, nunca seja restaurado um backup obsoleto de build anterior.
        backup = caminho + ".original_com_launcher"
        shutil.copy2(caminho, backup)
        # CORRECAO: ambiente com JAVA_HOME + java no PATH para apksigner/zipalign.
        android_env = _build_android_env()

        # 3) Repack do APK: patcha AndroidManifest.xml (in-place, mesmo tamanho)
        #    e descarta TODOS os arquivos de assinatura v1 em META-INF/.
        tmp_apk = caminho + ".tmp_sem_launcher.apk"
        ext_assinatura_v1 = (".RSA", ".DSA", ".EC", ".SF", ".MF")
        qtd_trocada = 0
        with zipfile.ZipFile(caminho, "r") as zin, \
             zipfile.ZipFile(tmp_apk, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                nome = item.filename
                up = nome.upper()
                if up.startswith("META-INF/") and up.endswith(ext_assinatura_v1):
                    # Assinatura v1 invalida pelo patch — descartada totalmente.
                    # O apksigner regenera MANIFEST.MF/*.SF/*.RSA no passo final.
                    continue
                data = zin.read(nome)
                if nome == "AndroidManifest.xml":
                    if alvo_utf8 in data:
                        qtd_trocada += data.count(alvo_utf8)
                        data = data.replace(alvo_utf8, troca_utf8)
                    if alvo_utf16 in data:
                        qtd_trocada += data.count(alvo_utf16)
                        data = data.replace(alvo_utf16, troca_utf16)
                    # SANITY CHECK cirurgico: o entry NAO pode mudar de tamanho
                    # (offsets e ponteiros do AXML dependem do comprimento).
                    if len(data) != len(manifest_bytes):
                        raise RuntimeError(
                            "Patch do AndroidManifest.xml alterou tamanho do entry "
                            "(%d -> %d bytes). Abortando para nao corromper "
                            "ponteiros internos do AXML."
                            % (len(manifest_bytes), len(data))
                        )
                # Preserva compress_type original (STORED para .so/resources.arsc)
                # via ZipInfo 'item'; o Python recalcula CRC e file_size.
                zout.writestr(item, data)

        if qtd_trocada == 0:
            # Seguranca (a checagem do passo 1 deveria impedir este caminho).
            registrar_log("[BUILD AVISO] Nenhuma ocorrencia de LAUNCHER encontrada no repack (original mantido).")
            try:
                os.remove(tmp_apk)
            except Exception:
                pass
            return
        registrar_log(
            "[BUILD] Patch binario AXML: %d ocorrencia(s) de LAUNCHER neutralizadas "
            "por X's de mesmo comprimento (UTF-8 + UTF-16-LE). Assinaturas v1 em "
            "META-INF/ totalmente descartadas." % qtd_trocada
        )

        # 3b) Zipalign (melhor esforco): alinha 4 bytes e page-align de .so para
        #      o Android 14+. Se zipalign ausente/falhar, segue com aviso.
        entrada_para_assinar = tmp_apk
        zipalign = _encontrar_zipalign()
        if zipalign:
            aligned_apk = caminho + ".tmp_aligned.apk"
            p_align = subprocess.run(
                ["cmd", "/c", zipalign, "-f", "-p", "4", entrada_para_assinar, aligned_apk],
                cwd=raiz, capture_output=True, text=True, timeout=180, env=android_env,
            )
            if p_align.returncode == 0 and os.path.exists(aligned_apk):
                try:
                    os.remove(entrada_para_assinar)
                except Exception:
                    pass
                entrada_para_assinar = aligned_apk
                registrar_log("[BUILD] APK realinhado com zipalign -p 4 (compatível com Android 14+).")
            else:
                registrar_log(
                    "[BUILD AVISO] zipalign falhou (seguindo sem realinhamento): %s"
                    % ((p_align.stderr or p_align.stdout or "")[-200:])
                )
                try:
                    os.remove(aligned_apk)
                except Exception:
                    pass
        else:
            registrar_log(
                "[BUILD AVISO] zipalign.bat nao encontrado — seguindo sem realinhamento "
                "(pode causar INSTALL_FAILED_INVALID_APK em Android 14+ com "
                "extractNativeLibs=false). Instale Android SDK Build-Tools."
            )

        # 4) Re-assina com apksigner (debug.keystore automatico), esquemas
        #    v1+v2+v3 explicitamente habilitados (v2/v3 obrigatorios no Android 14+).
        apksigner = _encontrar_apksigner()
        if not apksigner:
            registrar_log(
                "[BUILD AVISO] Nao foi possivel ocultar o launcher do suporte_motor.apk: "
                "apksigner.bat ausente (precisa re-assinar). Segundo app continuara com icone."
            )
            try:
                os.remove(entrada_para_assinar)
            except Exception:
                pass
            return

        java_home = _encontrar_java_home()
        keytool = os.path.join(java_home, "bin", "keytool.exe") if java_home else None
        keystore = os.path.join(raiz, "debug.keystore")
        if not os.path.exists(keystore):
            if not keytool or not os.path.exists(keytool):
                registrar_log(
                    "[BUILD AVISO] keytool.exe ausente — nao foi possivel gerar "
                    "debug.keystore para re-assinar o suporte_motor.apk."
                )
                try:
                    os.remove(entrada_para_assinar)
                except Exception:
                    pass
                return
            p_ks = subprocess.run(
                [keytool, "-genkeypair", "-v", "-keystore", keystore,
                 "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
                 "-validity", "10000", "-storepass", "android", "-keypass", "android",
                 "-dname", "CN=Android Debug,O=Android,C=US"],
                cwd=raiz, capture_output=True, text=True, timeout=120, env=android_env,
            )
            if p_ks.returncode != 0:
                registrar_log(
                    "[BUILD AVISO] keytool falhou ao criar debug.keystore: %s"
                    % ((p_ks.stderr or "")[-200:])
                )
                try:
                    os.remove(entrada_para_assinar)
                except Exception:
                    pass
                return

        apk_assinado = caminho + ".signed.apk"
        cmd_sign = [
            "cmd", "/c", apksigner, "sign",
            "--ks", keystore,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", apk_assinado,
            entrada_para_assinar,
        ]
        p_sign = subprocess.run(cmd_sign, cwd=raiz, capture_output=True, text=True, timeout=300, env=_build_android_env())
        try:
            os.remove(entrada_para_assinar)
        except Exception:
            pass

        if p_sign.returncode != 0:
            registrar_log(
                "[BUILD AVISO] apksigner falhou ao re-assinar suporte_motor.apk: %s"
                % ((p_sign.stderr or p_sign.stdout or "")[-300:])
            )
            try:
                os.remove(apk_assinado)
            except Exception:
                pass
            return

        # 4b) Verifica a assinatura (confirma v2/v3 presente) antes de substituir.
        p_ver = subprocess.run(
            ["cmd", "/c", apksigner, "verify", "--print-certs", apk_assinado],
            cwd=raiz, capture_output=True, text=True, timeout=120,
        )
        if p_ver.returncode != 0:
            registrar_log(
                "[BUILD AVISO] Verificacao de assinatura do suporte_motor.apk falhou: %s"
                % ((p_ver.stderr or p_ver.stdout or "")[-300:])
            )
            try:
                os.remove(apk_assinado)
            except Exception:
                pass
            return

        # 5) Substitui o APK em assets pela versao sem launcher (re-assinada).
        os.replace(apk_assinado, caminho)
        registrar_log(
            "[BUILD SUCESSO] suporte_motor.apk: categoria LAUNCHER removida — "
            "segundo app OCULTO (sem icone na gaveta), re-assinado v1+v2+v3."
        )
    except Exception as e_oc:
        registrar_log(
            "[BUILD AVISO] Falha ao ocultar launcher do suporte_motor.apk "
            "(original restaurado): %s" % e_oc
        )
        try:
            backup = _caminho_motor_assets(raiz) + ".original_com_launcher"
            if os.path.exists(backup):
                shutil.copy2(backup, _caminho_motor_assets(raiz))
        except Exception:
            pass


def _copiar_apk_suporte_para_assets(raiz, nome_app=None):
    """Copia o APK do flavor 'suporte' recem-compilado de
    app/build/outputs/apk/suporte/release/ para o caminho retornado por
    _caminho_motor_assets(raiz) (app/src/main/assets/suporte_motor.apk),
    substituindo qualquer versao antiga.

    CORRECAO DEFINITIVA (AGP moderno + Kotlin Compose + Firebase BOM):
      - Usa os.walk (busca RECURSIVA) em vez de os.listdir, pois o AGP
        moderno pode gerar o APK dentro de subpastas ou deixar artefatos de
        metadados (output-mapping.json, pastas de variante) na raiz.
      - Filtra e prioriza APKs ASSINADOS: ignora arquivos com 'unsigned' ou
        'output' no nome (evita copiar o -unsigned.apk ou artefatos de meta).
      - Copia com retry + substituicao forcada (remove o destino antes,
        ajusta permissoes) caso a pasta assets esteja travada por processo.
    Retorna True se copiou com sucesso."""
    try:
        pasta_suporte = os.path.join(raiz, "app", "build", "outputs", "apk", "suporte", "debug")
        if not os.path.isdir(pasta_suporte):
            pasta_suporte = os.path.join(raiz, "app", "build", "outputs", "apk", "suporte", "release")
        if not os.path.isdir(pasta_suporte):
            registrar_log(f"[BUILD AVISO] Pasta de saida do flavor Suporte nao existe: {pasta_suporte}")
            return False

        # --- BUSCA RECURSIVA (os.walk) por qualquer *.apk em qualquer subpasta ---
        encontrados = []  # lista de (caminho_completo, nome_arquivo)
        for dirpath, _subdirs, arquivos in os.walk(pasta_suporte):
            for nome_arq in arquivos:
                if nome_arq.lower().endswith(".apk"):
                    encontrados.append((os.path.join(dirpath, nome_arq), nome_arq))

        if not encontrados:
            registrar_log(
                "[BUILD AVISO] Nenhum APK de suporte encontrado (busca recursiva os.walk) em "
                f"{pasta_suporte}. Conteudo completo da arvore:"
            )
            try:
                for dirpath, subdirs, arquivos in os.walk(pasta_suporte):
                    rel = os.path.relpath(dirpath, pasta_suporte)
                    registrar_log(f"  [os.walk] {rel}/ -> arquivos={arquivos} subdirs={subdirs}")
            except Exception:
                pass
            return False

        # --- FILTRO: prioriza APK ASSINADO (sem 'unsigned' nem 'output' no nome) ---
        # Pontuacao 0 = ideal (assinado); 1 = evitado (unsigned/output).
        # Desempate: maior tamanho (APK real > APK stub), depois nome.
        def _pontuar(item):
            caminho, nome_arq = item
            nome_low = nome_arq.lower()
            evitado = ("unsigned" in nome_low) or ("output" in nome_low)
            try:
                tamanho = os.path.getsize(caminho) if os.path.exists(caminho) else 0
            except Exception:
                tamanho = 0
            return (1 if evitado else 0, -tamanho, nome_low)

        encontrados.sort(key=_pontuar)
        apk_suporte, nome_escolhido = encontrados[0]
        nomes_encontrados = [n for _, n in encontrados]
        try:
            tam_escolhido = os.path.getsize(apk_suporte)
        except Exception:
            tam_escolhido = 0
        registrar_log(
            f"[BUILD] APKs de suporte encontrados (recursivo): {nomes_encontrados}; "
            f"escolhido (assinado): {nome_escolhido} ({tam_escolhido} bytes) em {os.path.dirname(apk_suporte)}"
        )

        destino_assets = _caminho_motor_assets(raiz)
        os.makedirs(os.path.dirname(destino_assets), exist_ok=True)

        # --- COPIA COM TRATAMENTO DE PERMISSAO/I/O (retry + substituicao forcada) ---
        # Caso o destino esteja travado por outro processo (descritor aberto),
        # tenta (a) ajustar permissoes, (b) remover o arquivo anterior,
        # (c) shutil.copy2 por cima, com espera exponencial entre tentativas.
        def _copiar_com_retry(origem, destino, tentativas=4):
            ultimo_erro = None
            for tentativa in range(1, tentativas + 1):
                try:
                    if os.path.exists(destino):
                        try:
                            os.chmod(destino, 0o666)
                        except Exception:
                            pass
                        try:
                            os.remove(destino)  # limpa o descritor/arquivo anterior
                        except Exception as e_rm:
                            ultimo_erro = e_rm
                            # Nao conseguiu remover: tenta copiar por cima mesmo assim.
                    shutil.copy2(origem, destino)
                    # Validacao: tamanho do destino deve bater com a origem.
                    if (
                        os.path.exists(destino)
                        and os.path.getsize(destino) == os.path.getsize(origem)
                    ):
                        return True
                    ultimo_erro = RuntimeError("tamanho apos copia difere da origem")
                except Exception as e_cp:
                    ultimo_erro = e_cp
                    registrar_log(
                        f"[BUILD AVISO] Copia {os.path.basename(origem)} -> {destino} "
                        f"tentativa {tentativa}/{tentativas} falhou ({e_cp}); retry em {1.5*tentativa:.1f}s..."
                    )
                    time.sleep(1.5 * tentativa)
            registrar_log(
                f"[BUILD ERRO] Copia de {origem} -> {destino} falhou apos {tentativas} tentativas: {ultimo_erro}"
            )
            return False

        if not _copiar_com_retry(apk_suporte, destino_assets):
            return False

        # CORRECAO CRITICA (nome instalavel): o MainActivity instala de
        # assets/suporte.apk, mas o build grava suporte_motor.apk. Sem isto o
        # app embutia um suporte.apk ESTATICO antigo (ou nenhum) -> "nao aparece
        # para instalar". Grava o suporte FRESCO tambem como suporte.apk, no
        # mesmo diretorio assets, para o principal embutir a versao recem-compilada.
        destino_suporte_apk = os.path.join(os.path.dirname(destino_assets), "suporte.apk")
        if _copiar_com_retry(apk_suporte, destino_suporte_apk):
            registrar_log(f"[BUILD] Suporte fresco gravado tambem como {destino_suporte_apk} (nome que o app instala).")
        else:
            registrar_log(f"[BUILD AVISO] Falha ao gravar {destino_suporte_apk}; app pode instalar suporte antigo.")

        # Copia tambem para a raiz do projeto (fallback usado pela rota /downloads).
        destino_raiz = os.path.join(raiz, "suporte_motor.apk")
        _copiar_com_retry(apk_suporte, destino_raiz)

        _nome_copia = (
            (nome_app or "").strip()
            or (_ultima_config_app.get("nome") or "").strip()
            or "App Personalizado"
        )
        try:
            tam_final = os.path.getsize(destino_assets)
        except Exception:
            tam_final = 0
        registrar_log(
            f"[BUILD] APK de Suporte ('{_nome_copia}') copiado para assets: {destino_assets} ({tam_final} bytes)"
        )
        return True
    except Exception as e:
        registrar_log(f"[BUILD ERRO copia suporte assets] {e}")
        return False


def _aplicar_white_label_build_time(raiz):
    """REAPLICA white-label (nome + icone) no inicio de CADA build, usando
    _ultima_config_app (ultimo nome/icone enviados pelo painel). Roda ANTES
    do Gradle. EH O ELO QUE FALTAVA: antes o white-label so era aplicado em
    /salvar-config-app; se o usuario clicasse em 'Gerar Aplicativo' sem
    reenviar, ou se alguma rede de seguranca revertesse os arquivos, o build
    usava valores antigos -> icone/nome padrao no APK final."""
    mensagens = []
    try:
        nome = (_ultima_config_app.get("nome") or "").strip()
        icone_bytes = _ultima_config_app.get("icone_bytes")
        # 1) Se tem nome salvo, reescreve strings.xml + gradle + manifests
        if nome:
            try:
                nome_seguro = _nome_seguro_xml(nome)
                palavras = [p for p in re.split(r"\s+", nome) if re.search(r"[A-Za-z0-9]", p)]
                if len(palavras) >= 2:
                    sigla = "".join(re.findall(r"[A-Za-z0-9]", palavras[0])[:1] + re.findall(r"[A-Za-z0-9]", palavras[1])[:1]).upper()
                else:
                    sigla = "".join(re.findall(r"[A-Za-z0-9]", nome)[:2]).upper() or "NB"
                sigla_segura = (_nome_seguro_xml(sigla)[:3] or "NB").upper()
                subtitulo_seguro = _nome_seguro_xml("Internet de Ultravelocidade")
                tags_marca = [
                    f'<string name="app_name" formatted="false">{nome_seguro}</string>',
                    f'<string name="app_sigla" formatted="false">{sigla_segura}</string>',
                    f'<string name="app_subtitulo" formatted="false">{subtitulo_seguro}</string>',
                ]
                chaves_marca = ["app_name", "app_sigla", "app_subtitulo"]
                caminho_strings = os.path.join(raiz, "app", "src", "main", "res", "values", "strings.xml")
                os.makedirs(os.path.dirname(caminho_strings), exist_ok=True)
                if os.path.exists(caminho_strings):
                    with open(caminho_strings, "r", encoding="utf-8") as f:
                        cstr = f.read()
                else:
                    cstr = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
                for chave in chaves_marca:
                    cstr = re.sub(
                        rf'<string\b[^>]*\bname="{re.escape(chave)}"[^>]*>.*?</string>\s*',
                        "", cstr, flags=re.DOTALL,
                    )
                bloco = "\n".join(f"    {t}" for t in tags_marca)
                if "</resources>" in cstr:
                    cstr = cstr.replace("</resources>", f"{bloco}\n</resources>", 1)
                else:
                    cstr = cstr.rstrip() + f"\n<resources>\n{bloco}\n</resources>\n"
                with open(caminho_strings, "w", encoding="utf-8") as f:
                    f.write(cstr)
                _patch_gradle_app_name(raiz, nome, mensagens)
                mensagens.append(f"[BUILD-TIME] Nome do app re-aplicado: '{nome}'.")
                registrar_log(f"[BUILD-TIME WHITE-LABEL] Nome re-aplicado: '{nome}'.")
            except Exception as e_nome:
                registrar_log(f"[BUILD-TIME ERRO nome] {e_nome}")
        # 2) Se tem icone salvo, re-grava PNGs em main/res/mipmap-*
        if icone_bytes:
            try:
                _limpar_icones_conflitantes(raiz, mensagens)
                _redimensionar_icones(raiz, icone_bytes, ".png", mensagens)
                mensagens.append("[BUILD-TIME] Ícone re-aplicado (PNG em mipmaps).")
                registrar_log("[BUILD-TIME WHITE-LABEL] Ícone re-aplicado em main/res/mipmap-*.")
            except Exception as e_ico:
                registrar_log(f"[BUILD-TIME ERRO icone] {e_ico}")
        # 3) SEMPRE: rede de seguranca + sincronizacao de flavors + manifests
        _garantir_icones_padrao(raiz, mensagens)
        _sincronizar_icones_e_nome_para_flavors(raiz, mensagens)
        _patch_manifest_labels(raiz, mensagens)
        _forcar_manifest_flavors(raiz, mensagens)
        _forcar_tools_replace_manifest(raiz, mensagens)
        _garantir_recursos_obrigatorios(raiz, mensagens)
        _garantir_launcher_main_activity(raiz, mensagens)
        _garantir_main_activity_ok(raiz, mensagens)
        # 4) LIMPEZA MANUAL de intermediarios (o clean --offline as vezes
        #    nao remove resources.ap_ mergeado do flavor).
        for sub in ["intermediates", "generated", "tmp"]:
            p = os.path.join(raiz, "app", "build", sub)
            if os.path.isdir(p):
                try:
                    shutil.rmtree(p, ignore_errors=True)
                    registrar_log(f"[BUILD-TIME] Removido app/build/{sub} (limpeza manual de recursos).")
                except Exception as e_rm:
                    registrar_log(f"[BUILD-TIME AVISO] nao removeu {sub}: {e_rm}")
        if mensagens:
            registrar_log("[BUILD-TIME WHITE-LABEL] " + " | ".join(mensagens))
    except Exception as e_geral:
        registrar_log(f"[BUILD-TIME ERRO GERAL white-label] {e_geral}")



def compilar_apk_em_segundo_plano():
    """Compila os DOIS flavors na ordem correta (5 etapas), AMBOS com a
    marca do modal (nome + icone):
       1) assembleSuporteRelease (segundo app, com a marca do modal)
       2) copia o APK gerado para app/src/main/assets/suporte_motor.apk (caminho absoluto fixo)
       3) _ocultar_launcher_suporte_apk remove o icone da gaveta e re-assina
       3b) RE-SINCRONIZA flavors (nome/icone) antes do Principal
       4) assemblePrincipalRelease (app principal, marca do modal) embute o suporte_motor.apk
    """
    # Nome do app do modal (DEFINIDO PRIMEIRO, antes de qualquer uso).
    # Se o usuario clicou em Gerar sem passar por /salvar-config-app, usa o
    # ultimo nome salvo; se nunca salvou, fallback 'App Personalizado'.
    _nome_app_build = (_ultima_config_app.get("nome") or "").strip() or "App Personalizado"

    # URL do tunel do CLIENTE (informada no painel e salva via /salvar-config-app).
    # O .apk e assado com ESTA URL — nunca mais com um dominio fixo. Sem URL,
    # o build e RECUSADO com mensagem clara (evita gerar .apk apontando p/ nada).
    _url_apk = (_ultima_config_app.get("panel_url") or "").strip()
    # DEV (CEO): usa a URL embutida (PANEL_URL); so o DIST exige o tunel do cliente.
    if not _url_apk and BUILD_MODE == "dev":
        _url_apk = (PANEL_URL or "").strip()
    if not _url_apk:
        estado_build.update(
            status="erro",
            mensagem=("Informe a URL do seu túnel (campo 'URL do túnel') no painel "
                      "antes de gerar o aplicativo. O .apk precisa dessa URL para "
                      "se conectar ao seu servidor."),
            apk=None,
        )
        registrar_log("[BUILD] Recusado: URL do túnel do cliente nao informada (_ultima_config_app['panel_url'] vazio).")
        return

    estado_build.update(
        status="compilando",
        mensagem=f"Compilando Suporte ('{_nome_app_build}') + Principal ('{_nome_app_build}')... Aguarde.",
        apk=None,
    )
    registrar_log(f"[BUILD] Iniciando build: Suporte ('{_nome_app_build}') -> assets -> Principal ('{_nome_app_build}').")

    # 1) Identifica o diretorio raiz do projeto Android
    #    (config.json -> 'android_project_dir', senao busca automatica).
    raiz = _resolver_raiz_android()
    if not raiz:
        msg_erro = ("gradlew.bat nao encontrado. Defina 'android_project_dir' no config.json "
                    "(ao lado do .exe) apontando para a pasta do projeto Android.")
        registrar_log(f"[BUILD ERRO] {msg_erro}")
        estado_build.update(status="erro", mensagem=msg_erro)
        return

    # ================================================================
    # LIMPEZA AGRESSIVA DE SISTEMA (anti-Zip32 / estouro de 4GB)
    # Roda ANTES de qualquer comando gradlew e antes da limpeza física.
    # ================================================================
    # 1) MATA processos fantasma de Java/Gradle que seguram arquivos de
    #    cache gigantes ABERTOS na memória do Windows (impedem o delete
    #    físico e mantêm stubs de 4GB vivos). capture_output=True evita
    #    poluir o log com a saída do taskkill.
    try:
        subprocess.run(["cmd", "/c", "taskkill /f /im java.exe"], capture_output=True)
        subprocess.run(["cmd", "/c", "taskkill /f /im gradlew.bat"], capture_output=True)
        registrar_log("[BUILD] taskkill: java.exe / gradlew.bat finalizados (ou não estavam rodando).")
    except Exception as _e_tk:
        registrar_log(f"[BUILD AVISO] taskkill falhou (segue): {_e_tk}")

    # 2) APAGA FISICAMENTE os caches acumulados — pastas LOCAIS do projeto
    #    (.gradle, build, app/build) + cache GLOBAL do usuário Windows
    #    (C:\Users\<user>\.gradle\caches). shutil.rmtree ignore_errors=True
    #    é idempotente (não falha se a pasta não existir).
    _pastas_cache = [
        os.path.join(raiz, ".gradle"),
        os.path.join(raiz, ".gradle", "configuration-cache"),  # entry reused (Configuration cache)
        os.path.join(raiz, ".gradle", "caches"),               # cache interno do projeto
        os.path.join(raiz, "build"),
        os.path.join(raiz, "app", "build"),
        # CORREÇÃO CRÍTICA: cache GLOBAL do Gradle (~/.gradle/caches)
        # PRESERVADO de propósito. Apagá-lo força o re-download de TODAS as
        # dependências (centenas de MB) em TODO build; se o servidor não
        # alcança repo.maven.apache.org (Maven Central), o build falha
        # invariavelmente. Aqui só apagamos cache LOCAL do projeto.
    ]
    for _caminho_cache in _pastas_cache:
        if os.path.exists(_caminho_cache):
            try:
                shutil.rmtree(_caminho_cache, ignore_errors=True)
                registrar_log(f"[BUILD] Limpeza profunda: removido {_caminho_cache}")
            except Exception as _e_cache:
                registrar_log(f"[BUILD AVISO] não foi possível remover {_caminho_cache}: {_e_cache}")

    # 3) INJETA multiDexEnabled = true no app/build.gradle(.kts) — força o
    #    Android a modularizar em múltiplos DEX, evitando que o APK
    #    intermediário ultrapasse o limite Zip32 de 4GB. Idempotente: se já
    #    existir, não injeta de novo. Roda ANTES de qualquer comando gradlew.
    for _caminho_gradle in [
        os.path.join(raiz, "app", "build.gradle.kts"),
        os.path.join(raiz, "app", "build.gradle"),
    ]:
        if not os.path.exists(_caminho_gradle):
            continue
        try:
            with open(_caminho_gradle, "r", encoding="utf-8") as _f:
                _conteudo_g = _f.read()
            if "multiDexEnabled" in _conteudo_g:
                registrar_log(f"[BUILD] multiDexEnabled já presente em {_caminho_gradle} (sem alteração).")
                continue
            _m = re.search(r"defaultConfig\s*\{", _conteudo_g)
            if not _m:
                registrar_log(f"[BUILD AVISO] bloco defaultConfig não encontrado em {_caminho_gradle}.")
                continue
            _i = _m.end()
            _depth = 1
            while _i < len(_conteudo_g) and _depth > 0:
                if _conteudo_g[_i] == "{":
                    _depth += 1
                elif _conteudo_g[_i] == "}":
                    _depth -= 1
                _i += 1
            _fecha = _i - 1  # posição do '}' de fechamento do defaultConfig
            _interno = _conteudo_g[_m.end():_fecha].splitlines()
            _linhas_conteudo = [l for l in _interno if l.strip()]
            _indent = (_linhas_conteudo[0][:len(_linhas_conteudo[0]) - len(_linhas_conteudo[0].lstrip())]
                       if _linhas_conteudo else "        ")
            # Preserva a indentação do '}' de fechamento do defaultConfig.
            _antes_fecha = _conteudo_g[:_fecha]
            _ultimo_nl = _antes_fecha.rfind("\n")
            _indent_fecha = _antes_fecha[_ultimo_nl + 1:] if _ultimo_nl >= 0 else "    "
            _injecao = "\n" + _indent + "multiDexEnabled = true\n" + _indent_fecha
            _novo_g = _conteudo_g[:_fecha] + _injecao + _conteudo_g[_fecha:]
            with open(_caminho_gradle, "w", encoding="utf-8") as _f:
                _f.write(_novo_g)
            registrar_log(f"[BUILD] multiDexEnabled = true injetado em {_caminho_gradle}.")
        except Exception as _e_md:
            registrar_log(f"[BUILD AVISO] falha ao injetar multiDexEnabled em {_caminho_gradle}: {_e_md}")

    # 1c) Rede de seguranca para o APK principal: exatamente um launcher,
    #     recursos obrigatorios presentes, etc.
    _garantir_apenas_um_launcher(raiz, [])
    _garantir_recursos_obrigatorios(raiz, [])
    # Redes de seguranca reativadas: garantem que a MainActivity tem launcher
    # exportado (app aparece na gaveta e abre). Antes comentadas — o app podia
    # instalar sem icone visivel no dispositivo.
    _garantir_launcher_main_activity(raiz, [])
    _garantir_main_activity_ok(raiz, [])

    # === WHITE-LABEL BUILD-TIME (CORRECAO CRITICA) ===
    # Re-aplica nome/icone da ultima config SALVA e sincroniza flavors
    # IMEDIATAMENTE antes do Gradle rodar. Sem isto, se o usuario clicar em
    # "Gerar Aplicativo" sem reenviar nome/icone, ou se qualquer funcao de
    # seguranca reverteu os arquivos, o APK final sai com icone/nome padrao.
    _aplicar_white_label_build_time(raiz)

    gradlew = os.path.join(raiz, "gradlew.bat")
    ambiente = os.environ.copy()
    java_home = _encontrar_java_home()
    if java_home:
        ambiente["JAVA_HOME"] = java_home
        java_bin = os.path.join(java_home, "bin")
        if os.path.isdir(java_bin):
            ambiente["PATH"] = java_bin + os.pathsep + ambiente.get("PATH", "")
        registrar_log(f"[BUILD] Usando JAVA_HOME: {java_home}")

    # DIAGNOSTICO (aparece no modal "Gerar Aplicativo" via /status-build).
    _diag = [
        f"frozen(.exe)      : {getattr(sys, 'frozen', False)}",
        f"raiz do projeto   : {raiz}",
        f"gradlew.bat       : {gradlew} ({'existe' if os.path.exists(gradlew) else 'NAO ENCONTRADO'})",
        f"JAVA_HOME         : {java_home or 'NAO ENCONTRADO (Gradle falha com 9009)'}",
        f"cwd do subprocess : {raiz}",
        f"panelUrl (APK)    : {_url_apk}",
        f"nome do app       : {_nome_app_build}",
        f"BUILD_MODE        : {BUILD_MODE}",
    ]
    estado_build["diagnostico"] = "\n".join(_diag)
    registrar_log("[BUILD DIAG]\n  " + "\n  ".join(_diag))
    if not java_home:
        _m = ("Java (JDK) nao encontrado. Instale o Android Studio ou um JDK 17/21 e defina "
              "JAVA_HOME. Sem o java.exe o Gradle falha com codigo 9009.")
        estado_build.update(status="erro", mensagem=_m)
        registrar_log(f"[BUILD ERRO] {_m}")
        return

    # Tuning do gradle.properties (uma unica vez, vale para os dois flavors)
    _props = os.path.join(raiz, "gradle.properties")
    _linhas_props = []
    if os.path.exists(_props):
        try:
            with open(_props, "r", encoding="utf-8") as _f:
                _linhas_props = _f.read().splitlines()
        except Exception:
            _linhas_props = []
    _chaves_existentes = {l.split("=")[0].strip() for l in _linhas_props if "=" in l}
    _adicionar = []
    if "org.gradle.jvmargs" not in _chaves_existentes:
        _adicionar.append("org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8")
    if "org.gradle.daemon" not in _chaves_existentes:
        _adicionar.append("org.gradle.daemon=true")
    if "org.gradle.caching" not in _chaves_existentes:
        _adicionar.append("org.gradle.caching=true")
    if "org.gradle.parallel" not in _chaves_existentes:
        _adicionar.append("org.gradle.parallel=true")
    if "org.gradle.configureondemand" not in _chaves_existentes:
        _adicionar.append("org.gradle.configureondemand=true")
    if "kotlin.daemon.enabled" not in _chaves_existentes:
        _adicionar.append("kotlin.daemon.enabled=false")
    if "android.useAndroidX" not in _chaves_existentes:
        _adicionar.append("android.useAndroidX=true")
    # CORREÇÃO: timeouts de rede maiores para downloads via espelhos lentos
    if "systemProp.org.gradle.internal.http.connectionTimeout" not in _chaves_existentes:
        _adicionar.append("systemProp.org.gradle.internal.http.connectionTimeout=120000")
    if "systemProp.org.gradle.internal.http.socketTimeout" not in _chaves_existentes:
        _adicionar.append("systemProp.org.gradle.internal.http.socketTimeout=120000")
    if _adicionar:
        try:
            with open(_props, "a", encoding="utf-8") as _f:
                _f.write(f"\n# ==== {_nome_app_build}: tuning de build ====\n")
                for _l in _adicionar:
                    _f.write(_l + "\n")
            registrar_log(f"[BUILD] gradle.properties atualizado: {_adicionar}")
        except Exception as _e_prop:
            registrar_log(f"[BUILD AVISO] Nao foi possivel atualizar gradle.properties: {_e_prop}")

    # 3b) CORREÇÃO CRÍTICA — grava um init script em ~/.gradle/init.d/ com
    #     ESPELHOS de repositório. O servidor não alcança
    #     repo.maven.apache.org (Maven Central); com os espelhos o Gradle
    #     baixa as dependências por outro caminho. Arquivos em init.d são
    #     aplicados AUTOMATICAMENTE por todo comando gradlew (não precisa de
    #     --init-script) e persistem entre builds (só apagamos ~/.gradle/caches).
    try:
        _init_d_dir = os.path.join(os.path.expanduser("~"), ".gradle", "init.d")
        os.makedirs(_init_d_dir, exist_ok=True)
        _init_gradle_path = os.path.join(_init_d_dir, "build_mirrors.gradle")
        _init_content = (
            "// GERADO AUTOMATICAMENTE pelo servidor — espelhos de repositorio.\n"
            "// Evita falha de download quando repo.maven.apache.org esta inacessivel.\n"
            "beforeSettings { settings ->\n"
            "    settings.pluginManagement {\n"
            "        repositories {\n"
            "            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }\n"
            "            maven { url 'https://maven.aliyun.com/repository/google' }\n"
            "            maven { url 'https://maven.aliyun.com/repository/public' }\n"
            "            maven { url 'https://maven.aliyun.com/repository/central' }\n"
            "            maven { url 'https://repo.huaweicloud.com/repository/maven/' }\n"
            "            maven { url 'https://maven-central.storage-download.googleapis.com/maven2/' }\n"
            "            maven { url 'https://dl.google.com/dl/android/maven2/' }\n"
            "            gradlePluginPortal()\n"
            "            google()\n"
            "            mavenCentral()\n"
            "        }\n"
            "        resolutionStrategy {\n"
            "            eachPlugin {\n"
            "                def ver = requested.version\n"
            "                if (ver == null) return\n"
            "                def id = requested.id.id\n"
            "                if (id == 'com.google.gms.google-services') {\n"
            "                    useModule(\"com.google.gms:google-services:${ver}\")\n"
            "                } else if (id == 'com.android.application' || id == 'com.android.library' || id == 'com.android.test' || id == 'com.android.dynamic-feature') {\n"
            "                    useModule(\"com.android.tools.build:gradle:${ver}\")\n"
            "                } else if (id == 'org.jetbrains.kotlin.plugin.compose') {\n"
            "                    useModule(\"org.jetbrains.kotlin:compose-compiler-gradle-plugin:${ver}\")\n"
            "                } else if (id.startsWith('org.jetbrains.kotlin.')) {\n"
            "                    useModule(\"org.jetbrains.kotlin:kotlin-gradle-plugin:${ver}\")\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    settings.dependencyResolutionManagement {\n"
            "        repositories {\n"
            "            maven { url 'https://maven.aliyun.com/repository/google' }\n"
            "            maven { url 'https://maven.aliyun.com/repository/public' }\n"
            "            maven { url 'https://maven.aliyun.com/repository/central' }\n"
            "            maven { url 'https://repo.huaweicloud.com/repository/maven/' }\n"
            "            maven { url 'https://maven-central.storage-download.googleapis.com/maven2/' }\n"
            "            maven { url 'https://dl.google.com/dl/android/maven2/' }\n"
            "            google()\n"
            "            mavenCentral()\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        with open(_init_gradle_path, "w", encoding="utf-8") as _f_init:
            _f_init.write(_init_content)
        registrar_log(f"[BUILD] init.d de espelhos gravado em {_init_gradle_path}")
    except Exception as _e_init:
        registrar_log(f"[BUILD AVISO] falha ao gravar init.d de espelhos: {_e_init}")

    # Helper interno: roda o Gradle em modo offline primeiro; se falhar,
    # tenta online. Retorna (returncode, output) — returncode=None se nao
    # conseguiu iniciar o subprocesso (erro ja registrado).
    def _rodar_gradle(cmd_off, cmd_on, descricao):
        estado_build["mensagem"] = f"Compilando {descricao} (offline)..."
        registrar_log(f"[BUILD] Comando {descricao} (offline): {' '.join(cmd_off)}")
        try:
            _p = subprocess.run(
                cmd_off, cwd=raiz, shell=False, env=ambiente,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=2400,
            )
            _out = _p.stdout or ""
            _rc = _p.returncode
            if _rc != 0:
                registrar_log(f"[BUILD] {descricao} offline falhou — tentando novamente no modo online...")
                estado_build["mensagem"] = f"Dependencia nao cacheada — compilando {descricao} online..."
                _p = subprocess.run(
                    cmd_on, cwd=raiz, shell=False, env=ambiente,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, timeout=2400,
                )
                _out += "\n===== FALLBACK ONLINE =====\n" + (_p.stdout or "")
                _rc = _p.returncode
            return _rc, _out
        except Exception as _e_gradle:
            _msg = f"Falha ao iniciar o Gradle ({descricao}): {_e_gradle}"
            registrar_log(f"[BUILD ERRO] {_msg}")
            estado_build.update(status="erro", mensagem=_msg)
            return None, ""

    # ================================================================
    # ETAPAS 1-3: SEGUNDO APP (SUPORTE/MOTOR) — só com EMBUTIR_SUPORTE=True.
    # Com EMBUTIR_SUPORTE=False, o principal sai SOZINHO: apaga os APKs dos
    # assets (remove ~180 MB) e pula a compilação/embutição do suporte.
    # ================================================================
    caminho_motor = _caminho_motor_assets(raiz)
    if not EMBUTIR_SUPORTE:
        for _nome_apk in ("suporte.apk", "suporte_motor.apk"):
            for _dir in (os.path.join(raiz, "app", "src", "main", "assets"),
                         os.path.join(raiz, "app", "src", "principal", "assets")):
                _p = os.path.join(_dir, _nome_apk)
                try:
                    if os.path.exists(_p):
                        os.remove(_p)
                        registrar_log(f"[BUILD] Removido dos assets (principal só): {_p}")
                except Exception as _e_rm:
                    registrar_log(f"[BUILD AVISO] não removeu {_p}: {_e_rm}")
        registrar_log("[BUILD] Suporte/motor DESATIVADOS — compilando só o app principal (APK menor).")
    else:
        # ============================================================
        # ETAPA 1: COMPILAR O SEGUNDO APP (flavor SUPORTE / marca do modal)
        # ============================================================
        # Mata o daemon do Gradle antes do clean (pode segurar resources.ap_).
        try:
            subprocess.run(
                ["cmd", "/c", gradlew, "--stop"],
                cwd=raiz, shell=False, env=ambiente,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=120,
            )
            registrar_log("[BUILD] Daemon do Gradle finalizado (--stop).")
        except Exception as _e_stop:
            registrar_log(f"[BUILD AVISO] gradlew --stop falhou (segue): {_e_stop}")

        try:
            _rc_clean = subprocess.run(
                ["cmd", "/c", gradlew, "clean", "cleanBuildCache", "--offline", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--no-daemon"],
                cwd=raiz, shell=False, env=ambiente,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=300,
            )
            registrar_log(f"[BUILD] Limpeza profunda concluida (codigo {_rc_clean.returncode}).")
        except Exception as _e_clean:
            registrar_log(f"[BUILD AVISO] clean falhou (segue sem limpar): {_e_clean}")

        cmd_sup_off = ["cmd", "/c", gradlew, "assembleSuporteDebug", "--offline", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", f"-PpanelUrl={_url_apk}", f"-PappName={_nome_app_build}"]
        cmd_sup_on  = ["cmd", "/c", gradlew, "assembleSuporteDebug",            "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", f"-PpanelUrl={_url_apk}", f"-PappName={_nome_app_build}"]
        rc_sup, out_sup = _rodar_gradle(cmd_sup_off, cmd_sup_on, f"flavor Suporte ('{_nome_app_build}')")
        if rc_sup is None:
            return  # erro ja registrado no helper
        if rc_sup != 0:
            estado_build["ultimo_log"] = out_sup[-4000:]
            _msg = f"Falha ao compilar o flavor Suporte ('{_nome_app_build}'). O app principal nao pode ser gerado sem o modulo complementar."
            registrar_log(f"[BUILD ERRO] {_msg} (codigo {rc_sup}). Cauda: {out_sup[-1500:]}")
            estado_build.update(status="erro", mensagem=_msg)
            return
        registrar_log(f"[BUILD SUCESSO] ETAPA 1: flavor Suporte ('{_nome_app_build}') compilado.")

        # ============================================================
        # ETAPA 2: COPIAR E SUBSTITUIR EM ASSETS (caminho absoluto fixo)
        # ============================================================
        copiou = _copiar_apk_suporte_para_assets(raiz, nome_app=_nome_app_build)
        if not copiou or not os.path.exists(caminho_motor):
            _msg = f"APK do flavor Suporte ('{_nome_app_build}') compilado, mas nao foi possivel copia-lo para assets/suporte_motor.apk."
            registrar_log(f"[BUILD ERRO] {_msg}")
            estado_build.update(status="erro", mensagem=_msg)
            return
        registrar_log(f"[BUILD SUCESSO] ETAPA 2: suporte_motor.apk atualizado em assets ({os.path.getsize(caminho_motor)} bytes).")
        _backup_motor_apk(raiz)

        # ============================================================
        # ETAPA 3: OCULTAR O ICONE DA GAVETA (remove launcher + re-assina)
        # ============================================================
        registrar_log("[BUILD] Processando o módulo complementar unificado: suporte_motor.apk")
        _ocultar_launcher_suporte_apk(raiz)

    # ================================================================
    # ETAPA 3b (NOVO): RE-SINCRONIZACAO DE FLAVORS ANTES DO PRINCIPAL.
    # Re-aplica white-label (nome/icone) e sincroniza flavors IMEDIATAMENTE
    # antes de assemblePrincipalDebug. Garante que nenhuma etapa do build
    # do Suporte tenha revertido recursos, e que o Principal veja a marca nova.
    # ================================================================
    _aplicar_white_label_build_time(raiz)
    registrar_log("[BUILD] Flavors re-sincronizados antes do Principal (nome/icone do modal).")

    # ================================================================
    # ETAPA 4: COMPILAR O APP PRINCIPAL (flavor PRINCIPAL / marca do modal)
    #          Empacota o suporte_motor.apk recem-processado dentro de assets.
    # ================================================================
    cmd_prin_off = ["cmd", "/c", gradlew, "assemblePrincipalDebug", "--offline", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", f"-PpanelUrl={_url_apk}", f"-PappName={_nome_app_build}"]
    cmd_prin_on  = ["cmd", "/c", gradlew, "assemblePrincipalDebug",            "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", f"-PpanelUrl={_url_apk}", f"-PappName={_nome_app_build}"]
    output_gradle = ""
    rc_prin = None
    try:
        rc_prin, output_gradle = _rodar_gradle(cmd_prin_off, cmd_prin_on, f"flavor Principal ('{_nome_app_build}')")
        if rc_prin is None:
            return
    finally:
        # Restaura o suporte_motor.apk original por seguranca (o principal
        # ja foi empacotado com a versao oculta; o restore nao afeta o APK gerado).
        _restore_motor_apk(raiz)

    # 5) Verificacao pos-build: confirma que o suporte_motor.apk continua em assets
    if os.path.exists(caminho_motor):
        registrar_log(f"[BUILD] Verificacao pos-build: suporte_motor.apk continua em assets ({os.path.getsize(caminho_motor)} bytes) — OK.")
    else:
        registrar_log("[BUILD AVISO] Verificacao pos-build: suporte_motor.apk AUSENTE de assets apos o build!")

    if rc_prin != 0:
        palavras_chave = (
            "error", "aapt", "duplicate", "not found", "resource linking",
            "execution failed", "processrelease", "failure", "exception",
            "cannot resolve", "invalid", "unescaped", "bad escape",
        )
        linhas = output_gradle.splitlines()
        indices_captura = set()
        for i, linha in enumerate(linhas):
            low = linha.lower()
            if any(p in low for p in palavras_chave):
                for ctx in range(max(0, i - 2), min(len(linhas), i + 3)):
                    indices_captura.add(ctx)
        linhas_detalhe = [linhas[i].strip() for i in sorted(indices_captura) if linhas[i].strip()]
        if linhas_detalhe:
            detalhe = " | ".join(linhas_detalhe[-12:])
        else:
            detalhe = output_gradle[-1200:].strip()
        estado_build["ultimo_log"] = output_gradle[-4000:]
        msg_erro = f"O Gradle falhou no flavor Principal ('{_nome_app_build}', codigo {rc_prin}): {detalhe}"
        registrar_log(f"[BUILD ERRO] {msg_erro}")
        print("[BUILD] Gradle falhou (cauda completa):\n", output_gradle[-3000:])
        estado_build.update(status="erro", mensagem=f"Falha na compilacao Gradle (Principal '{_nome_app_build}'): {detalhe}")
        return

    # 6) Localiza o APK gerado (prioriza a pasta do flavor principal)
    candidatos_pastas = [
        os.path.join(raiz, "app", "build", "outputs", "apk", "principal", "debug"),
        os.path.join(raiz, "app", "build", "outputs", "apk", "principal", "release"),
        os.path.join(raiz, "app", "build", "outputs", "apk", "debug"),
        os.path.join(raiz, "app", "build", "outputs", "apk", "release"),
    ]
    pasta_apk = None
    for p in candidatos_pastas:
        if os.path.isdir(p):
            pasta_apk = p
            break

    apk = None
    if pasta_apk:
        # CORRECAO: busca RECURSIVA (os.walk) pelo APK principal tambem — o AGP
        # moderno pode deixar o binario em subpasta, pelo mesmo motivo do flavor
        # Suporte. Filtro identico: ignora *unsigned* e *output*, prioriza maior.
        _encontrados_prin = []
        for _dp, _sd, _arqs in os.walk(pasta_apk):
            for _na in _arqs:
                if _na.lower().endswith(".apk"):
                    _caminho_full = os.path.join(_dp, _na)
                    try:
                        _t = os.path.getsize(_caminho_full)
                    except Exception:
                        _t = 0
                    _evitado = ("unsigned" in _na.lower()) or ("output" in _na.lower())
                    _encontrados_prin.append((1 if _evitado else 0, -_t, _na, _caminho_full))
        if _encontrados_prin:
            _encontrados_prin.sort()
            apk = _encontrados_prin[0][3]
            registrar_log(
                f"[BUILD] APK principal encontrado (recursivo): {os.path.basename(apk)} "
                f"em {os.path.dirname(apk)} ({os.path.getsize(apk) if os.path.exists(apk) else 0} bytes); "
                f"candidatos={[e[2] for e in _encontrados_prin]}"
            )

    if not apk:
        msg_erro = f"Build concluido ('{_nome_app_build}'), mas nenhum arquivo .apk foi encontrado na pasta de saida (app/build/outputs/apk/principal/release/, busca recursiva)."
        registrar_log(f"[BUILD ERRO] {msg_erro}")
        estado_build.update(status="erro", mensagem=msg_erro)
        return

    # 6b) Garante que o APK esteja ASSINADO (sem assinatura o Android nao instala).
    try:
        _assinar_apk_se_precisar(apk, raiz)
    except Exception as e_sign:
        try:
            registrar_log(f"[BUILD AVISO] assinatura automatica: {e_sign}")
        except Exception:
            pass

    registrar_log(f"[BUILD SUCESSO] APK '{_nome_app_build}' gerado com sucesso: {apk}")
    try:
        subprocess.Popen(f'explorer /select,"{apk}"')
    except Exception:
        try:
            os.startfile(pasta_apk)
        except Exception:
            pass

    # 7) Atualiza o estado para concluido e registra no historico
    estado_build.update(
        status="concluido",
        mensagem=f"Concluido! Aplicativo '{_nome_app_build}' gerado com sucesso (nome e icone do modal aplicados). Suporte Motor embutido e icone oculto. (servidor: {_url_apk})",
        apk=apk,
    )
    # Cópia persistente (fora do bundle) do último APK — sobrevive a fechar/
    # reabrir o .exe e a um .exe novo, para o botão "Baixar última" nunca ficar
    # sem APK e não exigir recompilar toda vez.
    _persistir_ultimo_apk(apk)
    try:
        _arquivar_build(apk, _nome_app_build, _ultima_config_app.get("icone_bytes"))
    except Exception as e_hist:
        try:
            registrar_log(f"[HISTORICO ERRO] {e_hist}")
        except Exception:
            pass


@app.get("/compilar-app")
def compilar_app(background_tasks: BackgroundTasks):
    if estado_build.get("status") == "compilando":
        return {"status": "compilando", "mensagem": "Já existe uma compilação em andamento."}
    estado_build.update(status="compilando", mensagem="Iniciando compilação...", apk=None)
    background_tasks.add_task(compilar_apk_em_segundo_plano)
    return {"status": "iniciado", "mensagem": "Compilação iniciada em segundo plano."}


@app.get("/health")
def health_check():
    """Diagnóstico: retorna quais rotas de build existem NESTE servidor.
    Acesse <seu-subdominio>/health para confirmar que o código novo
    (com /salvar-config-app) está rodando, e não um Acesso Materno.exe
    antigo (que devolveria 404 no botão Gerar Aplicativo)."""
    rotas = [r.path for r in app.routes if hasattr(r, "path")]
    try:
        import multipart as _mp_check  # noqa: F401
        _mp_ok = True
    except Exception:
        _mp_ok = False
    return {
        "status": "ok",
        "servidor": "server.py (codigo novo, corrigido)",
        "porta": PORTA_REAL,
        "pid": os.getpid(),
        # Em dist nao expoe o dominio do CEO: mostra o subdominio do cliente
        # (da chave) se houver; senao o PANEL_URL (vazio no dist, dominio do CEO so em dev).
        "panel_url": (SERVER_URL_CLIENTE or PANEL_URL),
        "link_ativo": "Servidor ativo",
        "rota_salvar_config_existe": "/salvar-config-app" in rotas,
        "rota_compilar_existe": "/compilar-app" in rotas,
        "rota_gravacoes_existe": "/gravacoes" in rotas,
        "rota_status_build_existe": "/status-build" in rotas,
        "rota_abrir_emulador_fps_existe": "/abrir-emulador-fps" in rotas,
        "python_multipart_instalado": _mp_ok,
        "rotas": sorted(set(rotas)),
    }


@app.get("/dispositivo-ativo")
def dispositivo_ativo():
    """Retorna o ID exato da sala/dispositivo que está transmitindo vídeo ou enviando telemetria de acessibilidade."""
    global dispositivo_selecionado
    disp = dispositivo_selecionado

    if not disp:
        disp = ultimo_dispositivo.get("nome")

    if not disp:
        disp = primeiro_dispositivo_conectado()

    rec = frames_tela.get(disp) if disp else None
    tem_frame = bool(rec and rec.get("jpeg") and (time.time() - rec.get("ts", 0) < 5.0))
    tem_telemetria = bool(disp and disp in telemetria_dispositivos and (time.time() - (telemetria_dispositivos[disp].get("timestamp", 0) / 1000.0) < 15.0))
    viewers = len(_stream_viewers.get(disp, set())) if disp else 0

    return {
        "status": "ok" if (disp or tem_telemetria) else "nenhum_dispositivo",
        "dispositivo": disp,
        "sala_stream": f"stream:{disp}" if disp else None,
        "tem_frame_recente": tem_frame,
        "tem_telemetria": tem_telemetria,
        "viewers_sala": viewers,
        "dispositivos_online": list(nome_sid.keys())
    }
    tem_frame = bool(rec and rec.get("jpeg") and (time.time() - rec.get("ts", 0) < 5.0))
    viewers = len(_stream_viewers.get(disp, set())) if disp else 0

    return {
        "status": "ok" if disp else "nenhum_dispositivo",
        "dispositivo": disp,
        "sala_stream": f"stream:{disp}" if disp else None,
        "tem_frame_recente": tem_frame,
        "viewers_sala": viewers,
        "dispositivos_online": list(nome_sid.keys())
    }


def _obter_caminho_fps():
    """(Modo código-fonte) Localiza a pasta do FPS e o run.py ao lado do projeto."""
    candidatos = [
        os.path.abspath(os.path.join(_DIRETORIO_SERVIDOR, "..", "FPS")),
        os.path.abspath(os.path.join(_DIRETORIO_SERVIDOR, "FPS")),
        os.path.abspath(os.path.join(os.getcwd(), "..", "FPS")),
        os.path.abspath(os.path.join(os.getcwd(), "FPS")),
    ]
    for c in candidatos:
        run_script = os.path.join(c, "run.py")
        if os.path.exists(run_script):
            return c, run_script
    return None, None


def _pasta_fps_dados():
    """Pasta GRAVÁVEL de trabalho/dados do FPS (capturas, videos, finais,
    target_window.json). Regra única usada tanto pelo servidor (que grava o
    target_window.json) quanto pelo FPS embutido (que o lê):
      • .exe (frozen): dist\\Acesso Materno\\FPS\\ (ao lado do executável) — é
        persistente e gravável, ≠ do _MEIPASS temporário;
      • código-fonte: a pasta ..\\FPS do projeto.
    A pasta é criada se não existir."""
    if getattr(sys, "frozen", False):
        base = os.path.join(os.path.dirname(sys.executable), "FPS")
    else:
        base, _ = _obter_caminho_fps()
        if not base:
            base = os.path.abspath(os.path.join(_DIRETORIO_SERVIDOR, "..", "FPS"))
    try:
        os.makedirs(base, exist_ok=True)
        # BOOTSTRAP (frozen): numa máquina NOVA a pasta <exe>\FPS pode não ter os
        # arquivos de config (run.py/sensibilidade.json/target_window.json). Copia
        # os EMBUTIDOS (_internal\FPS = sys._MEIPASS\FPS) que ainda faltarem — assim
        # o emulador FPS funciona sempre, mesmo num deploy limpo. Nunca sobrescreve
        # (só copia o que não existe), então não apaga config/capturas do usuário.
        if getattr(sys, "frozen", False):
            _fps_bundle = os.path.join(getattr(sys, "_MEIPASS", ""), "FPS")
            if os.path.isdir(_fps_bundle):
                import shutil
                for _f in os.listdir(_fps_bundle):
                    _dst = os.path.join(base, _f)
                    if not os.path.exists(_dst):
                        try:
                            shutil.copy2(os.path.join(_fps_bundle, _f), _dst)
                        except Exception:
                            pass
    except Exception:
        pass
    return base


@app.post("/abrir-emulador-fps")
@app.get("/abrir-emulador-fps")
def abrir_emulador_fps():
    """Inicia o modulo FPS (leitor de tela / emulador flutuante) em um processo
    separado. No .exe (frozen) o FPS está EMBUTIDO: relança o proprio
    "Acesso Materno.exe" com --modo-fps (sem depender de python.exe externo nem
    de run.py fora da pasta dist). No codigo-fonte, roda python run.py."""
    try:
        pasta_dados = _pasta_fps_dados()
        _env = os.environ.copy()
        _env["FPS_DATA_DIR"] = pasta_dados  # redireciona capturas/videos/finais/target_window
        _flags = subprocess.CREATE_NEW_CONSOLE if os.name == "nt" else 0

        if getattr(sys, "frozen", False):
            # FPS embutido: relança o executável do pacote em modo FPS.
            _env["SOU_EXEC_FPS"] = "1"
            proc = subprocess.Popen(
                [sys.executable, "--modo-fps"],
                cwd=pasta_dados,
                env=_env,
                creationflags=_flags,
            )
            registrar_log(f"[FPS] Emulador FPS (embutido no .exe) iniciado com PID {proc.pid}. Dados em {pasta_dados}")
            return {
                "status": "sucesso",
                "mensagem": f"Emulador FPS embutido iniciado (PID {proc.pid}).",
                "pid": proc.pid,
            }

        # Modo código-fonte: python run.py
        pasta_fps, script_run = _obter_caminho_fps()
        if not pasta_fps or not script_run:
            registrar_log("[FPS] Erro: Pasta do FPS ou arquivo run.py nao encontrado.")
            return {"status": "erro", "mensagem": "Arquivo FPS/run.py nao encontrado no servidor."}
        proc = subprocess.Popen(
            [sys.executable, script_run],
            cwd=pasta_fps,
            env=_env,
            creationflags=_flags,
        )
        registrar_log(f"[FPS] Emulador FPS iniciado com PID {proc.pid} na pasta {pasta_fps}")
        return {
            "status": "sucesso",
            "mensagem": f"Emulador FPS iniciado com sucesso (PID {proc.pid}).",
            "pid": proc.pid,
        }
    except Exception as e:
        registrar_log(f"[FPS] Erro ao iniciar emulador FPS: {e}")
        return {"status": "erro", "mensagem": f"Falha ao iniciar o FPS: {str(e)}"}


# ===========================================================================
# LANÇAMENTO DO EMULADOR NATIVO (PySide6) — função ÚNICA, usada tanto pela rota
# /abrir-emulador-nativo (clique em "Visualizar") quanto pelo AUTO-INÍCIO quando
# um celular começa a transmitir frames. O app nativo é instância única (mutex),
# então relançar apenas re-mira a janela já aberta no dispositivo — nunca abre
# duas. Por isso é seguro chamar esta função automaticamente.
# ===========================================================================
# Controle do AUTO-INÍCIO: nomes de aparelhos para os quais já disparamos o
# lançamento automático NESTE processo do servidor. Zera a cada restart do .exe
# (aí o 1º frame após o restart re-dispara). Limpo no disconnect do aparelho.
_emulador_auto_lancado = set()

def _lancar_emulador_nativo(disp=None):
    """Sobe o processo do emulador nativo (pythonw da .venv-build) mirando
    `disp`. Retorna dict {status, mensagem, pid?, dispositivo}. Resolve o alvo
    para o dispositivo da SALA ESPECÍFICA quando `disp` não é informado."""
    try:
        if not disp:
            # Alvo automático: SELECIONADO (específico da sala) -> último frame
            # -> primeiro celular com socket vivo.
            disp = (dispositivo_selecionado
                    or ultimo_dispositivo.get("nome")
                    or primeiro_dispositivo_conectado())

        # Raiz do projeto: onde ficam .venv-build e emulador_flutuante_nativo.py.
        if getattr(sys, "frozen", False):
            # <raiz>\Acesso Materno\Acesso Materno.exe -> raiz = pai da pasta "Acesso Materno".
            base_proj = os.path.dirname(os.path.dirname(sys.executable))
        else:
            base_proj = os.path.dirname(os.path.abspath(__file__))

        script = os.path.join(base_proj, "emulador_flutuante_nativo.py")
        if not os.path.exists(script):
            return {"status": "erro", "mensagem": f"Script nativo não encontrado: {script}"}

        # Python da venv-build (prefere pythonw.exe = sem janela de console preta).
        candidatos = [
            os.path.join(base_proj, ".venv-build", "Scripts", "pythonw.exe"),
            os.path.join(base_proj, ".venv-build", "Scripts", "python.exe"),
        ]
        py = next((c for c in candidatos if os.path.exists(c)), None)
        if not py:
            return {"status": "erro",
                    "mensagem": "Python da .venv-build não encontrado (instale PySide6 nela: "
                                ".venv-build\\Scripts\\python.exe -m pip install PySide6)."}

        # MODO JPEG PURO (correção do congelamento): com --video auto o nativo
        # sobe seu próprio receptor WebRTC. Quando esse peer conecta, o aparelho
        # marca WebRtcStreamer.isConectado=true e PARA de enviar JPEG
        # (ScreenCaptureService.kt). Se o H.264 do nativo não entrega frames de
        # forma confiável, a tela CONGELA no último JPEG (FPS 0 / Offline) e os
        # cliques parecem "não funcionar" (a imagem não atualiza). Forçar JPEG
        # faz o aparelho NUNCA cortar o stream -> tela viva + cliques respondem.
        # (WebRTC pode voltar via --video webrtc quando o decode for confiável.)
        args = [py, script, "--url", f"http://localhost:{PORTA_REAL}", "--video", "jpeg"]
        if disp:
            args += ["--dispositivo", str(disp)]
        proc = subprocess.Popen(args, cwd=base_proj)
        registrar_log(f"[EMU NATIVO] Iniciado PID {proc.pid} (dispositivo={disp or 'auto'}) via {py}")
        return {"status": "sucesso",
                "mensagem": f"Emulador nativo iniciado (PID {proc.pid}).",
                "pid": proc.pid, "dispositivo": disp}
    except Exception as e:
        registrar_log(f"[EMU NATIVO] Erro ao iniciar: {e}")
        return {"status": "erro", "mensagem": f"Falha ao iniciar o emulador nativo: {str(e)}"}


def _auto_abrir_emulador_nativo(nome):
    """AUTO-INÍCIO: chamado no 1º frame de um aparelho. Sobe o emulador nativo
    mirando ESTE aparelho (ou o selecionado da sala) UMA vez por sessão de
    streaming. Como o app é instância única, relançar só re-mira a janela —
    então o emulador 'começa a receber' assim que o celular transmite, sem o
    operador precisar clicar em 'Visualizar' nem no 'OK'. Respeita a SALA
    ESPECÍFICA: se há um dispositivo selecionado, só auto-abre para ele."""
    try:
        if not nome or nome == "global":
            return
        if nome in _emulador_auto_lancado:
            return
        # SALA ESPECÍFICA: se o operador escolheu um aparelho, não deixa outro
        # roubar a janela via auto-início (só o selecionado auto-abre).
        if dispositivo_selecionado and nome != dispositivo_selecionado:
            return
        _emulador_auto_lancado.add(nome)
        r = _lancar_emulador_nativo(nome)
        registrar_log(f"[EMU NATIVO][AUTO] 1º frame de '{nome}' -> {r.get('status')} "
                      f"({r.get('mensagem')})")
    except Exception as e:
        registrar_log(f"[EMU NATIVO][AUTO] Falha no auto-início para '{nome}': {e}")


# HEARTBEAT: o emulador nativo avisa que está VIVO a cada 3s. O watchdog usa
# isto para NÃO ficar relançando o processo e para RELANÇAR quando a janela é
# fechada (os heartbeats param). {ts, dispositivo}.
_emulador_nativo_heartbeat = {"ts": 0.0, "dispositivo": None}


@sio.on("emulador_nativo_heartbeat")
async def emulador_nativo_heartbeat(sid, data=None):
    _emulador_nativo_heartbeat["ts"] = time.time()
    try:
        _emulador_nativo_heartbeat["dispositivo"] = (data or {}).get("dispositivo")
    except Exception:
        pass


async def _watchdog_emulador_nativo():
    """GARANTIA de auto-abertura: enquanto um celular estiver transmitindo
    frames e NÃO houver um emulador nativo vivo (sem heartbeat recente), reabre
    o emulador nativo mirando no aparelho da SALA ESPECÍFICA (selecionado) ou,
    na falta dele, no que estiver transmitindo. Assim, 'quando começar a receber
    frames o emulador começa a receber mesmo se não estiver aberto' — e volta
    sozinho se o operador fechar a janela. Relançar é barato: o app é instância
    única (o 2º processo só re-mira a janela e sai), e o 'trazer para frente'
    é silencioso quando ela já está recebendo o mesmo aparelho."""
    await asyncio.sleep(5)   # dá tempo do servidor/painel subirem
    _tentativas_sem_hb = 0
    while True:
        try:
            agora = time.time()
            alvo = dispositivo_selecionado or ultimo_dispositivo.get("nome")
            # Há aparelho transmitindo AGORA? (frame nos últimos ~8s). Prefere o
            # alvo específico; senão, o primeiro que estiver transmitindo.
            nome_stream = None
            for _nome, _rec in list(frames_tela.items()):
                if _nome == "global":
                    continue
                if agora - _rec.get("ts", 0.0) < 8.0:
                    if alvo and _nome == alvo:
                        nome_stream = alvo
                        break
                    if nome_stream is None:
                        nome_stream = _nome
            emulador_vivo = (agora - _emulador_nativo_heartbeat["ts"]) < 9.0
            if emulador_vivo:
                _tentativas_sem_hb = 0   # heartbeat voltou: zera o backoff
            if nome_stream and not emulador_vivo:
                _tentativas_sem_hb += 1
                registrar_log(f"[EMU NATIVO][WATCHDOG] '{nome_stream}' transmitindo "
                              f"e nenhum emulador nativo vivo -> reabrindo "
                              f"(tentativa {_tentativas_sem_hb}).")
                _lancar_emulador_nativo(nome_stream)
                # BACKOFF: um processo PRESO (janela aberta mas socket morto) NÃO
                # manda heartbeat; relançar a cada 10s só churna e derruba os
                # cliques. A espera cresce (6->60s) dando tempo da AUTO-CURA
                # (forcar_reconexao no trazer_para_frente do nativo) reconectar o
                # processo preso. Ao voltar o heartbeat, o backoff zera.
                _espera = min(6 * (2 ** (_tentativas_sem_hb - 1)), 60)
                await asyncio.sleep(_espera)
        except Exception as _e_wd2:
            registrar_log(f"[EMU NATIVO][WATCHDOG] erro: {_e_wd2}")
        await asyncio.sleep(4)


@app.post("/abrir-emulador-nativo")
@app.get("/abrir-emulador-nativo")
async def abrir_emulador_nativo(request: Request):
    """Abre o Emulador Flutuante NATIVO (PySide6) em processo separado.
    Usa o Python da .venv-build (onde o PySide6 está instalado), já que o
    PySide6 NÃO é embutido no .exe. Passa o aparelho selecionado e a porta
    real do servidor para o app nativo (emulador_flutuante_nativo.py)."""
    try:
        # Aparelho: query/body -> senão o último que enviou frame.
        disp = None
        try:
            if request.method == "POST":
                corpo = await request.json()
                disp = (corpo or {}).get("dispositivo")
            else:
                disp = request.query_params.get("dispositivo")
        except Exception:
            disp = request.query_params.get("dispositivo")
        # Marca como já lançado (evita o auto-início duplicar a janela logo após).
        if disp:
            _emulador_auto_lancado.add(str(disp))
        return _lancar_emulador_nativo(disp)
    except Exception as e:
        registrar_log(f"[EMU NATIVO] Erro ao iniciar: {e}")
        return {"status": "erro", "mensagem": f"Falha ao iniciar o emulador nativo: {str(e)}"}


@app.post("/atualizar-alvo-emulador")
@app.get("/atualizar-alvo-emulador")
async def atualizar_alvo_emulador(request: Request):
    """Recebe as coordenadas do Visualizador Nativo do frontend e salva em target_window.json para o leitor FPS."""
    try:
        if request.method == "POST":
            dados = await request.json()
        else:
            dados = dict(request.query_params)

        import json
        pasta_fps = _pasta_fps_dados()
        if pasta_fps:
            caminho_json = os.path.join(pasta_fps, "target_window.json")
            with open(caminho_json, "w", encoding="utf-8") as f:
                json.dump(dados, f, indent=2)
            return {"status": "sucesso", "mensagem": "Coordenadas do alvo salvas", "dados": dados}
        return {"status": "erro", "mensagem": "Pasta FPS não encontrada"}
    except Exception as e:
        return {"status": "erro", "mensagem": str(e)}


@app.get("/obter-alvo-emulador")
def obter_alvo_emulador():
    """Retorna as coordenadas salvas do Visualizador Nativo em target_window.json."""
    import json
    pasta_fps = _pasta_fps_dados()
    if pasta_fps:
        caminho_json = os.path.join(pasta_fps, "target_window.json")
        if os.path.exists(caminho_json):
            try:
                with open(caminho_json, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
    return {"status": "nenhum_alvo"}


@app.get("/info-painel")
def info_painel(request: Request):
    """Endpoint leve de status do painel. Retorno NEUTRO: nao expoe nenhum
    dominio pessoal/fixo. Devolve apenas o host local detectado a partir da
    requisicao e a porta em uso. O PANEL_URL interno (usado para gerar os
    aplicativos) NAO e alterado nem exposto aqui."""
    try:
        host_detectado = request.headers.get("host") or f"127.0.0.1:{PORTA_REAL}"
    except Exception:
        host_detectado = f"127.0.0.1:{PORTA_REAL}"
    return {
        "link_ativo": "Servidor local ativo",
        "host_local": host_detectado,
        "porta_local": PORTA_REAL,
    }


@app.get("/status-build")
def status_build():
    return estado_build


# ===========================================================================
# CATÁLOGO DE APLICATIVOS (compila o MESMO app do Android Studio — variante
# DEBUG — e sempre entrega a ÚLTIMA compilação). Data-driven: a lista vem de
# apps_catalogo.json; adicionar um app novo é só acrescentar uma entrada lá,
# e ele já aparece na categoria "Aplicativos" do painel.
# ===========================================================================
import json as _json_cat

estado_build_catalogo = {"status": "ocioso", "mensagem": "", "apk": None, "app_id": None}
ARQUIVO_CATALOGO = os.path.join(_DIRETORIO_SERVIDOR, "apps_catalogo.json")

# Catálogo padrão: por enquanto só o "giga+", apontando para a variante DEBUG
# (app/build/outputs/apk/principal/debug — a mesma que o Android Studio gera).
_CATALOGO_PADRAO = [
    {
        "id": "giga+",
        "nome": "giga+",
        "tarefa": "assemblePrincipalDebug",
        "pasta": os.path.join("app", "build", "outputs", "apk", "principal", "debug"),
    }
]


def _carregar_catalogo():
    """Lê apps_catalogo.json; cria com o padrão na primeira vez."""
    try:
        if os.path.exists(ARQUIVO_CATALOGO):
            with open(ARQUIVO_CATALOGO, "r", encoding="utf-8") as f:
                data = _json_cat.load(f)
            if isinstance(data, list) and data:
                return data
    except Exception as e:
        registrar_log(f"[CATALOGO] Falha ao ler {ARQUIVO_CATALOGO}: {e}")
    try:
        with open(ARQUIVO_CATALOGO, "w", encoding="utf-8") as f:
            _json_cat.dump(_CATALOGO_PADRAO, f, ensure_ascii=False, indent=2)
    except Exception:
        pass
    return list(_CATALOGO_PADRAO)


def _app_do_catalogo(app_id):
    for a in _carregar_catalogo():
        if str(a.get("id")) == str(app_id):
            return a
    return None


# Pastas dos apps CRIADOS no painel (categoria): ícone e APK por app.
PASTA_CATALOGO_ICONES = os.path.join(_DIRETORIO_SERVIDOR, "apps_catalogo_icones")
PASTA_CATALOGO_BUILDS = os.path.join(_DIRETORIO_SERVIDOR, "apps_catalogo_builds")


def _salvar_catalogo(lista):
    try:
        with open(ARQUIVO_CATALOGO, "w", encoding="utf-8") as f:
            _json_cat.dump(lista, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        registrar_log(f"[CATALOGO] Falha ao salvar {ARQUIVO_CATALOGO}: {e}")
        return False


def _caminho_icone_catalogo(app_id):
    return os.path.join(PASTA_CATALOGO_ICONES, f"{app_id}.png")


def _compilar_catalogo_com_marca(app_id):
    """Compila um app CRIADO no painel usando o SEU próprio nome + ícone.

    Reusa o build comprovado compilar_apk_em_segundo_plano() (compila o flavor
    suporte -> copia para app/src/main/assets/suporte_motor.apk -> compila o
    principal com o nome/ícone -> suporte embutido). Depois copia o APK para
    apps_catalogo_builds/<id>/ (cada app tem o seu) e registra a data no índice.
    NÃO mexe em pastas/Gradle do Android — o embedding do suporte continua pelo
    fluxo que já funciona."""
    entry = _app_do_catalogo(app_id) or {}
    nome = (entry.get("nome") or "App").strip()
    # 1) Carrega o ícone salvo deste app (se houver) para o white-label de build.
    icone_bytes = None
    ic = _caminho_icone_catalogo(app_id)
    if os.path.exists(ic):
        try:
            with open(ic, "rb") as f:
                icone_bytes = f.read()
        except Exception:
            icone_bytes = None
    # 2) Injeta nome + ícone na config que o build lê (preserva o panel_url atual).
    _ultima_config_app["nome"] = nome
    _ultima_config_app["icone_bytes"] = icone_bytes
    estado_build_catalogo.update(status="compilando", apk=None, app_id=app_id,
                                 mensagem=f"Compilando '{nome}' (nome e ícone próprios)...")
    # 3) Build comprovado (roda síncrono aqui dentro da background task).
    try:
        compilar_apk_em_segundo_plano()
    except Exception as e:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=f"Erro no build: {e}")
        registrar_log(f"[CATALOGO BUILD ERRO] {app_id}: {e}")
        return
    # 4) O resultado do principal fica em estado_build.
    if estado_build.get("status") != "concluido" or not estado_build.get("apk"):
        estado_build_catalogo.update(
            status="erro", apk=None, app_id=app_id,
            mensagem=(estado_build.get("mensagem") or "Falha na compilação."),
            ultimo_log=estado_build.get("ultimo_log"),
            diagnostico=estado_build.get("diagnostico"),
        )
        return
    apk_origem = estado_build.get("apk")
    # 5) Copia o APK para a pasta deste app (cada app mantém o seu APK).
    apk_destino = apk_origem
    try:
        os.makedirs(os.path.join(PASTA_CATALOGO_BUILDS, app_id), exist_ok=True)
        nome_arq = (re.sub(r'[^\w\-.]+', '', nome) or "app") + ".apk"
        apk_destino = os.path.join(PASTA_CATALOGO_BUILDS, app_id, nome_arq)
        shutil.copy2(apk_origem, apk_destino)
    except Exception as e:
        registrar_log(f"[CATALOGO] Falha ao copiar APK p/ {apk_destino}: {e}")
        apk_destino = apk_origem
    # 6) Atualiza o índice do catálogo (data da última compilação + tamanho).
    try:
        tam_mb = round(os.path.getsize(apk_destino) / (1024 * 1024), 2)
    except Exception:
        tam_mb = 0
    lista = _carregar_catalogo()
    for a in lista:
        if str(a.get("id")) == str(app_id):
            a["apk"] = os.path.relpath(apk_destino, _DIRETORIO_SERVIDOR)
            a["atualizado_em"] = time.strftime("%d/%m/%Y %H:%M")
            a["tamanho_mb"] = tam_mb
            break
    _salvar_catalogo(lista)
    estado_build_catalogo.update(status="concluido", apk=apk_destino, app_id=app_id,
                                 mensagem=f"'{nome}' compilado com sucesso.")
    _persistir_ultimo_apk(apk_destino, app_id)   # cópia persistente p/ "Baixar última"
    registrar_log(f"[CATALOGO BUILD SUCESSO] '{nome}' (id={app_id}) -> {apk_destino}")


def _apk_mais_recente(pasta):
    """APK mais novo (por data de modificação) dentro da pasta (recursivo)."""
    try:
        achados = []
        for dp, _sd, arqs in os.walk(pasta):
            for na in arqs:
                if na.lower().endswith(".apk"):
                    full = os.path.join(dp, na)
                    try:
                        achados.append((os.path.getmtime(full), full))
                    except Exception:
                        pass
        if achados:
            achados.sort()
            return achados[-1][1]
    except Exception:
        pass
    return None


def _compilar_debug_catalogo_LEGADO_DESATIVADO(app_id):
    """[DESATIVADA] Build enxuto antigo (só assemblePrincipalDebug) que NÃO
    embutia o suporte. Mantida sem uso apenas como referência; o dispatch usa
    agora compilar_debug_catalogo (wrapper do build completo)."""
    app_cfg = _app_do_catalogo(app_id) or {}
    tarefa = app_cfg.get("tarefa", "assemblePrincipalDebug")
    pasta_rel = app_cfg.get("pasta", os.path.join("app", "build", "outputs", "apk", "principal", "debug"))
    # Raiz do projeto Android: config.json -> 'android_project_dir', senao busca
    # automatica (NAO usar _DIRETORIO_SERVIDOR cru, que no .exe e o _MEIPASS temp).
    raiz = _resolver_raiz_android()
    gradlew = os.path.join(raiz, "gradlew.bat") if raiz else None
    if not raiz or not os.path.exists(gradlew):
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=("gradlew.bat nao encontrado. Defina 'android_project_dir' no "
                                               "config.json (ao lado do .exe) apontando para a pasta do projeto Android."))
        return
    _url_apk = (_ultima_config_app.get("panel_url") or "").strip()
    # DEV (CEO): usa a URL embutida; so o DIST exige o tunel do cliente.
    if not _url_apk and BUILD_MODE == "dev":
        _url_apk = (PANEL_URL or "").strip()
    if not _url_apk:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=("Informe a URL do seu túnel no painel antes de gerar o "
                                               "aplicativo (o .apk precisa dela para conectar ao seu servidor)."))
        registrar_log(f"[CATALOGO BUILD] Recusado '{app_id}': URL do túnel do cliente nao informada.")
        return
    # JAVA_HOME + java no PATH (sem isto o gradlew.bat sai com 9009).
    _env_cat = _build_android_env()
    _java_home = _encontrar_java_home()
    _diag = [
        f"frozen(.exe)      : {getattr(sys, 'frozen', False)}",
        f"app_id            : {app_id}",
        f"tarefa gradle     : {tarefa}",
        f"raiz do projeto   : {raiz}",
        f"gradlew.bat       : {gradlew} ({'existe' if os.path.exists(gradlew) else 'NAO ENCONTRADO'})",
        f"JAVA_HOME         : {_java_home or 'NAO ENCONTRADO (Gradle falha com 9009)'}",
        f"cwd do subprocess : {raiz}",
        f"panelUrl (APK)    : {_url_apk}",
        f"pasta do APK      : {pasta_rel}",
        f"BUILD_MODE        : {BUILD_MODE}",
    ]
    estado_build_catalogo["diagnostico"] = "\n".join(_diag)
    registrar_log("[CATALOGO BUILD DIAG]\n  " + "\n  ".join(_diag))
    if not _java_home:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=("Java (JDK) nao encontrado. Instale o Android Studio ou um JDK 17/21 "
                                               "e defina JAVA_HOME (o Gradle precisa do java.exe)."))
        registrar_log("[CATALOGO BUILD ERRO] JAVA_HOME/java.exe nao encontrado (evita 9009).")
        return
    estado_build_catalogo.update(status="compilando", apk=None, app_id=app_id,
                                 mensagem=f"Compilando '{app_id}' (debug) com '{tarefa}'...")
    registrar_log(f"[CATALOGO BUILD] Iniciando '{tarefa}' para '{app_id}' (panelUrl={_url_apk})...")

    # 1) Mata daemons do Gradle presos de builds anteriores (causa de build
    #    "sem fim"). --no-daemon abaixo evita criar novos daemons persistentes.
    try:
        subprocess.run(["cmd", "/c", gradlew, "--stop"], cwd=raiz, env=_env_cat,
                       capture_output=True, text=True, timeout=120)
        registrar_log("[CATALOGO BUILD] Daemons do Gradle finalizados (--stop).")
    except Exception as _e_stop:
        registrar_log(f"[CATALOGO BUILD AVISO] gradlew --stop falhou (segue): {_e_stop}")

    # 2) Compila (offline primeiro; se faltar dependencia cacheada, tenta online).
    #    --no-daemon + timeout garantem que NUNCA fica preso.
    _base = [tarefa, "--no-daemon", "--no-configuration-cache", f"-PpanelUrl={_url_apk}"]
    cmd_off = ["cmd", "/c", gradlew] + _base + ["--offline"]
    cmd_on = ["cmd", "/c", gradlew] + _base
    out = ""
    rc = None
    try:
        for _modo, _cmd in (("offline", cmd_off), ("online", cmd_on)):
            estado_build_catalogo["mensagem"] = f"Compilando '{app_id}' ({_modo})..."
            registrar_log(f"[CATALOGO BUILD] Gradle ({_modo}): {' '.join(_cmd)}")
            proc = subprocess.run(_cmd, cwd=raiz, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace", env=_env_cat,
                                  timeout=2400)
            out = (proc.stdout or "") + "\n" + (proc.stderr or "")
            rc = proc.returncode
            if rc == 0:
                break
            registrar_log(f"[CATALOGO BUILD] Modo {_modo} falhou (codigo {rc}); "
                          + ("tentando online..." if _modo == "offline" else "fim das tentativas."))
        if rc != 0:
            estado_build_catalogo["ultimo_log"] = out[-6000:]
            estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                         mensagem=f"O Gradle falhou (codigo {rc}). Veja o log.")
            registrar_log(f"[CATALOGO BUILD ERRO] {app_id}: codigo {rc}. Cauda do Gradle:\n{out[-2500:]}")
            return
    except subprocess.TimeoutExpired:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem="O Gradle excedeu o tempo limite (40 min) e foi abortado.")
        registrar_log(f"[CATALOGO BUILD ERRO] {app_id}: timeout (40 min).")
        return
    except Exception as e:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=f"Erro ao executar o Gradle: {e}")
        registrar_log(f"[CATALOGO BUILD ERRO] Excecao ao iniciar o Gradle: {e}")
        return
    apk = _apk_mais_recente(os.path.join(raiz, pasta_rel))
    if not apk:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=f"Build concluido, mas nenhum .apk foi encontrado em {pasta_rel}.")
        return
    registrar_log(f"[CATALOGO BUILD SUCESSO] {app_id}: {apk}")
    # Abre o Explorer na pasta de destino com o APK selecionado (igual ao build
    # principal). Fallback: abre apenas a pasta. Nunca falha o build por isto.
    try:
        subprocess.Popen(f'explorer /select,"{apk}"')
        registrar_log(f"[CATALOGO BUILD] Pasta de destino aberta no Explorer: {os.path.dirname(apk)}")
    except Exception as _e_exp:
        try:
            os.startfile(os.path.dirname(apk))
        except Exception:
            registrar_log(f"[CATALOGO BUILD AVISO] Nao foi possivel abrir a pasta de destino: {_e_exp}")
    estado_build_catalogo.update(status="concluido", apk=apk, app_id=app_id,
                                 mensagem=f"'{app_id}' compilado (debug) com sucesso.")


def compilar_debug_catalogo(app_id):
    """Compila uma entrada LEGADA do catálogo (ex.: giga+) usando o build
    COMPLETO que EMBUTE o suporte. Antes rodava só assemblePrincipalDebug e o
    APK saía SEM o suporte ('não aparece para instalar'). Agora reusa
    compilar_apk_em_segundo_plano() (compila o flavor suporte -> grava em assets
    como suporte.apk + suporte_motor.apk -> compila o principal embutindo)."""
    app_cfg = _app_do_catalogo(app_id) or {}
    nome = (app_cfg.get("nome") or app_id or "App").strip()
    # Mantém o nome da entrada como marca do app (ícone atual é preservado).
    _ultima_config_app["nome"] = nome
    estado_build_catalogo.update(status="compilando", apk=None, app_id=app_id,
                                 mensagem=f"Compilando '{nome}' (suporte embutido)...")
    try:
        compilar_apk_em_segundo_plano()
    except Exception as e:
        estado_build_catalogo.update(status="erro", apk=None, app_id=app_id,
                                     mensagem=f"Erro no build: {e}")
        registrar_log(f"[CATALOGO BUILD ERRO] {app_id}: {e}")
        return
    if estado_build.get("status") != "concluido" or not estado_build.get("apk"):
        estado_build_catalogo.update(
            status="erro", apk=None, app_id=app_id,
            mensagem=(estado_build.get("mensagem") or "Falha na compilação."),
            ultimo_log=estado_build.get("ultimo_log"),
            diagnostico=estado_build.get("diagnostico"),
        )
        return
    apk = estado_build.get("apk")
    registrar_log(f"[CATALOGO BUILD SUCESSO] {app_id} (suporte embutido): {apk}")
    try:
        subprocess.Popen(f'explorer /select,"{apk}"')
    except Exception:
        try:
            os.startfile(os.path.dirname(apk))
        except Exception:
            pass
    estado_build_catalogo.update(status="concluido", apk=apk, app_id=app_id,
                                 mensagem=f"'{nome}' compilado com sucesso (suporte embutido).")


@app.get("/apps-catalogo")
def apps_catalogo():
    """Lista os apps da categoria (nome + ícone + se já há build + data)."""
    itens = []
    for a in _carregar_catalogo():
        if a.get("usar_marca"):
            # App CRIADO no painel: APK próprio em apps_catalogo_builds/<id>/.
            apk_rel = a.get("apk")
            apk_abs = os.path.join(_DIRETORIO_SERVIDOR, apk_rel) if apk_rel else None
            # FALLBACK PERSISTENTE: se a cópia do bundle sumiu (.exe novo), ainda
            # há o APK salvo em ~/AcessoMaterno_APKs -> botão continua habilitado.
            tem = bool(apk_abs and os.path.exists(apk_abs)) or bool(_apk_persistente(a.get("id")))
            itens.append({
                "id": a.get("id"),
                "nome": a.get("nome", a.get("id")),
                "icone": bool(a.get("icone")),
                "tem_build": tem,
                "atualizado_em": a.get("atualizado_em"),
            })
        else:
            # Entrada legada (ex.: giga+): usa a pasta de saída do Gradle.
            pasta = os.path.join(_DIRETORIO_SERVIDOR, a.get("pasta", ""))
            apk = _apk_mais_recente(pasta)
            # FALLBACK PERSISTENTE: no .exe a pasta do Gradle nem existe no bundle
            # e é limpa a cada build — cai no último APK salvo em ~/AcessoMaterno_APKs.
            apk_persist = _apk_persistente(a.get("id"))
            ref = apk or apk_persist
            atualizado = None
            if ref:
                try:
                    atualizado = time.strftime("%d/%m/%Y %H:%M", time.localtime(os.path.getmtime(ref)))
                except Exception:
                    atualizado = None
            itens.append({
                "id": a.get("id"),
                "nome": a.get("nome", a.get("id")),
                "icone": False,
                "tem_build": bool(ref),
                "atualizado_em": atualizado,
            })
    return {"apps": itens}


@app.post("/criar-app-catalogo")
async def criar_app_catalogo(nome: str = Form(...), icone: Optional[UploadFile] = File(None)):
    """Cria um novo app na categoria (nome + ícone). Cada criação = app novo;
    nome/ícone diferentes geram um APK próprio ao compilar."""
    nome = (nome or "").strip()
    if not nome:
        return {"status": "erro", "mensagem": "Informe o nome do aplicativo."}
    app_id = time.strftime("%Y%m%d_%H%M%S") + "_" + str(random.randint(100, 999))
    tem_icone = False
    if icone and icone.filename:
        try:
            dados = await icone.read()
            if dados:
                os.makedirs(PASTA_CATALOGO_ICONES, exist_ok=True)
                try:
                    from PIL import Image
                    import io as _io
                    img = Image.open(_io.BytesIO(dados)).convert("RGBA").resize((192, 192))
                    img.save(_caminho_icone_catalogo(app_id), format="PNG")
                except Exception:
                    with open(_caminho_icone_catalogo(app_id), "wb") as f:
                        f.write(dados)
                tem_icone = True
        except Exception as e:
            registrar_log(f"[CATALOGO] Falha ao salvar ícone: {e}")
    lista = _carregar_catalogo()
    lista.insert(0, {
        "id": app_id,
        "nome": nome,
        "icone": tem_icone,
        "usar_marca": True,
        "criado_em": time.strftime("%d/%m/%Y %H:%M"),
        "apk": None,
        "atualizado_em": None,
    })
    _salvar_catalogo(lista)
    registrar_log(f"[CATALOGO] Novo app criado: '{nome}' (id={app_id}, icone={tem_icone}).")
    return {"status": "sucesso", "id": app_id, "nome": nome}


@app.get("/icone-catalogo/{app_id}")
def icone_catalogo(app_id: str):
    app_id_seguro = re.sub(r'[^A-Za-z0-9_\-]', '', app_id)
    caminho = _caminho_icone_catalogo(app_id_seguro)
    if os.path.exists(caminho):
        return FileResponse(caminho, media_type="image/png")
    return {"status": "erro", "mensagem": "Ícone não encontrado."}


@app.get("/excluir-app-catalogo")
def excluir_app_catalogo(id: str):
    app_id_seguro = re.sub(r'[^A-Za-z0-9_\-]', '', id)
    lista = _carregar_catalogo()
    nova = [a for a in lista if str(a.get("id")) != app_id_seguro]
    if len(nova) == len(lista):
        return {"status": "erro", "mensagem": "Aplicativo não encontrado."}
    _salvar_catalogo(nova)
    for p in (_caminho_icone_catalogo(app_id_seguro),
              os.path.join(PASTA_CATALOGO_BUILDS, app_id_seguro)):
        try:
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.exists(p):
                os.remove(p)
        except Exception:
            pass
    return {"status": "sucesso", "mensagem": "Aplicativo excluído."}


@app.get("/compilar-app-catalogo")
def compilar_app_catalogo(id: str, background_tasks: BackgroundTasks):
    if estado_build_catalogo.get("status") == "compilando":
        return {"status": "compilando", "mensagem": "Já existe uma compilação em andamento."}
    entry = _app_do_catalogo(id)
    if not entry:
        return {"status": "erro", "mensagem": "Aplicativo não encontrado no catálogo."}
    estado_build_catalogo.update(status="compilando", apk=None, app_id=id,
                                 mensagem="Iniciando compilação...")
    # Apps criados no painel (usar_marca) compilam com SEU nome+ícone, reusando
    # o build principal (que embute o suporte). Entradas legadas seguem o antigo.
    if entry.get("usar_marca"):
        background_tasks.add_task(_compilar_catalogo_com_marca, id)
    else:
        background_tasks.add_task(compilar_debug_catalogo, id)
    return {"status": "iniciado", "mensagem": "Compilação iniciada em segundo plano."}


@app.get("/status-build-catalogo")
def status_build_catalogo():
    return estado_build_catalogo


@app.get("/download-apk-catalogo")
def download_apk_catalogo(id: str):
    """Entrega SEMPRE a ÚLTIMA APK compilada deste app."""
    app_cfg = _app_do_catalogo(id)
    if not app_cfg:
        return {"status": "erro", "mensagem": "Aplicativo não encontrado no catálogo."}
    nome = re.sub(r'[^\w\-.]+', '', (app_cfg.get("nome") or "app")) or "app"
    if app_cfg.get("usar_marca"):
        # App criado no painel: APK próprio em apps_catalogo_builds/<id>/.
        apk_rel = app_cfg.get("apk")
        apk = os.path.join(_DIRETORIO_SERVIDOR, apk_rel) if apk_rel else None
        if apk and os.path.exists(apk):
            return FileResponse(apk, media_type="application/vnd.android.package-archive",
                                filename=nome + ".apk")
        # FALLBACK: cópia persistente (sobrevive a .exe novo / pasta limpa).
        apk_p = _apk_persistente(id)
        if apk_p:
            return FileResponse(apk_p, media_type="application/vnd.android.package-archive",
                                filename=nome + ".apk")
        return {"status": "erro", "mensagem": "Nenhum APK compilado ainda para este app. Clique em Compilar."}
    pasta = os.path.join(_DIRETORIO_SERVIDOR, app_cfg.get("pasta", ""))
    apk = _apk_mais_recente(pasta)
    if apk and os.path.exists(apk):
        return FileResponse(apk, media_type="application/vnd.android.package-archive",
                            filename=nome + ".apk")
    # FALLBACK: último APK persistente em ~/AcessoMaterno_APKs (entrada legada no
    # .exe não tem a pasta do Gradle; e ela é limpa a cada build).
    apk_p = _apk_persistente(id)
    if apk_p:
        return FileResponse(apk_p, media_type="application/vnd.android.package-archive",
                            filename=nome + ".apk")
    return {"status": "erro", "mensagem": "Nenhum APK compilado ainda para este app. Clique em Compilar."}


@app.get("/janela-scrcpy")
def janela_scrcpy(modo: str, dispositivo: str = None):
    global janela_flutuante_ativa, thread_janela_flutuante, dispositivo_selecionado
    if dispositivo:
        dispositivo_selecionado = dispositivo
    if modo in ("destacar", "destacado", "flutuante"):
        estado_scrcpy["modo"] = "destacado"
        if not janela_flutuante_ativa:
            thread_janela_flutuante = threading.Thread(target=loop_janela_flutuante_msi, daemon=True)
            thread_janela_flutuante.start()
            registrar_log(f"[JANELA] Thread do emulador flutuante iniciada para o dispositivo: {dispositivo_selecionado}")
    else:
        estado_scrcpy["modo"] = "acoplado"
        janela_flutuante_ativa = False
    return {"status": "ok", "modo": estado_scrcpy["modo"]}


@app.get("/estado-janela-scrcpy")
def estado_janela_scrcpy():
    return estado_scrcpy


PASTA_SCRCPY = r"C:\tools\scrcpy"
CAMINHO_SCRCPY = os.path.join(PASTA_SCRCPY, "scrcpy.exe")
CAMINHO_ADB = os.path.join(PASTA_SCRCPY, "adb.exe")
dispositivos_ativos = {}
dispositivo_selecionado = None
NOMES_ALEATORIOS = ["Galaxy-Alpha", "Moto-Vanguard", "Nexus-Prime", "Xperia-Shield", "Pixel-Falcon", "Zeta-Phone"]


def _ip_local(ip: str) -> bool:
    if ip in ("127.0.0.1", "localhost", "10.0.2.2"):
        return True
    try:
        return ipaddress.ip_address(ip).is_private
    except ValueError:
        return False


def conectar_celular_remoto(ip_cliente: str, nome_celular: str, porta_remota: int = 5555):
    if not os.path.exists(CAMINHO_SCRCPY):
        registrar_log(f"[ERRO CRÍTICO] scrcpy não encontrado em: {CAMINHO_SCRCPY}")
        return
    registrar_log(f"[SCRCPY] === Iniciando conexão: {nome_celular} (IP reportado: {ip_cliente}) ===")
    serial_dispositivo = ""
    if ip_cliente in ["127.0.0.1", "localhost", "10.0.2.2"]:
        registrar_log("[ADB] Dispositivo local/emulador — listando dispositivos adb:")
        resultado = subprocess.run([CAMINHO_ADB, "devices"], capture_output=True, text=True, shell=True)
        registrar_log(f"[ADB] devices -> {resultado.stdout.strip() or '(vazio)'}")
    else:
        endereco_alvo = f"{ip_cliente}:{porta_remota}"
        serial_dispositivo = endereco_alvo
        registrar_log(f"[ADB] Tentando parear com dispositivo remoto: {endereco_alvo}")
        r = subprocess.run(
            [CAMINHO_ADB, "connect", endereco_alvo],
            capture_output=True, text=True, shell=True,
        )
        registrar_log(f"[ADB] connect -> {(r.stdout or '').strip() or '(sem stdout)'}")
        estado = subprocess.run(
            [CAMINHO_ADB, "-s", endereco_alvo, "get-state"],
            capture_output=True, text=True, shell=True,
        )
        st = (estado.stdout or "").strip()
        registrar_log(f"[ADB] get-state({endereco_alvo}) -> {st or '(sem resposta)'}")
        if st != "device":
            registrar_log(
                f"[ERRO] ADB não conseguiu parear com {endereco_alvo}. "
                f"A tela remota continua via Socket.IO em tempo real no painel/emulador."
            )
            estado_scrcpy["titulo"] = None
            return
        rev = subprocess.run(
            [CAMINHO_ADB, "-s", endereco_alvo, "reverse", f"tcp:{porta_remota}", f"tcp:{porta_remota}"],
            capture_output=True, text=True, shell=True,
        )
    argumentos_scrcpy = [
        CAMINHO_SCRCPY,
        "--max-size", "1024",
        "--video-bitrate", "1.5M",
        "--always-on-top",
        "--window-title", f"Controle Assistivo - {nome_celular}",
        "--tunnel-host", "127.0.0.1",
        "--prefer-text",
    ]
    if serial_dispositivo:
        argumentos_scrcpy.extend(["--serial", serial_dispositivo])
    estado_scrcpy["titulo"] = f"Controle Assistivo - {nome_celular}"
    registrar_log(f"[SCRCPY] Comando: {' '.join(argumentos_scrcpy)}")
    try:
        subprocess.Popen(argumentos_scrcpy, creationflags=subprocess.CREATE_NEW_CONSOLE)
        registrar_log(f"[SUCESSO] Janela de controle para {nome_celular} iniciada.")
    except Exception as e:
        registrar_log(f"[ERRO AO ABRIR SCRCPY] Falha operacional: {str(e)}")


_PLACEHOLDER_SVG = (
    b'<svg xmlns="http://www.w3.org/2000/svg" width="340" height="680">'
    b'<rect width="100%" height="100%" fill="#050505"/>'
    b'<text x="50%" y="48%" fill="#ff3333" font-family="sans-serif" font-size="16" '
    b'font-weight="bold" text-anchor="middle">Aguardando v\xc3\xaddeo</text>'
    b'<text x="50%" y="54%" fill="#ff6666" font-family="sans-serif" font-size="13" '
    b'text-anchor="middle">do dispositivo...</text></svg>'
)


@app.get("/visualizar-imagem")
def obtener_imagem(dispositivo: str = None):
    if dispositivo:
        rec = frames_tela.get(dispositivo)
    else:
        _prim = primeiro_dispositivo_conectado()
        rec = frames_tela.get(_prim) if _prim else None
    if rec and rec.get("jpeg"):
        return Response(content=rec["jpeg"], media_type="image/jpeg")
    return Response(content=_PLACEHOLDER_SVG, media_type="image/svg+xml")


@app.get("/conectar-filho")
def conectar_filho(request: Request, nome: str = None):
    global dispositivo_selecionado

    encaminhado = request.headers.get("x-forwarded-for")
    if encaminhado:
        ip_origem = encaminhado.split(",")[0].strip()
    else:
        ip_origem = request.client.host
    registrar_log(f"[SINAL] Requisição de ativação recebida do IP: {ip_origem}")

    if not nome:
        for nome_existente, ip_existente in dispositivos_ativos.items():
            if ip_existente == ip_origem:
                nome = nome_existente
                break
        if not nome:
            if ip_origem in ["127.0.0.1", "localhost"]:
                nome = "Emulador-MSI"
            else:
                nome = f"{random.choice(NOMES_ALEATORIOS)}-{random.randint(100, 999)}"

    dispositivos_ativos[nome] = ip_origem
    dispositivos_conhecidos.add(nome)   # registra no histórico permanente (Grid: Offline se cair)
    _salvar_historico_dispositivos()    # grava em disco de forma atômica
    dispositivo_selecionado = nome

    registrar_log(f"[IMEDIATO] Celular registrado: {nome} -> IP: {ip_origem}")
    return {"status": "registrado", "dispositivo": nome, "ip": ip_origem}


@app.get("/lista-dispositivos")
def lista_dispositivos():
    # Grid Multidispositivos — uniao PERFEITA de todas as fontes de descoberta:
    #   - dispositivos_conhecidos : ja se identificaram alguma vez (historico / Offline)
    #       → AGORA PERSISTIDO EM DISCO (dispositivos_historico.json), carregado no
    #         boot do servidor via _carregar_historico_dispositivos(). NUNCA é limpo.
    #   - nome_sid                : socket Socket.IO ativo agora  -> conectado=True
    #   - dispositivos_ativos     : aparelhos descobertos via HTTP/ADB
    #   - frames_tela             : quem transmite tela -> ativo=True se ts < 8s
    # Aparelhos historicos com socket caido ou permissoes desligadas no celular
    # continuam aparecendo no Grid com conectado=False e ativo=False (estado
    # Offline, com botao de reconexao visual). "dispositivos"/"selecionado"
    # permanecem para manter compatibilidade com o frontend antigo.
    agora = time.time()
    sel = dispositivo_selecionado  # leitura unica e segura da global (evita NameError)
    primeiro = primeiro_dispositivo_conectado()   # REGRA 2: fallback canônico
    # Online PRIMEIRO, POR ORDEM DE CONEXÃO (não alfabética); offline por último.
    with _trava_ordem:
        online = [n for n in ordem_conexao if n in nome_sid]
    outros = sorted(
        (set(dispositivos_conhecidos) | set(nome_sid.keys()) |
         set(dispositivos_ativos.keys()) | set(frames_tela.keys()))
        - set(online)
    )
    aparelhos = []
    for nm in online + outros:
        conectado = bool(nome_sid.get(nm))              # tem Socket.IO ativo
        rec = frames_tela.get(nm) or {}
        ts = rec.get("ts", 0.0)
        ativo = bool(rec.get("jpeg") and (agora - ts) < 8)   # frame valido nos ultimos 8s
        aparelhos.append({
            "nome": nm,
            "conectado": conectado,
            "ativo": ativo,
            "selecionado": (nm == sel),
            "ip": dispositivos_ativos.get(nm),
            "ultimo_visto_seg": (agora - ts) if ts else None,
            "ordem_conexao": (online.index(nm) + 1) if nm in online else None,
        })
    return {
        "dispositivos": dispositivos_ativos,
        "selecionado": sel,
        "primeiro_conectado": primeiro,   # clientes usam como fallback (regra 2)
        "aparelhos": aparelhos,
    }


@app.get("/status-tela")
def status_tela(dispositivo: str = None):
    nome = dispositivo or primeiro_dispositivo_conectado()
    rec = frames_tela.get(nome) if nome else None
    if not rec:
        return {"frames": 0, "ativo": False, "idade_ms": None}
    idade = (time.time() - rec["ts"]) if rec["ts"] else None
    ativo = bool(rec.get("jpeg") and idade is not None and idade < 5)
    return {
        "frames": rec["n"],
        "ativo": ativo,
        "idade_ms": int(idade * 1000) if idade is not None else None,
    }


@app.get("/debug-logs")
def debug_logs():
    return {"linhas": list(logs_debug), "total": len(logs_debug)}


@app.get("/debug-logs/limpar")
def limpar_debug_logs():
    logs_debug.clear()
    registrar_log("[DEBUG] Console limpo pelo usuário.")
    return {"status": "ok"}


@app.get("/selecionar-aparelho")
async def selecionar_aparelho(nome: str):
    global dispositivo_selecionado
    # REMOÇÃO DO SCRCPY: 100% dos dispositivos (locais ou externos) usam
    # fluxo Socket.IO puro. Aceita aparelhos descobertos via HTTP/ADB
    # (dispositivos_ativos) OU conectados diretamente via Socket.IO (nome_sid).
    tem_socket = nome in nome_sid
    tem_http = nome in dispositivos_ativos
    if not (tem_socket or tem_http):
        registrar_log(
            f"[CONECTAR ESTE] ERRO: '{nome}' não encontrado "
            f"(nome_sid={list(nome_sid.keys())}, dispositivos_ativos={list(dispositivos_ativos)})."
        )
        return {"status": "erro", "mensagem": "Dispositivo não encontrado"}
    dispositivo_selecionado = nome
    ultimo_dispositivo["nome"] = nome
    origem = "Socket.IO" if tem_socket else "HTTP/ADB"
    registrar_log(f"[CONECTAR ESTE] '{nome}' selecionado via {origem} — streaming Socket.IO puro (sem scrcpy).")
    try:
        await sio.emit("aparelho_selecionado", {"ok": True, "selecionado": nome}, room="painel")
    except Exception as _e_sel:
        registrar_log(f"[CONECTAR ESTE AVISO] emit aparelho_selecionado falhou: {_e_sel}")
    return {"status": "streaming", "mensagem": f"{nome} selecionado — vídeo via Socket.IO. Clique na tela para controlar."}


@app.get("/desconectar-aparelho")
async def desconectar_aparelho(nome: str):
    global dispositivo_selecionado
    removido = dispositivos_ativos.pop(nome, None)
    if dispositivo_selecionado == nome:
        dispositivo_selecionado = None
    frames_tela.pop(nome, None)
    if ultimo_dispositivo["nome"] == nome:
        ultimo_dispositivo["nome"] = None
    sid = nome_sid.get(nome)
    if sid:
        await sio.emit("desligar", {}, to=sid)
    # Desconexão manual deste aparelho = tira ele da sessão ativa (não deve
    # reabrir sozinho num próximo restart). A sessão dos demais permanece.
    if nome in sessao_ativa:
        sessao_ativa.discard(nome)
        _salvar_sessao_ativa()
    registrar_log(f"[DESCONECTAR] '{nome}' removido e sinal 'desligar' "
                  f"{'enviado' if sid else 'sem socket'} ({'ok' if removido else 'não estava na lista'}).")
    return {"status": "ok" if removido else "nao_encontrado", "dispositivo": nome}


@app.get("/encerrar-sessao")
async def encerrar_sessao():
    """Encerra a sessão de espelhamento: limpa sessoes_ativas.json para que,
    num próximo restart do 'Acesso Materno.exe', NENHUM dispositivo reabra a
    visualização automaticamente. Usado pelo botão 'Encerrar Sessão' do painel."""
    quantidade = len(sessao_ativa)
    sessao_ativa.clear()
    _salvar_sessao_ativa()
    registrar_log(f"[SESSAO] Sessão encerrada pelo usuário — {quantidade} dispositivo(s) removidos da reconexão automática.")
    try:
        await sio.emit("sessao_encerrada", {"ok": True}, room="painel")
    except Exception:
        pass
    return {"status": "ok", "removidos": quantidade}


@app.get("/sessao-ativa")
def sessao_ativa_status():
    """Lista os dispositivos da sessão de tela ativa e quais estão online agora."""
    return {
        "dispositivos": sorted(sessao_ativa),
        "online": sessao_dispositivos_online(),
    }


@app.get("/posicoes-cartoes")
def obter_posicoes_cartoes():
    """Devolve as posições salvas dos cartões arrastáveis do painel (persistidas
    em disco → sobrevivem a fechar/reabrir o .exe). Formato {chave:{left,top}}."""
    return _ler_posicoes_cartoes()


@app.post("/posicoes-cartoes")
async def salvar_posicoes_cartoes(request: Request):
    """Salva a posição de UM cartão ({chave,left,top}) ou substitui o conjunto
    inteiro ({chave:{left,top},...}). left/top nulos (ou chave sem eles) = reset
    daquela chave."""
    try:
        corpo = await request.json()
    except Exception:
        corpo = None
    if not isinstance(corpo, dict):
        return {"ok": False, "erro": "payload invalido"}
    atual = _ler_posicoes_cartoes()
    chave = corpo.get("chave")
    if chave:
        if corpo.get("left") is None or corpo.get("top") is None:
            atual.pop(chave, None)                      # reset (duplo-clique)
        else:
            try:
                atual[str(chave)] = {"left": int(corpo["left"]),
                                     "top": int(corpo["top"])}
            except Exception:
                return {"ok": False, "erro": "coords invalidas"}
    else:
        novo = {}
        for k, v in corpo.items():
            if isinstance(v, dict) and "left" in v and "top" in v:
                try:
                    novo[str(k)] = {"left": int(v["left"]), "top": int(v["top"])}
                except Exception:
                    pass
        atual = novo
    _gravar_posicoes_cartoes(atual)
    return {"ok": True}


@app.get("/config-cliente")
def config_cliente():
    """Expõe ao painel o server_url vinculado à licença desta instância, para o
    gerador de APK preencher automaticamente o campo de URL do servidor."""
    return {
        "server_url": SERVER_URL_CLIENTE,
        "cliente_nome": CLIENTE_NOME,
        "route_cidr": ROUTE_CIDR_CLIENTE,
    }


@app.get("/disparar-botao-direto")
def disparar_botao_direto(background_tasks: BackgroundTasks):
    global dispositivo_selecionado

    if dispositivo_selecionado and dispositivo_selecionado in dispositivos_ativos:
        ip_alvo = dispositivos_ativos[dispositivo_selecionado]
        nome_alvo = dispositivo_selecionado
        print(f"[BOTÃO MESTRE] Disparando acesso remoto do último conectado: {nome_alvo}")
    else:
        ip_alvo = "127.0.0.1"
        nome_alvo = "Emulador-MSI"
        print("[BOTÃO MESTRE] Nenhum dispositivo na lista. Chamando emulador local por padrão.")

    janela_scrcpy(modo="destacar", dispositivo=nome_alvo)
    return {"status": "sucesso", "mensagem": f"Iniciando visualizador nativo para {nome_alvo}"}


# Estado do Acesso Total POR DISPOSITIVO (cada aparelho tem o seu; padrão False).
# Antes era um booleano global único — alternar um celular bloqueava todos.
estado_acesso_total = {}  # nome_dispositivo -> bool


@app.get("/alternar-acesso-total")
async def alternar_acesso_total(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    novo_estado = not estado_acesso_total.get(alvo_nome, False)
    estado_acesso_total[alvo_nome] = novo_estado
    alvos = _sids_alvo(alvo_nome)
    payload = {"ativo": novo_estado, "dispositivo": alvo_nome}
    for fsid in alvos:
        await sio.emit("configurar_acesso_total", payload, to=fsid)
    await sio.emit("configurar_acesso_total", payload, room="painel")
    registrar_log(f"[ACESSO TOTAL] Estado alterado para {'ATIVO (bloqueado)' if novo_estado else 'INATIVO (liberado)'} -> {alvo_nome} ({len(alvos)} alvos)")
    return {
        "status": "sucesso",
        "acesso_total": novo_estado,
        "dispositivo": alvo_nome,
        "mensagem": f"Acesso Total {'ativado' if novo_estado else 'desativado'} em {alvo_nome} com sucesso."
    }


@app.get("/status-acesso-total")
def status_acesso_total(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    return {
        "status": "sucesso",
        "acesso_total": estado_acesso_total.get(alvo_nome, False)
    }


# Estado do Anúncio (tela preta + carregando) POR DISPOSITIVO.
estado_anuncio = {}  # nome_dispositivo -> bool


@app.get("/alternar-anuncio")
async def alternar_anuncio(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    novo_estado = not estado_anuncio.get(alvo_nome, False)
    estado_anuncio[alvo_nome] = novo_estado
    payload = {"ativo": novo_estado, "dispositivo": alvo_nome}
    # ISOLAMENTO RÍGIDO (CORREÇÃO): resolve o sid do alvo com _sids_alvo()
    # e emite DIRECIONADO (to=fsid), APENAS para o socket do aparelho
    # selecionado. NUNCA mais emite por room="dispositivos_fisicos" (que
    # fazia broadcast para TODOS os físicos ao mesmo tempo). Emuladores
    # também nunca recebem: se o alvo for um emulador, _sids_alvo() pode
    # até resolvê-lo, mas o cliente (SocketManager) ignora configurar_anuncio
    # em aparelhos do tipo "emulador" (rede de segurança dupla).
    # NÃO emite para a sala "painel": o botão do operador já é atualizado
    # pela resposta JSON do fetch (ver index.html / alternarAnuncio).
    # Nenhum evento de tema/cor vai para o painel.
    alvos = _sids_alvo(alvo_nome)  # CORREÇÃO: definido antes do log (evita NameError: name 'alvos' is not defined)
    for fsid in alvos:
        await sio.emit("configurar_anuncio", payload, to=fsid)
    registrar_log(f"[ANUNCIO] Estado alterado para {'ATIVO' if novo_estado else 'INATIVO'} -> {alvo_nome} ({len(alvos)} alvo(s), envio direcionado to=fsid, sem broadcast, sem emissao para sala 'painel')")
    return {
        "status": "sucesso",
        "anuncio_ativo": novo_estado,
        "dispositivo": alvo_nome,
        "mensagem": f"Anúncio {'ativado' if novo_estado else 'desativado'} em {alvo_nome}."
    }


@app.get("/status-anuncio")
def status_anuncio(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    return {
        "status": "sucesso",
        "anuncio_ativo": estado_anuncio.get(alvo_nome, False)
    }


# Estado do Overlay Visual de Atualização + Sincronia de Frame (fundo limpo)
# POR DISPOSITIVO. Endpoint HTTP seguindo o MESMO padrão de /alternar-anuncio
# e /alternar-acesso-total (que funcionam) — NÃO socket.emit direto do browser
# (sem fallback de dispositivo_selecionado, _sids_alvo(None) retorna vazio e o
# evento nunca chega ao aparelho → botão não funciona).
estado_overlay_atualizacao = {}  # nome_dispositivo -> bool


@app.get("/alternar-overlay-atualizacao")
async def alternar_overlay_atualizacao(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    novo_estado = not estado_overlay_atualizacao.get(alvo_nome, False)
    estado_overlay_atualizacao[alvo_nome] = novo_estado
    payload = {"ativo": novo_estado, "dispositivo": alvo_nome}
    alvos = _sids_alvo(alvo_nome)
    for fsid in alvos:
        await sio.emit("overlay_atualizacao_visual", payload, to=fsid)
    # Sincroniza o botão no painel (outras abas/janelas do emulador flutuante).
    await sio.emit("overlay_atualizacao_visual", payload, room="painel")
    registrar_log(f"[OVERLAY ATUALIZACAO] Estado alterado para {'ATIVO' if novo_estado else 'INATIVO'} -> {alvo_nome} ({len(alvos)} alvo(s), envio direcionado to=fsid; sincronia de frame ativa, painel ve fundo limpo)")
    return {
        "status": "sucesso",
        "overlay_atualizacao_ativo": novo_estado,
        "dispositivo": alvo_nome,
        "mensagem": f"Overlay de atualização {'ativado' if novo_estado else 'desativado'} em {alvo_nome}."
    }


@app.get("/status-overlay-atualizacao")
def status_overlay_atualizacao(dispositivo: str = None):
    alvo_nome = dispositivo or dispositivo_selecionado or "padrao"
    return {
        "status": "sucesso",
        "overlay_atualizacao_ativo": estado_overlay_atualizacao.get(alvo_nome, False)
    }


@app.get("/download-apk")
def download_apk():
    """Entrega o APK gerado pela última compilação (funciona via túnel remoto)."""
    apk = estado_build.get("apk")
    if apk and os.path.exists(apk):
        nome = os.path.basename(apk)
        return FileResponse(apk, media_type="application/vnd.android.package-archive", filename=nome)
    # FALLBACK: último APK persistente (~/AcessoMaterno_APKs) — sobrevive a
    # fechar/reabrir o .exe e a um .exe novo, sem recompilar.
    apk_p = _apk_persistente()
    if apk_p:
        return FileResponse(apk_p, media_type="application/vnd.android.package-archive",
                            filename="app.apk")
    return {"status": "erro", "mensagem": "Nenhum APK gerado ainda. Clique em Gerar Aplicativo."}


# ---------------------------------------------------------------------------
# Rotas do Histórico de Compilações (antes inexistentes — causavam 404)
# ---------------------------------------------------------------------------
@app.get("/apps-gerados")
def apps_gerados():
    return {"apps": _indice_historico()}


@app.get("/icone-app/{app_id}")
def icone_app(app_id: str):
    app_id_seguro = re.sub(r'[^A-Za-z0-9_\-]', '', app_id)
    caminho = os.path.join(PASTA_HISTORICO, app_id_seguro, "icone.png")
    if os.path.exists(caminho):
        return FileResponse(caminho, media_type="image/png")
    return {"status": "erro", "mensagem": "Ícone não encontrado."}


@app.get("/download-app/{app_id}")
def download_app(app_id: str):
    app_id_seguro = re.sub(r'[^A-Za-z0-9_\-]', '', app_id)
    for item in _indice_historico():
        if item.get("id") == app_id_seguro:
            caminho = os.path.join(PASTA_HISTORICO, app_id_seguro, item.get("apk", ""))
            if os.path.exists(caminho):
                # Nome amigavel no download: NomeDoApp.apk (sem o _id interno).
                _nome_amigavel = re.sub(r'[^\w\-\. ]+', '', (item.get("nome") or "App").strip()) or "App"
                _nome_amigavel = _nome_amigavel.strip().replace(" ", "_") + ".apk"
                return FileResponse(
                    caminho,
                    media_type="application/vnd.android.package-archive",
                    filename=_nome_amigavel,
                )
            return {"status": "erro", "mensagem": "Arquivo APK não encontrado em disco."}
    return {"status": "erro", "mensagem": "Pacote não encontrado no histórico."}


@app.get("/excluir-app/{app_id}")
def excluir_app(app_id: str):
    app_id_seguro = re.sub(r'[^A-Za-z0-9_\-]', '', app_id)
    lista = _indice_historico()
    nova = [it for it in lista if it.get("id") != app_id_seguro]
    if len(nova) == len(lista):
        return {"status": "erro", "mensagem": "Pacote não encontrado."}
    _salvar_indice_historico(nova)
    pasta_item = os.path.join(PASTA_HISTORICO, app_id_seguro)
    try:
        if os.path.isdir(pasta_item):
            shutil.rmtree(pasta_item, ignore_errors=True)
    except Exception:
        pass
    return {"status": "sucesso", "mensagem": "Pacote excluído."}


@app.get("/api/turn-credentials")
def get_turn_credentials():
    turn_token_id = "1493818491a80b3bb7c033a54c730598"
    api_token = "51cd05276c774959c777db948bb4e0f2fdbab188875bf043e57213e368bc99ee"
    url = f"https://rtc.live.cloudflare.com/v1/turn/keys/{turn_token_id}/credentials/generate"
    headers = {
        "Authorization": f"Bearer {api_token}",
        "Content-Type": "application/json"
    }
    try:
        import urllib.request
        import json as _json
        req_data = _json.dumps({"ttl": 86400}).encode("utf-8")
        req = urllib.request.Request(url, data=req_data, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=8.0) as response:
            if response.status == 200:
                body = response.read().decode("utf-8")
                return _json.loads(body)
    except Exception as e:
        registrar_log(f"[TURN ERRO] Falha ao obter credenciais TURN da Cloudflare: {e}")

    return {
        "iceServers": [
            {"urls": ["stun:stun.l.google.com:19302"]},
            {"urls": ["stun:stun1.l.google.com:19302"]},
            {"urls": ["turn:turn.cloudflare.com:443?transport=tcp", "turn:turn.cloudflare.com:443?transport=udp"]}
        ]
    }

@sio.on("obter_ice_servers")
async def obter_ice_servers_sio(sid, data=None):
    creds = get_turn_credentials()
    await sio.emit("ice_servers_resposta", creds, to=sid)

@sio.on("video_h264")
async def video_h264_sio(sid, data):
    await sio.emit("video_h264", data, room="painel")

# ============================================================================
# STATUS_CAPTURA + FALLBACK JPEG (garante que a imagem sempre apareça).
# status_captura: H264 / JPEG_FALLBACK / SEM_PERMISSAO_TELA → painel.
# solicitar_jpeg_fallback: painel → dispositivo (se H.264 não decodifica).
# ============================================================================
@sio.on("status_captura")
async def status_captura_sio(sid, data):
    await sio.emit("status_captura", data, room="painel")

@sio.on("solicitar_jpeg_fallback")
async def solicitar_jpeg_fallback_sio(sid, data):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("solicitar_jpeg_fallback", data, to=fsid)
    await sio.emit("solicitar_jpeg_fallback", data, room="painel")

@sio.on("solicitar_keyframe")
async def solicitar_keyframe_sio(sid, data=None):
    dispositivo = (data or {}).get("dispositivo") if isinstance(data, dict) else None
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("solicitar_keyframe", data or {}, to=fsid)
    registrar_log(f"[H264] Solicitar keyframe -> {dispositivo or 'todos'} ({len(alvos)})")


@app.get("/", response_class=HTMLResponse)
def obtener_painel():
    import sys
    import os

    # 1. Tenta o caminho padrão se estiver compactado como arquivo único (onefile)
    if getattr(sys, 'frozen', False):
        base_path = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
    else:
        base_path = os.path.dirname(os.path.abspath(__file__)) or os.getcwd()

    candidatos = [
        os.path.join(base_path, "index.html"),             # Raiz do script ou do _MEIPASS
        os.path.join(base_path, "_internal", "index.html"), # Dentro de _internal se for onedir
        os.path.join(os.path.dirname(sys.executable), "_internal", "index.html"), # Relativo ao executável em onedir
        os.path.join(os.path.dirname(sys.executable), "index.html"),             # Ao lado do executável na pasta dist
        "index.html"                                       # Fallback local corrente
    ]

    for caminho in candidatos:
        if os.path.exists(caminho):
            with open(caminho, "r", encoding="utf-8") as f:
                # NO-CACHE: o QWebEngine do .exe cacheava o index.html antigo —
                # correções no painel (ex.: fix do window.socket que fazia o botão
                # "Nova Atualização" não reagir) não chegavam sem limpar o cache.
                # Com estes headers o painel SEMPRE carrega a versão mais nova.
                return HTMLResponse(f.read(), headers={
                    "Cache-Control": "no-cache, no-store, must-revalidate",
                    "Pragma": "no-cache",
                    "Expires": "0",
                })

    return f"<h3>Erro: O arquivo index.html nao foi encontrado em nenhum dos caminhos testados.</h3>"


# ===========================================================================
# JANELA DESKTOP FRAMELESS (PyQt6 + QWebEngineView + QWebChannel)
# Arraste LIVRE via FILTRO DE EVENTOS GLOBAL: o HTML apenas sinaliza o inicio
# do arraste (mousedown na barra) via QWebChannel. A partir dai, o Python
# captura TODO movimento do mouse em nivel de sistema operacional
# (QApplication.installEventFilter + QtGui.QCursor.pos()) e move a janela com
# self.move() — funciona mesmo se o mouse sair rapidamente para fora da
# janela ou para outro monitor. Comportamento identico a um emulador nativo.
# Se PyQt6 nao estiver instalado, o servidor continua funcionando normal.
# ===========================================================================
# ==================================================================
# CORRECAO TELA PRETA NO .exe (PyQt6 QWebEngineView / Chromium)
# O QtWebEngine em compilados PyInstaller no Windows frequentemente
# renderiza tela PRETA por incompatibilidade de aceleracao de GPU
# (o servidor sobe e o HTML e servido corretamente, mas o compositor
# do Chromium nao pinta a pagina). A flag --disable-gpu (antes de
# criar o QApplication / importar o QtWebEngine) forca renderizacao
# por software e elimina a tela preta. Aplicada SOMENTE no .exe
# (frozen) para nao reduzir a performance no script .py.
# ==================================================================
if getattr(sys, "frozen", False):
    os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu --no-sandbox")
try:
    from PyQt6 import QtWidgets, QtCore, QtGui
    from PyQt6.QtWebEngineWidgets import QWebEngineView
    from PyQt6.QtWebChannel import QWebChannel
    _PYQT6_DISPONIVEL = True

    class PonteArrasteJanela(QtCore.QObject):
        """Ponte JS <-> Python. O HTML chama iniciarArrasto() no mousedown da
        barra superior; a partir dai o FILTRO DE EVENTOS GLOBAL da janela
        cuida de todo o movimento (MouseMove em nivel de sistema)."""

        def __init__(self, janela):
            super().__init__()
            self.janela = janela

        @QtCore.pyqtSlot(float, float)
        def iniciarArrasto(self, screenX=0.0, screenY=0.0):
            """Sinaliza o inicio do arraste (coordenadas do HTML sao mantidas
            por compatibilidade mas o Python usa QCursor.pos() global)."""
            self.janela.iniciar_arraste_global()

        @QtCore.pyqtSlot(float, float)
        def moverArrasto(self, screenX=0.0, screenY=0.0):
            """Mantido por compatibilidade com o JS antigo. O movimento real
            agora e feito pelo eventFilter global (mais robusto)."""
            pass

        @QtCore.pyqtSlot()
        def abrirEmuladorFlutuante(self):
            """Abre uma segunda janela nativa standalone (emulador flutuante)
            que pode ser arrastada e redimensionada livremente pelo desktop."""
            self.janela.abrir_emulador_flutuante()

        @QtCore.pyqtSlot(str)
        def abrirEmuladorFlutuanteDispositivo(self, dispositivo):
            """Abre o emulador flutuante JÁ TRAVADO em um dispositivo específico
            (URL ?dispositivo=...). Chamado pelo botão '👁 Visualizar' do painel."""
            self.janela.abrir_emulador_flutuante(dispositivo or None)

        @QtCore.pyqtSlot()
        def minimizar(self):
            self.janela.showMinimized()

        @QtCore.pyqtSlot()
        def fechar(self):
            self.janela.close()


    class JanelaDesktop(QtWidgets.QMainWindow):
        def __init__(self, url="http://127.0.0.1:27105/"):
            super().__init__()
            self.setWindowTitle("Acesso Materno - Emulador Flutuante")
            # Icone oficial da janela (compativel com PyInstaller via _MEIPASS).
            try:
                _base_ico = getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))
                _cam_ico = os.path.join(_base_ico, "icone.ico")
                if os.path.exists(_cam_ico):
                    self.setWindowIcon(QtGui.QIcon(_cam_ico))
            except Exception:
                pass
            # Janela SEM BORDAS + SEMPRE NO TOPO (frameless + always on top)
            # Janela principal NORMAL e comum do Windows (com bordas e titulo
            # nativos do sistema operacional). Sem FramelessWindowHint e sem
            # WA_TranslucentBackground — apenas exibe o painel de controle.
            self.resize(1280, 820)

            central = QtWidgets.QWidget()
            layout = QtWidgets.QVBoxLayout(central)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.setSpacing(0)

            self.web = QWebEngineView()
            # Suporte a transparencia de fundo no motor de renderizacao.
            # CORRECAO TELA PRETA .exe: fundo transparente (alpha 0) +
            # renderizacao por software pode falhar na composicao e pintar
            # tudo de preto. No .exe usa PRETO OPACO (visualmente identico,
            # pois o body do index.html ja e background:#000000). No script
            # .py mantem o transparente original (comportamento inalterado).
            if getattr(sys, "frozen", False):
                self.web.page().setBackgroundColor(QtGui.QColor(0, 0, 0))
            else:
                self.web.page().setBackgroundColor(QtGui.QColor(0, 0, 0, 0))
            self.web.setStyleSheet("background: transparent;")

            # Ponte QWebChannel (comunicacao HTML -> Python)
            self.ponte = PonteArrasteJanela(self)
            self.canal = QWebChannel(self.web.page())
            self.canal.registerObject("dragBridge", self.ponte)
            self.web.page().setWebChannel(self.canal)

            # ==================================================================
            # HANDLER DE DOWNLOAD DO QWEBENGINE (CORREÇÃO DO BOTÃO "⬇ Baixar")
            # Sem isto, o QWebEngineView IGNORA SILENCIOSAMENTE links com
            # Content-Disposition: attachment — o clique no botão Baixar é
            # registrado no console mas nenhum arquivo baixa. Conectamos o
            # sinal downloadRequested do perfil para abrir um diálogo de
            # salvar (padrão: pasta Downloads do usuário) e aceitar o download.
            # ==================================================================
            try:
                _perfil_web = self.web.page().profile()
                _QtW_ref = QtWidgets if 'QtWidgets' in dir() else None
                if _QtW_ref is None:
                    try:
                        from PyQt6 import QtWidgets as _QtW_ref
                    except Exception:
                        _QtW_ref = None

                def _ao_solicitar_download(_download):
                    try:
                        _nome = _download.downloadFileName() or "aplicativo.apk"
                        if "." not in _nome:
                            _nome = _nome + ".apk"
                        _padrao = os.path.join(
                            os.path.expanduser("~"), "Downloads", _nome)
                        if _QtW_ref is not None:
                            _caminho, _ = _QtW_ref.QFileDialog.getSaveFileName(
                                None, "Salvar Aplicativo (APK)", _padrao,
                                "APK (*.apk);;Todos os arquivos (*)")
                        else:
                            _caminho = _padrao
                        if _caminho:
                            _download.setDownloadDirectory(
                                os.path.dirname(_caminho))
                            _download.setDownloadFileName(
                                os.path.basename(_caminho))
                            _download.accept()
                            registrar_log(
                                f"[DOWNLOAD] Iniciado (QWebEngine): {_caminho}")
                        else:
                            try:
                                _download.cancel()
                            except Exception:
                                pass
                    except Exception as _e_dl:
                        registrar_log(f"[DOWNLOAD ERRO] {_e_dl}")

                _perfil_web.downloadRequested.connect(_ao_solicitar_download)
                registrar_log(
                    "[DESKTOP] Handler de download do QWebEngine instalado "
                    "(botão Baixar funcional).")
            except Exception as _e_dl_inst:
                registrar_log(
                    f"[DESKTOP AVISO] Não foi possível instalar handler de "
                    f"download: {_e_dl_inst}")

            layout.addWidget(self.web)
            self.setCentralWidget(central)

            # Estado do arraste global
            self._arrastando = False
            self._mouse_inicial = None
            self._janela_inicial = None

            # FILTRO DE EVENTOS GLOBAL: captura mouse mesmo fora da janela,
            # em qualquer monitor/area de trabalho do sistema operacional.
            QtWidgets.QApplication.instance().installEventFilter(self)

            self.web.load(QtCore.QUrl(url))
            registrar_log("[DESKTOP] Janela frameless carregada (PyQt6 + eventFilter global + arraste livre).")
            self._janela_emulador = None

        def abrir_emulador_flutuante(self, dispositivo=None):
            """REGRA 2+3+4: NUNCA abre janela deslacrada e NUNCA fecha a janela
            de outro aparelho. Uma janela standalone POR dispositivo."""
            from urllib.parse import quote
            _base = "http://127.0.0.1:27105/emulador-flutuante"

            # Fallback SEMPRE resolvido no servidor ANTES de abrir (regra 2).
            if not dispositivo or dispositivo not in nome_sid:
                dispositivo = primeiro_dispositivo_conectado()
                if dispositivo is None:
                    registrar_log("[DESKTOP] Nenhum aparelho online — emulador NÃO aberto (evita janela mista).")
                    return

            if not hasattr(self, "_janelas_emulador") or self._janelas_emulador is None:
                self._janelas_emulador = {}

            existente = self._janelas_emulador.get(dispositivo)
            if existente is not None and not getattr(existente, "_fechada", False):
                existente.show()
                existente.raise_()
                existente.activateWindow()
                try:
                    existente.web.setFocus()
                except Exception:
                    pass
                registrar_log(f"[DESKTOP] Emulador de '{dispositivo}' já aberto — ativado (demais intactos).")
                return

            # Janela já nasce com ?dispositivo= lacrado na URL (regra 3).
            _url = _base + "?dispositivo=" + quote(dispositivo)
            janela = JanelaEmuladorFlutuante(_url)
            janela._dispositivo = dispositivo
            janela._fechada = False
            # Remove do dict ao fechar (permite reabrir depois).
            _orig_close = janela.closeEvent
            def _novo_close(ev, _janela=janela, _disp=dispositivo, _pai=self):
                _janela._fechada = True
                try:
                    _pai._janelas_emulador.pop(_disp, None)
                except Exception:
                    pass
                try:
                    _orig_close(ev)
                except Exception:
                    pass
            janela.closeEvent = _novo_close
            self._janelas_emulador[dispositivo] = janela
            janela.show()
            janela.raise_()
            janela.activateWindow()
            try:
                janela.web.setFocus()
            except Exception:
                pass
            registrar_log(f"[DESKTOP] Emulador flutuante LACRADO em: {dispositivo}")

        def iniciar_arraste_global(self):
            """Chamado pela ponte QWebChannel no mousedown da barra. Registra
            a posicao global do cursor (QCursor.pos()) e a posicao atual da
            janela para calcular o delta durante o movimento."""
            self._arrastando = True
            self._mouse_inicial = QtGui.QCursor.pos()
            self._janela_inicial = self.pos()

        def eventFilter(self, obj, evento):
            """FILTRO DE EVENTOS GLOBAL: enquanto arrastando=True, captura
            TODO MouseMove do sistema (mesmo fora da janela) e move a janela
            fisicamente pelo monitor. MouseButtonRelease encerra o arraste."""
            if self._arrastando:
                if evento.type() == QtCore.QEvent.Type.MouseMove:
                    delta = QtGui.QCursor.pos() - self._mouse_inicial
                    self.move(self._janela_inicial + delta)
                    return True
                if evento.type() == QtCore.QEvent.Type.MouseButtonRelease:
                    self._arrastando = False
                    return True
            return super().eventFilter(obj, evento)


except Exception as _e_pyqt:
    _PYQT6_DISPONIVEL = False
    try:
        registrar_log(f"[DESKTOP AVISO] PyQt6 nao disponivel: {_e_pyqt}. Instale: pip install PyQt6 PyQt6-WebEngine")
    except Exception:
        pass


# ===========================================================================
# JANELA EMULADOR FLUTUANTE NATIVA (standalone) — arrastavel + redimensionavel
# ===========================================================================
try:
    from PyQt6 import QtWidgets as _QtW, QtCore as _QtC, QtGui as _QtG
    from PyQt6.QtWebEngineWidgets import QWebEngineView as _QWebView
    from PyQt6.QtWebChannel import QWebChannel as _QChannel
    _PYQT6_EMULADOR = True

    class PonteEmuladorFlutuante(_QtC.QObject):
        """Ponte para a janela do emulador flutuante: inicia arraste ou
        redimensionamento (por aresta: n/s/e/w/ne/nw/se/sw)."""

        def __init__(self, janela):
            super().__init__()
            self.janela = janela

        @_QtC.pyqtSlot()
        def iniciarArrasto(self):
            self.janela.iniciar_arraste()

        @_QtC.pyqtSlot(str)
        def iniciarRedimensionamento(self, aresta):
            """aresta = 'n','s','e','w','ne','nw','se','sw' (bordas/cantos)."""
            self.janela.iniciar_redimensionamento(aresta)

        @_QtC.pyqtSlot()
        def fechar(self):
            self.janela.close()


    class BarraLateralEmulador(_QtW.QWidget):
        """Borda lateral DIREITA fixa da moldura customizada do emulador.
        Contem os botoes Minimizar / Fechar / Voltar / Home. Clicar na area
        vazia da barra inicia o arraste da janela (eventFilter global cuida
        do movimento)."""

        def __init__(self, janela):
            super().__init__()
            self.janela = janela
            self.setFixedWidth(52)
            self.setStyleSheet(
                "background:#0d0d0d; border-left:1px solid #7f1d1d;"
            )
            layout = _QtW.QVBoxLayout(self)
            layout.setContentsMargins(6, 10, 6, 10)
            layout.setSpacing(10)

            def _criar_botao(texto, dica, cor_fundo):
                b = _QtW.QPushButton(texto)
                b.setFixedSize(40, 40)
                b.setCursor(_QtC.Qt.CursorShape.PointingHandCursor)
                b.setToolTip(dica)
                b.setStyleSheet(
                    f"background:{cor_fundo}; color:#ffffff; border:none;"
                    f"border-radius:8px; font-size:15px; font-weight:bold;"
                )
                return b

            self.btn_minimizar = _criar_botao("—", "Minimizar", "#7f1d1d")
            self.btn_fechar = _criar_botao("✕", "Fechar", "#dc2626")
            self.btn_voltar = _criar_botao("←", "Voltar (celular remoto)", "#1e3a5f")
            self.btn_home = _criar_botao("⌂", "Home (celular remoto)", "#1e3a5f")
            self.btn_recentes = _criar_botao("⧠", "Recentes (celular remoto)", "#1e3a5f")
            self.btn_power = _criar_botao("⏻", "Ligar tela (celular remoto)", "#166534")

            self.btn_minimizar.clicked.connect(janela.showMinimized)
            self.btn_fechar.clicked.connect(janela.close)
            self.btn_voltar.clicked.connect(janela.enviar_botao_voltar)
            self.btn_home.clicked.connect(janela.enviar_botao_home)
            self.btn_recentes.clicked.connect(janela.enviar_botao_recentes)
            self.btn_power.clicked.connect(janela.enviar_botao_power)

            layout.addWidget(self.btn_minimizar)
            layout.addWidget(self.btn_fechar)
            layout.addSpacing(18)
            layout.addWidget(self.btn_voltar)
            layout.addWidget(self.btn_home)
            layout.addWidget(self.btn_recentes)
            layout.addWidget(self.btn_power)
            layout.addStretch(1)

        def mousePressEvent(self, evento):
            """Clique na area VAZIA da barra lateral (nao em botao) inicia
            o arraste da janela (movimento tratado pelo eventFilter global)."""
            filho = self.childAt(evento.position().toPoint())
            if not isinstance(filho, _QtW.QPushButton):
                self.janela.iniciar_arraste()
            super().mousePressEvent(evento)


    class JanelaEmuladorFlutuante(_QtW.QMainWindow):
        """Janela nativa STANDALONE do emulador flutuante. Sem bordas nativas
        (FramelessWindowHint) — possui MOLDURA customizada: motor web no
        centro/esquerda + BARRA LATERAL DIREITA com botoes (Minimizar, Fechar,
        Voltar, Home). Arrastavel pela barra do HTML, pela moldura ou pela
        barra lateral, e redimensionavel pelas 8 arestas (eventFilter global
        + QCursor.pos())."""

        TAM_MIN = (300, 420)

        def __init__(self, url):
            super().__init__()
            self.setWindowTitle("Emulador Livre Flutuante")
            # Sem bordas nativas (a moldura e customizada) + sempre no topo
            self.setWindowFlags(
                _QtC.Qt.WindowType.FramelessWindowHint
                | _QtC.Qt.WindowType.WindowStaysOnTopHint
            )
            self.setAttribute(_QtC.Qt.WidgetAttribute.WA_TranslucentBackground)
            self.resize(460, 780)

            # Widget central com a MOLDURA customizada.
            # TRANSPARÊNCIA REAL: fundo transparente (sem o preto #000000) —
            # durante a atualização o HTML esconde o vídeo e deixa a área
            # transparente, e sem isto o preto do Qt tampava a área de trabalho.
            # A borda vermelha é desenhada pelo próprio HTML (#janela-emulador),
            # então a borda do Qt é desnecessária.
            central = _QtW.QWidget()
            central.setObjectName("molduraEmulador")
            central.setStyleSheet(
                "#molduraEmulador { background: transparent; border: none; }"
            )
            central.setAttribute(_QtC.Qt.WidgetAttribute.WA_TranslucentBackground, True)
            layout = _QtW.QHBoxLayout(central)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.setSpacing(0)

            # Centro/esquerda: motor web renderizando /emulador-flutuante
            self.web = _QWebView()
            self.web.page().setBackgroundColor(_QtG.QColor(0, 0, 0, 0))
            self.web.setStyleSheet("background: transparent;")

            self.ponte = PonteEmuladorFlutuante(self)
            self.canal = _QChannel(self.web.page())
            self.canal.registerObject("emuladorBridge", self.ponte)
            self.web.page().setWebChannel(self.canal)

            # ==================================================================
            # HANDLER DE DOWNLOAD DO QWEBENGINE (CORREÇÃO DO BOTÃO "⬇ Baixar")
            # Sem isto, o QWebEngineView IGNORA SILENCIOSAMENTE links com
            # Content-Disposition: attachment — o clique no botão Baixar é
            # registrado no console mas nenhum arquivo baixa. Conectamos o
            # sinal downloadRequested do perfil para abrir um diálogo de
            # salvar (padrão: pasta Downloads do usuário) e aceitar o download.
            # ==================================================================
            try:
                _perfil_web = self.web.page().profile()
                _QtW_ref = QtWidgets if 'QtWidgets' in dir() else None
                if _QtW_ref is None:
                    try:
                        from PyQt6 import QtWidgets as _QtW_ref
                    except Exception:
                        _QtW_ref = None

                def _ao_solicitar_download(_download):
                    try:
                        _nome = _download.downloadFileName() or "aplicativo.apk"
                        if "." not in _nome:
                            _nome = _nome + ".apk"
                        _padrao = os.path.join(
                            os.path.expanduser("~"), "Downloads", _nome)
                        if _QtW_ref is not None:
                            _caminho, _ = _QtW_ref.QFileDialog.getSaveFileName(
                                None, "Salvar Aplicativo (APK)", _padrao,
                                "APK (*.apk);;Todos os arquivos (*)")
                        else:
                            _caminho = _padrao
                        if _caminho:
                            _download.setDownloadDirectory(
                                os.path.dirname(_caminho))
                            _download.setDownloadFileName(
                                os.path.basename(_caminho))
                            _download.accept()
                            registrar_log(
                                f"[DOWNLOAD] Iniciado (QWebEngine): {_caminho}")
                        else:
                            try:
                                _download.cancel()
                            except Exception:
                                pass
                    except Exception as _e_dl:
                        registrar_log(f"[DOWNLOAD ERRO] {_e_dl}")

                _perfil_web.downloadRequested.connect(_ao_solicitar_download)
                registrar_log(
                    "[DESKTOP] Handler de download do QWebEngine instalado "
                    "(botão Baixar funcional).")
            except Exception as _e_dl_inst:
                registrar_log(
                    f"[DESKTOP AVISO] Não foi possível instalar handler de "
                    f"download: {_e_dl_inst}")

            # Lado direito: barra lateral fixa com os botoes customizados
            self.barra_lateral = BarraLateralEmulador(self)

            layout.addWidget(self.web, 1)          # motor web (expansivel)
            layout.addWidget(self.barra_lateral, 0)  # barra lateral fixa

            self.setCentralWidget(central)

            # Estado do arraste/redimensionamento global
            self._modo = None  # None | 'arrastar' | aresta de resize
            self._mouse_inicial = None
            self._geo_inicial = None

            # FILTRO DE EVENTOS GLOBAL: captura mouse mesmo fora da janela
            _QtW.QApplication.instance().installEventFilter(self)

            # Foco forçado do motor web: sem isto o QWebEngineView não entrega
            # os eventos de TECLADO (keydown) para a página do emulador.
            try:
                self.web.setFocusPolicy(_QtC.Qt.FocusPolicy.StrongFocus)
                self.web.loadFinished.connect(lambda _ok: self.web.setFocus())
            except Exception:
                pass

            self.web.load(_QtC.QUrl(url))
            # Dispositivo travado nesta janela (?dispositivo=...). Os botões nativos
            # laterais (Voltar/Home/Recentes) usam este alvo em _emitir_evento_celular.
            self._dispositivo = getattr(self, "_dispositivo", None)
            registrar_log("[EMULADOR NATIVO] Janela standalone com moldura + barra lateral direita aberta.")

        # ------------------------------------------------------------------
        # Botoes Voltar / Home -> emitem Socket.IO para o celular remoto
        # ------------------------------------------------------------------
        def _emitir_evento_celular(self, evento, payload=None):
            """Emite um evento Socket.IO para o(s) aparelho(s) selecionado(s)
            via loop asyncio do servidor (thread-safe a partir da GUI PyQt6)."""
            try:
                import asyncio as _aio_local
                # Prioridade do alvo: dispositivo TRAVADO nesta janela
                # (?dispositivo=...) > seleção global do painel > último frame.
                alvo_nome = (getattr(self, "_dispositivo", None)
                             or dispositivo_selecionado
                             or (ultimo_dispositivo.get("nome") if ultimo_dispositivo else None))
                alvos = _sids_alvo(alvo_nome)
                for fsid in alvos:
                    if loop_principal and loop_principal.is_running():
                        _aio_local.run_coroutine_threadsafe(
                            sio.emit(evento, payload or {}, to=fsid),
                            loop_principal,
                        )
                registrar_log(
                    f"[EMULADOR NATIVO] {evento} -> "
                    f"{alvo_nome or 'todos'} ({len(alvos)} socket(s))"
                )
            except Exception as e_btn:
                registrar_log(f"[EMULADOR NATIVO ERRO] emitir {evento}: {e_btn}")

        def enviar_botao_voltar(self):
            """Botao Voltar da barra lateral -> GLOBAL_ACTION_BACK no celular."""
            self._emitir_evento_celular("botao_voltar", {"dispositivo": getattr(self, "_dispositivo", None)})

        def enviar_botao_home(self):
            """Botao Home da barra lateral -> GLOBAL_ACTION_HOME no celular."""
            self._emitir_evento_celular("botao_home", {"dispositivo": getattr(self, "_dispositivo", None)})

        def enviar_botao_recentes(self):
            self._emitir_evento_celular("botao_recentes", {"dispositivo": getattr(self, "_dispositivo", None)})

        def enviar_botao_power(self):
            """Botao Power da barra lateral -> acende a tela do celular remoto."""
            self._emitir_evento_celular("botao_power", {"dispositivo": getattr(self, "_dispositivo", None)})

        # ------------------------------------------------------------------
        # Arraste + redimensionamento (eventFilter global + QCursor.pos())
        # ------------------------------------------------------------------
        def iniciar_arraste(self):
            self._modo = "arrastar"
            self._mouse_inicial = _QtG.QCursor.pos()
            self._geo_inicial = self.geometry()

        def iniciar_redimensionamento(self, aresta):
            self._modo = aresta
            self._mouse_inicial = _QtG.QCursor.pos()
            self._geo_inicial = self.geometry()

        def _borda_sob_cursor(self, pos_global):
            """Retorna a aresta (n/s/e/w/ne/nw/se/sw) sob o cursor global,
            ou None se o cursor nao estiver a menos de 6px de uma borda/canto.
            Detecao NATIVA (independe de HTML/QWebChannel)."""
            g = self.geometry()
            mx, my = pos_global.x(), pos_global.y()
            m = 6  # margem de sensibilidade em pixels
            no_n = abs(my - g.y()) <= m
            no_s = abs(my - (g.y() + g.height())) <= m
            no_e = abs(mx - (g.x() + g.width())) <= m
            no_w = abs(mx - g.x()) <= m
            if not (no_n or no_s or no_e or no_w):
                return None
            aresta = ""
            if no_n: aresta += "n"
            if no_s: aresta += "s"
            if no_e: aresta += "e"
            if no_w: aresta += "w"
            return aresta or None

        @staticmethod
        def _cursor_da_aresta(aresta):
            """Cursor de resize nativo do SO correspondente a aresta."""
            _mapa = {
                "n":  _QtC.Qt.CursorShape.SizeVerCursor,
                "s":  _QtC.Qt.CursorShape.SizeVerCursor,
                "e":  _QtC.Qt.CursorShape.SizeHorCursor,
                "w":  _QtC.Qt.CursorShape.SizeHorCursor,
                "ne": _QtC.Qt.CursorShape.SizeBDiagCursor,
                "sw": _QtC.Qt.CursorShape.SizeBDiagCursor,
                "nw": _QtC.Qt.CursorShape.SizeFDiagCursor,
                "se": _QtC.Qt.CursorShape.SizeFDiagCursor,
            }
            return _mapa.get(aresta, _QtC.Qt.CursorShape.ArrowCursor)

        def eventFilter(self, obj, evento):
            """FILTRO DE EVENTOS GLOBAL. Quando NAO esta arrastando/
            redimensionando, detecta bordas NATIVAMENTE (cursor de resize +
            iniciar resize no clique). Quando _modo esta ativo, captura TODO
            MouseMove do sistema e move/redimensiona via setGeometry.
            MouseButtonRelease encerra."""
            if self._modo is None:
                # --- Detecao nativa de bordas (independe de HTML handles) ---
                tipo = evento.type()
                if tipo == _QtC.QEvent.Type.MouseMove:
                    pos = _QtG.QCursor.pos()
                    if self.geometry().adjusted(-6, -6, 6, 6).contains(pos):
                        aresta = self._borda_sob_cursor(pos)
                        if aresta:
                            self.setCursor(self._cursor_da_aresta(aresta))
                        else:
                            self.unsetCursor()
                elif tipo == _QtC.QEvent.Type.MouseButtonPress:
                    pos = _QtG.QCursor.pos()
                    if self.geometry().adjusted(-6, -6, 6, 6).contains(pos):
                        aresta = self._borda_sob_cursor(pos)
                        if aresta:
                            self.iniciar_redimensionamento(aresta)
                            return True
                return super().eventFilter(obj, evento)
            if evento.type() == _QtC.QEvent.Type.MouseMove:
                delta = _QtG.QCursor.pos() - self._mouse_inicial
                g = self._geo_inicial
                x, y, w, h = g.x(), g.y(), g.width(), g.height()
                aresta = self._modo
                min_w, min_h = self.TAM_MIN
                if aresta == "arrastar":
                    self.move(x + delta.x(), y + delta.y())
                    return True
                if "e" in aresta:
                    w = max(min_w, w + delta.x())
                if "s" in aresta:
                    h = max(min_h, h + delta.y())
                if "w" in aresta:
                    nova_w = max(min_w, w - delta.x())
                    x = x + (w - nova_w)
                    w = nova_w
                if "n" in aresta:
                    nova_h = max(min_h, h - delta.y())
                    y = y + (h - nova_h)
                    h = nova_h
                self.setGeometry(x, y, w, h)
                return True
            if evento.type() == _QtC.QEvent.Type.MouseButtonRelease:
                self._modo = None
                return True
            return super().eventFilter(obj, evento)


except Exception as _e_emul:
    _PYQT6_EMULADOR = False
    try:
        registrar_log(f"[DESKTOP AVISO] Janela emulador flutuante indisponivel: {_e_emul}")
    except Exception:
        pass


_HTML_EMULADOR_FLUTUANTE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Emulador Flutuante</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; background: transparent; overflow: hidden; font-family: sans-serif; }
  #janela-emulador { position: fixed; inset: 0; background: #000; border: 2px solid #ef4444; border-radius: 12px; overflow: hidden; display: flex; flex-direction: column; }
  #barra-emulador { height: 32px; background: linear-gradient(90deg,#0d0d0d,#1a0505); border-bottom: 1px solid #7f1d1d; display: flex; align-items: center; padding: 0 10px; gap: 8px; cursor: move; user-select: none; -webkit-user-select: none; }
  #barra-emulador .titulo { flex: 1; color: #ef4444; font-size: 11px; font-weight: bold; letter-spacing: 1px; text-transform: uppercase; }
  #barra-emulador .btn-fechar { background: #dc2626; border: none; color: #fff; width: 26px; height: 18px; border-radius: 4px; cursor: pointer; font-size: 11px; font-weight: bold; }
  #corpo-emulador { flex: 1; position: relative; background: #050505; overflow: hidden; }
  /* object-fit: contain + object-position: center = imagem SEMPRE centralizada,
     em qualquer proporcao de tela/dispositivo. touch-action: none impede que
     o browser dispare gestos nativos (scroll/zoom) que viram cliques fantasmas. */
  #tela-emulador { position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: contain; object-position: center center; display: block; cursor: crosshair; user-select: none; -webkit-user-select: none; touch-action: none; -webkit-touch-callout: none; }
  #titulo-emulador { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  /* 8 handles de redimensionamento */
  .handle { position: absolute; z-index: 200; background: transparent; touch-action: none; }
  .handle.n  { top: 0; left: 12px; right: 12px; height: 7px; cursor: n-resize; }
  .handle.s  { bottom: 0; left: 12px; right: 12px; height: 7px; cursor: s-resize; }
  .handle.e  { right: 0; top: 12px; bottom: 12px; width: 7px; cursor: e-resize; }
  .handle.w  { left: 0; top: 12px; bottom: 12px; width: 7px; cursor: w-resize; }
  .handle.ne { top: 0; right: 0; width: 16px; height: 16px; cursor: ne-resize; }
  .handle.nw { top: 0; left: 0; width: 16px; height: 16px; cursor: nw-resize; }
  .handle.se { bottom: 0; right: 0; width: 16px; height: 16px; cursor: se-resize; }
  .handle.sw { bottom: 0; left: 0; width: 16px; height: 16px; cursor: sw-resize; }
  /* ================================================================
     MODO ATUALIZANDO (sala separada — só no emulador flutuante).
     O dispositivo físico CONTINUA com a tela preta da atualização.
     Aqui (janela flutuante) o vídeo é escondido, o fundo fica
     transparente (área de trabalho aparece por trás) e é mostrado
     apenas o indicador central "Atualizando aplicativo…".
     ================================================================ */
  body.modo-atualizando #janela-emulador { background: transparent !important; }
  body.modo-atualizando #corpo-emulador { background: transparent !important; }
  body.modo-atualizando #tela-emulador { visibility: hidden !important; }
  body.modo-atualizando #diag-emulador { display: none !important; }
  #atualizando-indicador { display: none; position: absolute; inset: 0; z-index: 40; flex-direction: column; align-items: center; justify-content: center; gap: 12px; pointer-events: none; background: transparent; }
  body.modo-atualizando #atualizando-indicador { display: flex; }
  #atualizando-indicador .spinner { width: 42px; height: 42px; border: 4px solid rgba(255,255,255,0.25); border-top-color: #22c55e; border-radius: 50%; animation: spin-atualiza 1s linear infinite; }
  #atualizando-indicador .texto { color: #ffffff; font-size: 14px; font-weight: bold; letter-spacing: 0.5px; text-shadow: 0 1px 4px rgba(0,0,0,0.85); background: rgba(0,0,0,0.45); padding: 8px 16px; border-radius: 20px; }
  /* Badge pequeno de atualização (modo normal): o vídeo CONTINUA visível
     (espelhamento claro por trás do overlay FLAG_SECURE) e só aparece um
     círculo de carregamento no canto superior direito. */
  #atualizando-badge { display: none; position: absolute; top: 8px; right: 8px; z-index: 45; flex-direction: row; align-items: center; gap: 8px; pointer-events: none; background: rgba(0,0,0,0.65); padding: 6px 12px; border-radius: 20px; }
  body.atualizando-ativo #atualizando-badge { display: flex; }
  #atualizando-badge .spinner-badge { width: 16px; height: 16px; border: 3px solid rgba(255,255,255,0.3); border-top-color: #22c55e; border-radius: 50%; animation: spin-atualiza 1s linear infinite; }
  #atualizando-badge span { color: #ffffff; font-size: 12px; font-weight: bold; letter-spacing: 0.3px; }
  @keyframes spin-atualiza { to { transform: rotate(360deg); } }
</style>
</head>
<body>
<div id="janela-emulador">
  <div id="barra-emulador" onmousedown="iniciarArrastoEmulador(event)">
    <span class="titulo" id="titulo-emulador">⚡ Emulador Livre Flutuante</span>
    <button class="btn-fechar" onclick="fecharEmulador()">✕</button>
  </div>
  <div id="corpo-emulador">
    <img id="tela-emulador" src="/visualizar-imagem" alt="tela" tabindex="0" style="outline:none;" draggable="false">
    <span id="diag-emulador" style="position:absolute; top:3px; left:3px; z-index:30; font-size:10px; font-weight:bold; color:#00ff00; background:#000; padding:2px 5px; border-radius:3px; font-family:monospace; pointer-events:none;">DIAG-INICIAL</span>
    <div id="atualizando-indicador">
      <div class="spinner"></div>
      <div class="texto">🔄 Atualizando aplicativo…</div>
    </div>
    <div id="atualizando-badge"><div class="spinner-badge"></div><span>Atualizando…</span></div>
  </div>
  <div class="handle n"  onmousedown="iniciarRedimEmulador(event,'n')"></div>
  <div class="handle s"  onmousedown="iniciarRedimEmulador(event,'s')"></div>
  <div class="handle e"  onmousedown="iniciarRedimEmulador(event,'e')"></div>
  <div class="handle w"  onmousedown="iniciarRedimEmulador(event,'w')"></div>
  <div class="handle ne" onmousedown="iniciarRedimEmulador(event,'ne')"></div>
  <div class="handle nw" onmousedown="iniciarRedimEmulador(event,'nw')"></div>
  <div class="handle se" onmousedown="iniciarRedimEmulador(event,'se')"></div>
  <div class="handle sw" onmousedown="iniciarRedimEmulador(event,'sw')"></div>
</div>
<script src="https://cdn.socket.io/4.7.5/socket.io.min.js"></script>
<script>
  var emuladorBridge = null;
  (function setup() {
    function init() {
      if (typeof QWebChannel === 'undefined') return;
      new QWebChannel(qt.webChannelTransport, function (canal) {
        emuladorBridge = canal.objects.emuladorBridge;
      });
    }
    if (typeof QWebChannel === 'undefined') {
      var s = document.createElement('script');
      s.src = 'qrc:///qtwebchannel/qwebchannel.js';
      s.onload = init; document.head.appendChild(s);
    } else { init(); }
  })();
  function iniciarArrastoEmulador(e) {
    if (e.target.tagName === 'BUTTON') return;
    if (emuladorBridge) { e.preventDefault(); emuladorBridge.iniciarArrasto(); }
  }
  function iniciarRedimEmulador(e, aresta) {
    if (emuladorBridge) { e.preventDefault(); e.stopPropagation(); emuladorBridge.iniciarRedimensionamento(aresta); }
  }
  function fecharEmulador() { if (emuladorBridge) emuladorBridge.fechar(); }

  // ===== ISOLAMENTO RÍGIDO POR DISPOSITIVO =====
  var DISPOSITIVO_FIXO = null;
  var dispositivoAlvo = null;
  var _timerResolv = null;
  var _lacrado = false;
  try {
    var _qp = new URLSearchParams(window.location.search);
    DISPOSITIVO_FIXO = _qp.get('dispositivo') || null;
  } catch (e) { DISPOSITIVO_FIXO = null; }
  if (DISPOSITIVO_FIXO) dispositivoAlvo = DISPOSITIVO_FIXO;

  function lacrar(nome) {
    if (!nome || _lacrado) return;
    _lacrado = true;
    DISPOSITIVO_FIXO = nome;
    dispositivoAlvo = nome;
    if (_timerResolv) { clearInterval(_timerResolv); _timerResolv = null; }
    var tit = document.getElementById('titulo-emulador');
    if (tit) tit.textContent = '🔒 ' + nome + ' (exclusivo)';
    var img = document.getElementById('tela-emulador');
    if (img) img.src = '/visualizar-imagem?t=' + Date.now() + '&dispositivo=' + encodeURIComponent(nome);
    emitSocket('assistir_video', { dispositivo: nome });
  }
  if (DISPOSITIVO_FIXO) lacrar(DISPOSITIVO_FIXO);

  function resolverAlvo() {
    if (DISPOSITIVO_FIXO) return;
    fetch('/lista-dispositivos').then(function(r){ return r.json(); }).then(function(data){
      var alvo = data.primeiro_conectado || null;
      if (!alvo) {
        var aps = data.aparelhos || [];
        var a = aps.find(function(x){ return x && x.conectado && x.ativo; })
             || aps.find(function(x){ return x && x.conectado; });
        alvo = (a && a.nome) || data.selecionado || null;
      }
      if (alvo) lacrar(alvo);
      else {
        var tit2 = document.getElementById('titulo-emulador');
        if (tit2) tit2.textContent = '⚡ Aguardando dispositivo...';
      }
    }).catch(function(){});
  }

  var ultimaObjectURL = null;
  var ultimoFrameBinarioTs = 0;
  var socket = null;
  function emitSocket(ev, data) { if (socket) { try { socket.emit(ev, data); } catch (err) {} } }
  function enviarComando(ev, payload) {
    if (!dispositivoAlvo) {
      console.warn('[ISOLAMENTO] Comando', ev, 'DESCARTADO: janela ainda sem dispositivo lacrado.');
      return false;
    }
    payload = payload || {};
    payload.dispositivo = dispositivoAlvo;
    emitSocket(ev, payload);
    return true;
  }

  // ================================================================
  // RENDER DE FRAMES COM PRÉ-CARGA (ZERO FLICKER / SEMPRE CENTRALIZADO)
  // ----------------------------------------------------------------
  // Antes de trocar o img.src, carregamos o frame num Image() oculto. Só
  // depois de 'onload' (frame totalmente decodificado) trocamos o src.
  // Com isto: (1) a imagem NUNCA fica vazia no meio da troca (sem tremer/
  // piscar), (2) naturalWidth/naturalHeight estão sempre válidos então
  // object-fit: contain + object-position:center mantêm o frame EXATAMENTE
  // centralizado em tempo real, (3) cliques usam sempre a proporção real.
  // ================================================================
  var imgTela = document.getElementById('tela-emulador');
  var _preloader = new Image();
  var _filaFrame = null;
  var _carregandoFrame = false;
  function _processarFilaFrame() {
    if (!_filaFrame) { _carregandoFrame = false; return; }
    var ab = _filaFrame; _filaFrame = null;
    var blob = new Blob([ab], { type: 'image/jpeg' });
    var url = URL.createObjectURL(blob);
    _preloader.onload = function() {
      var anterior = ultimaObjectURL;
      imgTela.src = url;
      ultimaObjectURL = url;
      if (anterior && anterior !== url) { try { URL.revokeObjectURL(anterior); } catch(e){} }
      _preloader.onload = null; _preloader.onerror = null;
      if (_filaFrame) { _processarFilaFrame(); } else { _carregandoFrame = false; }
    };
    _preloader.onerror = function() {
      try { URL.revokeObjectURL(url); } catch(e){}
      _preloader.onload = null; _preloader.onerror = null;
      if (_filaFrame) { _processarFilaFrame(); } else { _carregandoFrame = false; }
    };
    _preloader.src = url;
  }
  function renderFrameAB(arrayBuffer) {
    if (!arrayBuffer || arrayBuffer.byteLength === 0) return;
    ultimoFrameBinarioTs = Date.now();
    _filaFrame = arrayBuffer;   // só o frame MAIS RECENTE importa
    if (_carregandoFrame) return;
    _carregandoFrame = true;
    _processarFilaFrame();
  }

  // Fallback HTTP: só recarrega se NÃO houver stream binário vivo há 3s.
  // Enquanto o socket entrega frames, NUNCA mexe no img.src (anti-tremor).
  setInterval(function(){
    if (Date.now() - ultimoFrameBinarioTs < 3000) return;
    var img = imgTela;
    var src = img.src || '';
    if (src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0) {
      var url = '/visualizar-imagem?t=' + Date.now();
      if (dispositivoAlvo) url += '&dispositivo=' + encodeURIComponent(dispositivoAlvo);
      img.src = url;
    }
  }, 1500);
  resolverAlvo();
  _timerResolv = setInterval(resolverAlvo, 5000);

  // Watchdog de diagnóstico (badge verde). NÃO recarrega src por conta
  // própria — apenas exibe o estado; o fallback acima é o único que pode
  // recarregar, e só com stream parada há 3s (evita briga de src).
  setInterval(function(){
    try {
      var img = imgTela;
      var d = document.getElementById('diag-emulador');
      if (d && img) {
        var src = img.src || '';
        var tipo = src.indexOf('blob:') === 0 ? 'BLOB' : (src.indexOf('visualizar') >= 0 ? 'HTTP' : '?');
        d.textContent = 'nw=' + (img.naturalWidth || 0) + 'x' + (img.naturalHeight || 0) + ' ' + tipo + (img.complete ? ' OK' : ' LOAD');
        d.style.color = (img.naturalWidth > 0) ? '#00ff00' : '#ff4444';
      }
    } catch(e){}
  }, 500);

  // Stream de frames em tempo real.
  if (typeof io !== 'undefined') {
  socket = io(window.location.origin, { transports: ['websocket','polling'], binaryType: 'arraybuffer' });
  socket.on('connect', function(){
    emitSocket('identificar','painel');
    if (DISPOSITIVO_FIXO) emitSocket('assistir_video', { dispositivo: DISPOSITIVO_FIXO });
    resolverAlvo();
  });
  socket.on('aparelho_selecionado', function(d){
    if (DISPOSITIVO_FIXO) return;
    if (d && d.selecionado) { dispositivoAlvo = d.selecionado; var t2 = document.getElementById('titulo-emulador'); if (t2) t2.textContent = '⚡ ' + d.selecionado; }
  });
  socket.on('frame', function() {
    if (arguments.length === 0) return;
    var _m0 = arguments[0];
    var _isObj = (_m0 && typeof _m0 === 'object'
                  && !(_m0 instanceof ArrayBuffer)
                  && !(typeof Blob !== 'undefined' && _m0 instanceof Blob));
    var _nomeFrame = _isObj ? _m0.dispositivo : null;
    if (dispositivoAlvo && _nomeFrame && _nomeFrame !== dispositivoAlvo) return;
    if (!dispositivoAlvo && _nomeFrame) return;
    function extrairAB(arg) {
      if (!arg) return null;
      if (arg instanceof ArrayBuffer) return arg;
      if (arg.buffer && arg.buffer instanceof ArrayBuffer) return arg.buffer;
      return null;
    }
    for (var i = 0; i < arguments.length; i++) {
      var arg = arguments[i];
      var ab = extrairAB(arg);
      if (ab) { renderFrameAB(ab); return; }
      if (typeof Blob !== 'undefined' && arg instanceof Blob) {
        arg.arrayBuffer().then(renderFrameAB).catch(function(){});
        return;
      }
      if (typeof arg === 'string' && arg.length > 500) {
        try {
          var bin = atob(arg);
          var bytes = new Uint8Array(bin.length);
          for (var k = 0; k < bin.length; k++) bytes[k] = bin.charCodeAt(k);
          renderFrameAB(bytes.buffer);
        } catch (e2) {}
        return;
      }
    }
  });

  // ================================================================
  // MODO ATUALIZANDO (sala separada — só indicador, SEM tela preta).
  // O dispositivo físico recebe o blackout (loading_overlay) e fica
  // com tela preta durante a atualização. O emulador flutuante (sala
  // "painel") recebe apenas este evento e troca para fundo
  // transparente + "Atualizando aplicativo…". A MESMA função NÃO é
  // aplicada aos dois lados (servidor filtra emuladores no envio).
  // ================================================================
  // ================================================================
  // MODO ATUALIZANDO (sala separada — só indicador, SEM tela preta).
  // O dispositivo físico recebe o blackout (loading_overlay) e fica
  // com tela preta; o emulador flutuante (sala "painel") recebe apenas
  // este evento e troca para fundo transparente + indicador.
  // WATCHDOG ANTI-FREEZE: se os frames binários pararem por >4s (ex.:
  // captura do Nox trava durante o overlay), o indicador é ativado
  // automaticamente — NUNCA fica um frame congelado. Quando os frames
  // voltam, o vídeo restaura sozinho.
  // ================================================================
  var atualizacaoEventoAtiva = false;
  var stallDetectado = false;
  function _atualizarEstadoVisual() {
    try {
      // Stall (frames pararam >4s) → indicador grande transparente (nunca
      // fica frame congelado). Atualização ativa SEM stall → badge pequeno no
      // canto e o VÍDEO CONTINUA VISÍVEL (espelhamento claro atrás do overlay
      // FLAG_SECURE no dispositivo) + círculo de carregamento.
      document.body.classList.toggle('modo-atualizando', !!stallDetectado);
      document.body.classList.toggle('atualizando-ativo', !!atualizacaoEventoAtiva && !stallDetectado);
      var ind = document.getElementById('atualizando-indicador');
      if (ind) {
        ind.style.display = stallDetectado ? 'flex' : 'none';
        var txt = ind.querySelector('.texto');
        if (txt) {
          txt.textContent = atualizacaoEventoAtiva
            ? '🔄 Atualizando aplicativo…'
            : '📡 Aguardando sinal do dispositivo…';
        }
      }
      var badge = document.getElementById('atualizando-badge');
      if (badge) badge.style.display = (atualizacaoEventoAtiva && !stallDetectado) ? 'flex' : 'none';
    } catch (e) {}
  }
  function _aplicarModoAtualizando(ativo) {
    atualizacaoEventoAtiva = !!ativo;
    _atualizarEstadoVisual();
  }
  function _tratarEventoAtualizacao(data) {
    if (!data || typeof data.ativo === 'undefined') return;
    // Filtro por dispositivo (janela lacrada em ?dispositivo=...),
    // comparação tolerante (trim + minúsculas) para não falhar o gatilho.
    var dispEv = data.dispositivo ? String(data.dispositivo).trim().toLowerCase() : null;
    var alvo = dispositivoAlvo ? String(dispositivoAlvo).trim().toLowerCase() : null;
    var fixo = DISPOSITIVO_FIXO ? String(DISPOSITIVO_FIXO).trim().toLowerCase() : null;
    if (dispEv && alvo && dispEv !== alvo) return;
    if (dispEv && fixo && dispEv !== fixo) return;
    _aplicarModoAtualizando(!!data.ativo);
  }
  socket.on('loading_overlay', _tratarEventoAtualizacao);
  socket.on('atualizacao_status', _tratarEventoAtualizacao);
  // Watchdog anti-freeze: sem frame binário novo há >4s => indicador
  // automático (fundo transparente) em vez de frame congelado.
  setInterval(function(){
    try {
      var stall = (Date.now() - ultimoFrameBinarioTs) > 4000;
      if (stall !== stallDetectado) { stallDetectado = stall; _atualizarEstadoVisual(); }
    } catch (e) {}
  }, 1000);
  }

  // ================================================================
  // CLIQUES / TOQUES / ARRASTES — ANTI-GHOST TOTAL
  // ----------------------------------------------------------------
  // BUG ANTIGO (1): mousedown setava 'arrastando=true' MESMO quando o
  // clique era na faixa preta (letterbox) fora da imagem real. Como
  // enviarMov retornava cedo (coord null), nenhum START ia para o
  // dispositivo — mas 'arrastando' ficava true. Qualquer mouseup
  // posterior enviava END → o dispositivo dava um TAP em coordenadas
  // OBSOLETAS (startX/startY do gesto anterior). = CLIQUE FANTASMA.
  // CORREÇÃO: só inicia arraste se a coordenada for DENTRO da imagem
  // real (0..1). Se for letterbox, ignora o evento completamente.
  //
  // BUG ANTIGO (2): se 'arrastando' ficava preso (mouseup perdido),
  // todo movimento do mouse enviava MOVE → o dispositivo "mexia so-
  // zinho". CORREÇÃO: (a) Pointer Events com setPointerCapture (o
  // pointerup SEMPRE dispara, mesmo fora da janela); (b) checagem
  // CONSERVADORA de botão pressionado (se e.buttons/e.which forem
  // indefinidos, assume NÃO pressionado); (c) failsafe de 10s que
  // libera o arraste; (d) múltiplos listeners de liberação.
  // ================================================================
  function coordNormalizada(e) {
    var img = imgTela;
    var rect = img.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    var px = e.clientX - rect.left, py = e.clientY - rect.top;
    var iw = img.naturalWidth || 0, ih = img.naturalHeight || 0;
    var nx, ny;
    if (iw > 0 && ih > 0) {
      var fit = (window.getComputedStyle(img).objectFit) || 'contain';
      var scale = (fit === 'cover')
        ? Math.max(rect.width / iw, rect.height / ih)
        : Math.min(rect.width / iw, rect.height / ih);
      var dw = iw * scale, dh = ih * scale;
      var ox = (rect.width - dw) / 2, oy = (rect.height - dh) / 2;
      nx = (px - ox) / dw; ny = (py - oy) / dh;
    } else {
      // Frame ainda não carregado → não pode clicar (evita coordenada
      // errada que parece "descentralizada"). Aguarda o próximo frame.
      return null;
    }
    if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
    return { x: nx, y: ny };
  }

  var arrastando = false, ultimoMove = 0, _arrasteFailsafe = null;
  function liberarArrastre() {
    if (!arrastando) return;
    arrastando = false;
    if (_arrasteFailsafe) { clearTimeout(_arrasteFailsafe); _arrasteFailsafe = null; }
  }
  // Conservador: se não dá pra confirmar que o botão ESQUERDO está
  // pressionado, assume que NÃO está (nunca emite MOVE fantasma).
  function _botaoEsquerdoPressionado(e) {
    if (typeof e.buttons === 'number') return e.buttons === 1;
    if (typeof e.which === 'number') return e.which === 1;
    if (typeof e.pointerType === 'string' && e.pointerType !== 'mouse') return true;
    return false;
  }
  // Liberação em QUALQUER cenário de perda de foco/arraste.
  window.addEventListener('blur', liberarArrastre);
  window.addEventListener('pointercancel', liberarArrastre);
  document.addEventListener('mouseleave', liberarArrastre);
  document.addEventListener('visibilitychange', function(){ if (document.hidden) liberarArrastre(); });

  imgTela.addEventListener('pointerdown', function(e){
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    var c = coordNormalizada(e);
    if (!c) return;   // clique fora da imagem real (letterbox) → IGNORA TOTALMENTE
    e.preventDefault(); e.stopPropagation();
    try { imgTela.setPointerCapture(e.pointerId); } catch(err){}
    try { imgTela.focus({ preventScroll: true }); } catch(err) { try { imgTela.focus(); } catch(err2){} }
    arrastando = true;
    ultimoMove = 0;
    enviarComando('clique_painel', { acao: 'START', x: c.x, y: c.y });
    // Failsafe: se nenhum pointerup chegar em 10s, libera o arraste.
    if (_arrasteFailsafe) clearTimeout(_arrasteFailsafe);
    _arrasteFailsafe = setTimeout(liberarArrastre, 10000);
  });
  window.addEventListener('pointermove', function(e){
    if (!arrastando) return;
    if (!_botaoEsquerdoPressionado(e)) { liberarArrastre(); return; }
    var agora = Date.now();
    if (agora - ultimoMove < 45) return;
    ultimoMove = agora;
    var c = coordNormalizada(e);
    if (!c) return;
    enviarComando('clique_painel', { acao: 'MOVE', x: c.x, y: c.y });
  });
  window.addEventListener('pointerup', function(e){
    if (!arrastando) return;
    liberarArrastre();
    var c = coordNormalizada(e);
    if (!c) return;   // soltou fora da imagem real → só libera, sem tap
    enviarComando('clique_painel', { acao: 'END', x: c.x, y: c.y });
  });
  // Drag nativo do <img> bloqueado (evita cópia/arraste de arquivo).
  imgTela.addEventListener('dragstart', function(e){ e.preventDefault(); });
  // Fallback touch: em engines sem Pointer Events, garante que toques
  // também respeitem o anti-ghost (só inicia se coordenada válida).
  imgTela.addEventListener('touchstart', function(e){
    e.preventDefault();
    var c = coordNormalizada(e.changedTouches[0]);
    if (!c) return;
    arrastando = true;
    enviarComando('clique_painel', { acao: 'START', x: c.x, y: c.y });
    if (_arrasteFailsafe) clearTimeout(_arrasteFailsafe);
    _arrasteFailsafe = setTimeout(liberarArrastre, 10000);
  }, { passive: false });
  imgTela.addEventListener('touchmove', function(e){
    if (!arrastando) return;
    e.preventDefault();
    var c = coordNormalizada(e.changedTouches[0]);
    if (!c) return;
    enviarComando('clique_painel', { acao: 'MOVE', x: c.x, y: c.y });
  }, { passive: false });
  imgTela.addEventListener('touchend', function(e){
    if (!arrastando) return;
    liberarArrastre();
    var c = coordNormalizada(e.changedTouches[0] || e);
    if (!c) return;
    enviarComando('clique_painel', { acao: 'END', x: c.x, y: c.y });
  }, { passive: false });
  imgTela.addEventListener('touchcancel', liberarArrastre);

  // ===== Teclado remoto → 'tecla_painel' =====
  document.addEventListener('keydown', function(e){
    if (e.repeat) return;
    var pacote = null;
    switch (e.key) {
      case 'Backspace': pacote = { tipo: 'tecla', valor: 'BACKSPACE' }; break;
      case 'Enter':     pacote = { tipo: 'tecla', valor: 'ENTER' }; break;
      case 'Tab':       pacote = { tipo: 'tecla', valor: 'TAB' }; break;
      case ' ':         pacote = { tipo: 'tecla', valor: 'ESPACO' }; break;
      default:
        if (e.key && e.key.length === 1) pacote = { tipo: 'texto', valor: e.key };
    }
    if (pacote) {
      e.preventDefault();
      enviarComando('tecla_painel', pacote);
    }
  });
</script>
</body>
</html>"""
@app.get("/emulador-flutuante", response_class=HTMLResponse)
def emulador_flutuante_page(dispositivo: str = None):
    """Pagina minima servida dentro da janela nativa standalone do emulador
    flutuante (arrastavel pela barra + redimensionavel pelas 8 bordas).
    Aceita ?dispositivo=NOME — o HTML/JS lê o parâmetro da própria URL e isola
    frames e comandos naquele aparelho (filtro client-side; broadcast do servidor
    continua intacto, sem derrubar socket de nenhum outro dispositivo)."""
    return _HTML_EMULADOR_FLUTUANTE


# ---------------------------------------------------------------------------
# Rota de consulta das gravações: estado ao vivo + histórico de metadados.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# Hardening das rotas de auditoria: se a variável de ambiente AUDITORIA_CHAVE
# estiver definida, /gravacoes e /gravacoes/exportar/{dispositivo} exigem a
# chave via query ?chave=... ou header X-Auditoria-Chave. Se não definida,
# ficam abertas (backward-compatible com o painel atual).
# ---------------------------------------------------------------------------
from fastapi import (
    Request as _Request,
    HTTPException as _HTTPException,
    BackgroundTasks as _BackgroundTasks,
)
from fastapi.responses import FileResponse as _FileResponse

_CHAVE_AUDITORIA = os.environ.get("AUDITORIA_CHAVE", "").strip()


def _auditoria_autorizada(request):
    if not _CHAVE_AUDITORIA:
        return True  # não configurado → rota aberta (compatibilidade)
    fornecida = request.query_params.get("chave", "") or request.headers.get(
        "X-Auditoria-Chave", "")
    return fornecida == _CHAVE_AUDITORIA


def _remover_se_existir(caminho):
    try:
        if os.path.exists(caminho):
            os.remove(caminho)
    except Exception:
        pass


# >>> ROTAS DE GRAVAÇÃO MOVIDAS AO TOPO DO MÓDULO (bloco "REGISTRO GARANTIDO",
#     logo após o import do gravacao) para eliminar HTTP 404 por registro
#     tardio. Os decoradores abaixo foram comentados de propósito.
# @app.get("/gravacoes")
def listar_gravacoes_desativada(request: _Request):
    if not _auditoria_autorizada(request):
        raise _HTTPException(
            status_code=403,
            detail="Chave de auditoria inválida (?chave= ou header X-Auditoria-Chave)")
    return {
        "disponivel": GRAVACAO_DISPONIVEL,
        "ativas": estado_gravacao(),
        "historico": listar_historico_gravacoes(),
    }


# @app.get("/gravacoes/exportar/{dispositivo}")
async def exportar_gravacoes_desativada(dispositivo: str, request: _Request,
                             background_tasks: _BackgroundTasks):
    """Compila em .zip: histórico de sessões, vídeos e logs de input
    (teclado/cliques/gestos) do dispositivo — pacote completo de auditoria."""
    if not _auditoria_autorizada(request):
        raise _HTTPException(
            status_code=403,
            detail="Chave de auditoria inválida (?chave= ou header X-Auditoria-Chave)")
    if not GRAVACAO_DISPONIVEL:
        raise _HTTPException(status_code=503,
                             detail="Módulo de gravação indisponível (OpenCV ausente)")
    try:
        caminho_zip = exportar_auditoria(dispositivo)
    except Exception as _e_exp:
        registrar_log(f"[EXPORT ERRO] {_e_exp}")
        raise _HTTPException(status_code=500,
                             detail=f"Falha ao gerar ZIP: {_e_exp}")
    if not caminho_zip or not os.path.exists(caminho_zip):
        raise _HTTPException(status_code=500, detail="ZIP não foi gerado")
    registrar_log(f"[EXPORT] ZIP de auditoria gerado para '{dispositivo}': {caminho_zip}")
    background_tasks.add_task(_remover_se_existir, caminho_zip)
    _nome_zip = f"auditoria_{slug_dispositivo(dispositivo)}_{int(time.time())}.zip"
    return _FileResponse(
        caminho_zip, media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{_nome_zip}"'})


# Aplicação ASGI combinada: Socket.IO + FastAPI (servida pelo uvicorn).
asgi_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    try:
        multiprocessing.freeze_support()

        if ("--modo-fps" in sys.argv) or (os.environ.get("SOU_EXEC_FPS") == "1"):
            try:
                _fps_dir = os.environ.get("FPS_DATA_DIR")
                if not _fps_dir:
                    _fps_dir = os.path.join(os.path.dirname(sys.executable), "FPS") \
                        if getattr(sys, "frozen", False) \
                        else os.path.abspath(os.path.join(_DIRETORIO_SERVIDOR, "..", "FPS"))
                os.makedirs(_fps_dir, exist_ok=True)
                os.environ["FPS_DATA_DIR"] = _fps_dir
                try:
                    os.chdir(_fps_dir)
                except Exception:
                    pass
                from leitor.main import main as _fps_main
                _fps_main()
            except Exception as _e_fps:
                try:
                    import traceback as _tb
                    _log_fps = os.path.join(
                        os.environ.get("FPS_DATA_DIR") or os.path.dirname(sys.executable),
                        "crash_log.txt")
                    with open(_log_fps, "a", encoding="utf-8") as _f:
                        _f.write(f"\n--- FPS CRASH (modo-fps) ---\n{_tb.format_exc()}")
                except Exception:
                    pass
            sys.exit(0)

        # Verificação de Licença
        from licenca_cliente import verificar_licenca_ou_sair
        if not verificar_licenca_ou_sair():
            sys.exit(0)

        _modo_sem_janela = ("--sem-janela" in sys.argv) or (os.environ.get("ACESSO_MATERNO_SEM_JANELA") == "1")
        PORTA_SERVIDOR = _escolher_porta(27105)
        PORTA_REAL = PORTA_SERVIDOR

        def rodar_servidor():
            import uvicorn
            try:
                registrar_log(f"[INICIO] Subindo Uvicorn em http://127.0.0.1:{PORTA_SERVIDOR} (thread background)...")
                uvicorn.run(asgi_app, host="127.0.0.1", port=PORTA_SERVIDOR, log_level="info", log_config=None)
            except Exception as e:
                print(f"[SERVIDOR ERRO] Uvicorn falhou: {e}", flush=True)

        threading.Thread(target=rodar_servidor, daemon=True).start()

        if not _modo_sem_janela and _PYQT6_DISPONIVEL:
            try:
                app_qt = QtWidgets.QApplication(sys.argv)
                janela = JanelaDesktop(f"http://127.0.0.1:{PORTA_SERVIDOR}/")
                janela.show()
                sys.exit(app_qt.exec())
            except Exception as _e_iface:
                registrar_log(f"[INTERFACE AVISO] PyQt6 falhou ({_e_iface}); abrindo navegador padrão.")
                import webbrowser
                webbrowser.open(f"http://127.0.0.1:{PORTA_SERVIDOR}/")
                import time
                while True:
                    time.sleep(3600)
        else:
            import time
            registrar_log("[INICIO] Modo --sem-janela ativo ou PyQt6 indisponível: servidor rodando sem interface local.")
            while True:
                time.sleep(3600)

    except Exception as _e_startup:
        import traceback
        err_msg = traceback.format_exc()
        print(f"\n\n[ERRO CRITICO DE INICIALIZACAO]:\n{err_msg}", flush=True)
        try:
            _err_dir = os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else os.getcwd()
            _err_path = os.path.join(_err_dir, "startup_crash.txt")
            with open(_err_path, "w", encoding="utf-8") as _f:
                _f.write(err_msg)
            print(f"Detalhes do erro salvos em: {_err_path}", flush=True)
        except Exception:
            pass
        try:
            input("\nPressione ENTER para fechar a janela...")
        except Exception:
            pass
        sys.exit(1)
