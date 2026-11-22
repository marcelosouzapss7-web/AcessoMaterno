# -*- coding: utf-8 -*-
"""
gravacao.py — Módulo modular de gravação de vídeo, auditoria síncrona e metadados.

Integra-se ao server.py (FastAPI + Socket.IO) através de:
  - gravadores_ativos            : dict nome_dispositivo -> GravadorVideo
  - GravadorVideo.enfileirar(jpeg): chamado no handler 'enviar_tela'
  - iniciar_gravacao() / finalizar_gravacao(): chamados no handler 'gravacao_painel'
  - finalizar_todas_gravacoes()   : chamado no shutdown / disconnect geral

Requisitos: opencv-python (cv2) e numpy. Se ausentes, o módulo sobe em modo
"indisponível" e o server.py continua funcionando normalmente (sem gravação).

Estrutura de pastas (ao lado do executável/script, padrão do projeto):
  gravacoes/
    videos/<dispositivo>_<ts>_<sessao>.avi
    historico_gravacoes.json   (lista de metadados das sessões — append atômico)
    auditoria.log              (log estruturado linha-a-linha, flush+fsync)
"""
import os
import sys
import json
import time
import queue
import threading
import datetime
import uuid
import io

# ---------------------------------------------------------------------------
# Dependências pesadas (OpenCV / numpy) com degradação graciosa.
# ---------------------------------------------------------------------------
try:
    import cv2
    import numpy as np
except Exception as _e_import:
    cv2 = None
    np = None
    ERRO_IMPORT = (
        f"OpenCV/numpy ausentes: {_e_import}. "
        "Instale com: pip install opencv-python numpy"
    )
else:
    ERRO_IMPORT = None


# ---------------------------------------------------------------------------
# Caminhos (compatível com PyInstaller frozen e com execução normal).
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# FIXACAO ABSOLUTA DA PASTA DE GRAVACAO (producao): caminho fixo e permanente
# FORA da pasta dist\ (que o PyInstaller limpa/recria a cada build). Assim os
# videos .avi, logs de cliques e auditoria NUNCA sao apagados ao recompilar.
# Se o caminho fixo nao puder ser criado (ex.: rodando em Linux/Mac ou em outro
# usuario), cai de volta para uma pasta 'gravacoes' ao lado do script/.exe.
# ---------------------------------------------------------------------------
_PASTA_GRAVACAO_FIXA = r"C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master\gravacoes"
try:
    if os.name == "nt":
        PASTA_GRAVACOES = _PASTA_GRAVACAO_FIXA
    else:
        raise RuntimeError("SO nao Windows — usa fallback de projeto")
    os.makedirs(PASTA_GRAVACOES, exist_ok=True)
    # teste de escrita real dentro da pasta fixa
    _teste_w = os.path.join(PASTA_GRAVACOES, ".write_test")
    open(_teste_w, "w").close()
    os.remove(_teste_w)
except Exception:
    try:
        _raiz_projeto = (os.path.dirname(sys.executable)
                         if getattr(sys, "frozen", False)
                         else os.path.dirname(os.path.abspath(__file__)))
    except Exception:
        _raiz_projeto = os.getcwd()
    PASTA_GRAVACOES = os.path.join(_raiz_projeto, "gravacoes")
    os.makedirs(PASTA_GRAVACOES, exist_ok=True)

PASTA_BASE = os.path.dirname(PASTA_GRAVACOES)
PASTA_VIDEOS = os.path.join(PASTA_GRAVACOES, "videos")
os.makedirs(PASTA_VIDEOS, exist_ok=True)
ARQUIVO_HISTORICO = os.path.join(PASTA_GRAVACOES, "historico_gravacoes.json")
ARQUIVO_AUDITORIA = os.path.join(PASTA_GRAVACOES, "auditoria.log")
# Trilha separada e durável para eventos de input (teclado/cliques/gestos).
ARQUIVO_AUDITORIA_INPUTS = os.path.join(PASTA_GRAVACOES, "auditoria_inputs.jsonl")
_trava_inputs = threading.Lock()
os.makedirs(PASTA_VIDEOS, exist_ok=True)


# ---------------------------------------------------------------------------
# GRAVAÇÃO DUPLA: além de gravacoes/videos (raiz), copia cada vídeo final para
# a pasta de vídeos do EMULADOR FPS (FPS/videos). Resolve o caminho do FPS do
# mesmo jeito que o server.py (_pasta_fps_dados): env FPS_DATA_DIR, depois
# .exe (ao lado do executável), depois o ..\FPS do projeto.
# ---------------------------------------------------------------------------
def _resolver_pasta_videos_fps():
    candidatos = []
    _env = os.environ.get("FPS_DATA_DIR")
    if _env:
        candidatos.append(os.path.join(_env, "videos"))
    if getattr(sys, "frozen", False):
        candidatos.append(os.path.join(os.path.dirname(sys.executable), "FPS", "videos"))
    # PASTA_BASE = raiz do projeto (dirname de gravacoes). O FPS fica em ..\FPS.
    candidatos.append(os.path.abspath(os.path.join(PASTA_BASE, "..", "FPS", "videos")))
    candidatos.append(os.path.join(PASTA_BASE, "FPS", "videos"))
    for c in candidatos:
        try:
            pasta_fps = os.path.dirname(c)  # a pasta FPS em si
            if _env or os.path.isdir(pasta_fps):
                os.makedirs(c, exist_ok=True)
                return c
        except Exception:
            continue
    # Último recurso: cria ..\FPS\videos mesmo que ainda não exista.
    try:
        c = os.path.abspath(os.path.join(PASTA_BASE, "..", "FPS", "videos"))
        os.makedirs(c, exist_ok=True)
        return c
    except Exception:
        return None


PASTA_VIDEOS_FPS = _resolver_pasta_videos_fps()


def _copiar_para_fps(caminho_video):
    """Copia o vídeo (ou a pasta de frames do fallback) também para FPS/videos.
    Nunca levanta exceção — falha de cópia não pode quebrar a finalização."""
    if not PASTA_VIDEOS_FPS or not caminho_video:
        return
    try:
        import shutil
        if os.path.isfile(caminho_video):
            os.makedirs(PASTA_VIDEOS_FPS, exist_ok=True)
            destino = os.path.join(PASTA_VIDEOS_FPS, os.path.basename(caminho_video))
            if os.path.abspath(destino) != os.path.abspath(caminho_video):
                shutil.copy2(caminho_video, destino)
        elif os.path.isdir(caminho_video):
            destino = os.path.join(PASTA_VIDEOS_FPS, os.path.basename(caminho_video))
            if os.path.abspath(destino) != os.path.abspath(caminho_video):
                shutil.copytree(caminho_video, destino, dirs_exist_ok=True)
    except Exception as e:
        try:
            registrar_auditoria("copia_fps_erro", caminho=caminho_video, erro=str(e))
        except Exception:
            pass


# ---------------------------------------------------------------------------
# PORTÃO POR MOVIMENTO: a gravação só começa quando há movimento no dispositivo
# (gesto remoto OU mudança visual na tela) e só para quando a tela apaga E não
# há mais movimento por GRACE_PARAR_SEM_MOVIMENTO_S. Evita vídeos parados
# ocupando memória. A detecção usa frame-diff (cv2) em miniatura 64x64; sem
# cv2, usa um proxy por hash de amostra do JPEG (sem detectar tela apagada).
# ---------------------------------------------------------------------------
LIMIAR_MOVIMENTO = 1.0          # média de |diff| (0-255) na miniatura: acima = movimento
                                # (reduzido de 2.2 para 1.0 — captura mudanças sutis
                                # na tela; ver LIMIAR_MOVIMENTO_ACESSIBILIDADE p/ o
                                # modo ainda mais sensível quando há acessibilidade).
LIMIAR_MOVIMENTO_ACESSIBILIDADE = 0.8  # limiar aplicado quando houve atividade de
                                # acessibilidade recente (ver evento_acessibilidade
                                # no server.py) — pega micro-mudanças da automação.
LIMIAR_TELA_ESCURA = 14.0       # brilho médio (0-255): abaixo = tela apagada/preta
GRACE_PARAR_SEM_MOVIMENTO_S = 4.0  # segundos parado (e tela apagada) antes de parar
# Trava anti-memória: mesmo com a tela ACESA, se ficar totalmente parado (sem
# gesto nem mudança visual) por este tempo, para a gravação — evita o keep-alive
# (reenvio do último frame a cada ~150ms) gravar minutos de tela estática.
MAX_PARADO_TELA_ACESA_S = 30.0
_mov_estado = {}                # dispositivo -> {"prev"/"hash", "ultimo_mov_ts"}
_trava_mov = threading.Lock()


def marcar_gesto(dispositivo):
    """Marca 'houve movimento AGORA' por um gesto remoto (clique/arraste/tecla).
    Chamado pelo server.py quando um gesto é repassado ao dispositivo, para que
    a interação do operador conte como movimento mesmo sem mudança visual."""
    if not dispositivo:
        return
    with _trava_mov:
        st = _mov_estado.get(dispositivo) or {}
        st["ultimo_mov_ts"] = time.time()
        _mov_estado[dispositivo] = st


def tempo_desde_movimento(dispositivo):
    """Segundos desde o último movimento (gesto ou visual). Grande se nunca houve."""
    with _trava_mov:
        st = _mov_estado.get(dispositivo)
        if not st:
            return 1e9
        return time.time() - st.get("ultimo_mov_ts", 0.0)


def avaliar_frame(dispositivo, jpeg_bytes, limiar=None):
    """Compara o frame atual com o anterior deste dispositivo.
    Retorna (tem_movimento: bool, tela_escura: bool). Atualiza ultimo_mov_ts
    quando detecta movimento visual.

    limiar: sensibilidade de |diff| para considerar movimento. Se None usa
    LIMIAR_MOVIMENTO; o server.py passa LIMIAR_MOVIMENTO_ACESSIBILIDADE (mais
    baixo) quando houve atividade de acessibilidade recente no dispositivo."""
    _limiar = LIMIAR_MOVIMENTO if limiar is None else float(limiar)
    agora = time.time()
    if cv2 is not None and np is not None:
        try:
            arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
            img = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
            if img is None:
                return (False, False)
            peq = cv2.resize(img, (64, 64), interpolation=cv2.INTER_AREA)
            tela_escura = float(peq.mean()) < LIMIAR_TELA_ESCURA
            with _trava_mov:
                st = _mov_estado.get(dispositivo) or {}
                prev = st.get("prev")
                tem_mov = False
                if prev is not None:
                    if float(cv2.absdiff(prev, peq).mean()) >= _limiar:
                        tem_mov = True
                st["prev"] = peq
                if tem_mov:
                    st["ultimo_mov_ts"] = agora
                st.setdefault("ultimo_mov_ts", 0.0)
                _mov_estado[dispositivo] = st
            return (tem_mov, tela_escura)
        except Exception:
            return (False, False)
    else:
        # Sem cv2: proxy por hash de amostra do JPEG (detecta mudança de conteúdo).
        try:
            import hashlib
            h = hashlib.md5(bytes(jpeg_bytes[::97])).hexdigest()
            with _trava_mov:
                st = _mov_estado.get(dispositivo) or {}
                tem_mov = (st.get("hash") is not None and h != st.get("hash"))
                st["hash"] = h
                if tem_mov:
                    st["ultimo_mov_ts"] = agora
                st.setdefault("ultimo_mov_ts", 0.0)
                _mov_estado[dispositivo] = st
            return (tem_mov, False)  # sem cv2 não há detecção confiável de tela apagada
        except Exception:
            return (True, False)


def marcar_evento_acessibilidade(dispositivo):
    """Marca que o dispositivo ACABOU de usar acessibilidade (Inject/gesto do
    serviço). Conta como movimento imediato (para disparar a gravação) E grava
    um timestamp separado 'ultimo_acess_ts' usado para MANTER a gravação viva
    por JANELA_ACESSIBILIDADE_S mesmo que a tela pareça estática."""
    if not dispositivo:
        return
    agora = time.time()
    with _trava_mov:
        st = _mov_estado.get(dispositivo) or {}
        st["ultimo_mov_ts"] = agora       # acessibilidade conta como movimento
        st["ultimo_acess_ts"] = agora     # trilha própria p/ a janela anti-corte
        _mov_estado[dispositivo] = st


def tempo_desde_acessibilidade(dispositivo):
    """Segundos desde o último evento de acessibilidade. Grande se nunca houve."""
    with _trava_mov:
        st = _mov_estado.get(dispositivo)
        if not st:
            return 1e9
        _ts = st.get("ultimo_acess_ts")
        if not _ts:
            return 1e9
        return time.time() - _ts


def limpar_estado_movimento(dispositivo):
    """Esquece o estado de movimento do dispositivo (ao desconectar/encerrar)."""
    with _trava_mov:
        _mov_estado.pop(dispositivo, None)

# ---------------------------------------------------------------------------
# DIAGNÓSTICO DE INICIALIZAÇÃO (hardening): torna falhas VISÍVEIS no console.
# A queda silenciosa do OpenCV (muito comum em PyInstaller/Windows por DLLs
# ausentes — opencv_videoio_ffmpeg*.dll etc.) fazia o servidor rodar normal
# sem gravar NADA e sem erros aparentes. Agora imprime banner em stderr.
# ---------------------------------------------------------------------------
if cv2 is None:
    _BANNER = (
        "\n" + "=" * 72 + "\n"
        "[GRAVACAO] ATENCAO CRITICA: OpenCV/numpy NAO carregou.\n"
        f"[GRAVACAO] Motivo: {ERRO_IMPORT}\n"
        "[GRAVACAO] -> Sem .avi, sem historico JSON, sem auditoria de video.\n"
        "[GRAVACAO] -> No PyInstaller (Windows): empacote as DLLs do cv2:\n"
        "[GRAVACAO]    pyinstaller --collect-binaries opencv-python ...\n"
        "[GRAVACAO] -> Ou instale no ambiente de build: pip install opencv-python numpy\n"
        + "=" * 72
    )
    try:
        import sys as _sys_diag
        print(_BANNER, file=_sys_diag.stderr, flush=True)
    except Exception:
        pass

# Verifica escrita no diretório de saída (absoluto) e mostra o caminho exato,
# para eliminar o problema de "salvando em pasta temporária invisível".
ESCRITA_OK = False
try:
    _arq_teste = os.path.join(PASTA_GRAVACOES, ".write_test.tmp")
    with open(_arq_teste, "w", encoding="utf-8") as _f:
        _f.write("ok")
    os.remove(_arq_teste)
    ESCRITA_OK = True
except Exception as _e_write:
    ERRO_IMPORT = (ERRO_IMPORT or "") + f" | FALHA DE ESCRITA em {PASTA_GRAVACOES}: {_e_write}"
    try:
        import sys as _sys_w
        print(f"[GRAVACAO] FALHA DE ESCRITA em {PASTA_GRAVACOES}: {_e_write}",
              file=_sys_w.stderr, flush=True)
    except Exception:
        pass
try:
    print(f"[GRAVACAO] Pasta de saida (absoluta): {PASTA_GRAVACOES} "
          f"| escrita_ok={ESCRITA_OK} | opencv_ok={cv2 is not None}", flush=True)
except Exception:
    pass


# Diagnóstico em ARQUIVO (não depende de console — essencial para .exe
# PyInstaller --windowed, onde stderr/stdout são invisíveis). O usuário abre
# gravacoes/diagnostico.log e vê exatamente por que não grava.
def _escrever_diagnostico():
    try:
        _caminho = os.path.join(PASTA_GRAVACOES, "diagnostico.log")
        with open(_caminho, "a", encoding="utf-8") as _f:
            _f.write(
                f"\n===== {datetime.datetime.now().astimezone().isoformat(timespec='seconds')} =====\n"
                f"opencv_ok={cv2 is not None}\n"
                f"numpy_ok={np is not None}\n"
                f"erro_import={ERRO_IMPORT}\n"
                f"pasta_gravacoes={PASTA_GRAVACOES}\n"
                f"pasta_videos={PASTA_VIDEOS}\n"
                f"escrita_ok={ESCRITA_OK}\n"
                f"modo_gravador={'OpenCV VideoWriter (.avi)' if cv2 is not None else 'FALLBACK: sequencia de JPEG (sem OpenCV)'}\n"
                f"frozen={getattr(sys, 'frozen', False)} sys.executable={sys.executable}\n"
                f"sys.argv[0]={sys.argv[0] if sys.argv else '?'}\n"
            )
            _f.flush()
            os.fsync(_f.fileno())
    except Exception:
        pass


_escrever_diagnostico()

# Marcação VISÍVEL de que o módulo carregou (o usuário abre este arquivo em
# gravacoes\_modulo_carregado.txt e confirma imediatamente se o build atual
# incluiu o gravacao.py). Se este arquivo NÃO aparecer após iniciar o .exe,
# o executável está desatualizado — recompilar com PyInstaller.
try:
    with open(os.path.join(PASTA_GRAVACOES, "_modulo_carregado.txt"), "w",
              encoding="utf-8") as _f_marc:
        _f_marc.write(
            f"modulo=gravacao.py\n"
            f"carregado_em={datetime.datetime.now().astimezone().isoformat(timespec='seconds')}\n"
            f"opencv_ok={cv2 is not None}\n"
            f"pasta_base={PASTA_BASE}\n"
            f"pasta_gravacoes={PASTA_GRAVACOES}\n"
            f"pasta_videos={PASTA_VIDEOS}\n"
            f"max_fila={MAX_FILA}\n"
            f"codec={CODEC}\n"
            f"frozen={getattr(sys, 'frozen', False)}\n"
            f"executable={sys.executable}\n"
        )
except Exception:
    pass

# Codec/container. MJPG + .avi é o mais portável: não depende de ffmpeg embutido
# no build do OpenCV (importante em PyInstaller/Windows). Para .mp4 troque por
# CODEC="mp4v" e EXTENSAO=".mp4" (requer suporte a MPEG-4 no OpenCV).
CODEC = "MJPG"
EXTENSAO = ".avi"
FPS_ALVO = 15.0          # cadência de escrita; frames chegam em rajada e são
                         # serializados pela fila — o relógio do vídeo é o do
                         # VideoWriter (1/fps por frame), não o da chegada.
MAX_FILA = 180           # ~12s a 15fps mantidos em RAM (reduzido de 1000 para
                         # 180 — PROTEÇÃO DE RAM em máquina de 8GB: se o SSD
                         # engasgar, a fila por dispositivo não acumula centenas
                         # de MB. Pior caso 7 disp × 180 × ~60KB ≈ 75MB, folgado.
                         # Quando cheia, aplica backpressure (bloqueia o
                         # produtor) — NUNCA descarta frames (ver enfileirar()).
MAX_HISTORICO_ENTRADAS = 5000  # tampa o histórico para o JSON não crescer sem fim.


# ---------------------------------------------------------------------------
# Auditoria síncrona (hardening): escrita durável em disco.
# Cada entrada: abre o arquivo, escreve linha JSON, flush(), fsync(), fecha.
# Assim, mesmo em crash/desligamento abrupto, o último log está em disco.
# ---------------------------------------------------------------------------
_trava_auditoria = threading.Lock()


def registrar_auditoria(acao, **campos):
    """Registra uma ação de auditoria de forma síncrona e durável.

    Formato da linha (JSONL): {"ts": ISO8601 com tz, "acao": str, ...campos}.
    Nunca levanta exceção — falha de log não pode quebrar a operação principal.
    """
    linha = {
        "ts": datetime.datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "acao": str(acao),
    }
    linha.update(campos)
    try:
        texto = json.dumps(linha, ensure_ascii=False, default=str) + "\n"
    except Exception as _e:
        texto = json.dumps({"ts": linha["ts"], "acao": acao,
                            "erro_serializacao": str(_e)}, ensure_ascii=False) + "\n"
    with _trava_auditoria:
        try:
            with open(ARQUIVO_AUDITORIA, "a", encoding="utf-8") as f:
                f.write(texto)
                f.flush()
                os.fsync(f.fileno())
        except Exception as e:
            try:
                print(f"[AUDITORIA FALHA] {e} :: {texto.rstrip()}", flush=True)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# Auditoria de INPUTS (teclado/cliques/gestos): JSONL durável e síncrono.
# Cada caractere, clique ou gesto recebido do dispositivo é escrito com
# flush() + os.fsync() — mitiga perda em crash/desligamento repentino.
# ---------------------------------------------------------------------------
def registrar_input(tipo, dispositivo, **campos):
    """Persiste um evento de input. tipo: 'tecla' | 'clique' | 'gesto'."""
    linha = {
        "ts": datetime.datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "tipo": str(tipo),
        "dispositivo": str(dispositivo),
    }
    linha.update(campos)
    try:
        texto = json.dumps(linha, ensure_ascii=False, default=str) + "\n"
    except Exception as _e:
        texto = json.dumps({"ts": linha["ts"], "tipo": tipo,
                            "dispositivo": dispositivo,
                            "erro_serializacao": str(_e)}, ensure_ascii=False) + "\n"
    with _trava_inputs:
        try:
            with open(ARQUIVO_AUDITORIA_INPUTS, "a", encoding="utf-8") as f:
                f.write(texto)
                f.flush()
                os.fsync(f.fileno())
        except Exception as e:
            try:
                print(f"[AUDITORIA INPUTS FALHA] {e} :: {texto.rstrip()}", flush=True)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# DIGITAÇÃO AO VIVO — acúmulo das teclas NA MESMA LINHA, em ordem.
# ---------------------------------------------------------------------------
# Cada tecla chega UMA a UMA (evento 'teclado_tecla', capturado no clique da
# tecla do teclado virtual) e é registrada IMEDIATAMENTE — nenhuma letra se
# perde:
#   • mantém um buffer por dispositivo com o texto que está sendo digitado;
#   • a cada tecla reescreve um arquivo PARCIAL durável (flush+fsync) — se o
#     servidor cair no meio, a última letra já está em disco;
#   • FECHA a linha (grava a sequência completa em UMA linha no .log) quando:
#     Enter ("\n"), troca de app, ociosidade (> IDLE_LINHA_TECLADO_S) ou
#     desconexão do dispositivo.
# Saída:
#   gravacoes/teclado/<slug>.log        -> 1 linha JSON por sessão de digitação
#                                          {ts, dispositivo, app, texto, motivo}
#   gravacoes/teclado/<slug>.atual.txt  -> texto da linha em andamento (volátil)
# ---------------------------------------------------------------------------
PASTA_TECLADO = os.path.join(PASTA_GRAVACOES, "teclado")
try:
    os.makedirs(PASTA_TECLADO, exist_ok=True)
except Exception:
    pass
IDLE_LINHA_TECLADO_S = 8.0   # gap sem digitar que fecha a linha atual
_trava_teclado = threading.Lock()
_buffers_teclado = {}        # dispositivo -> {"texto","app","ultimo_ts"}


def _caminho_log_teclado(dispositivo):
    return os.path.join(PASTA_TECLADO, f"{_slug_dispositivo(dispositivo)}.log")


def _caminho_parcial_teclado(dispositivo):
    return os.path.join(PASTA_TECLADO, f"{_slug_dispositivo(dispositivo)}.atual.txt")


def _persistir_parcial_teclado(dispositivo, st):
    """Grava o texto em andamento (durável) — garante que nenhuma letra se perca
    mesmo se a linha ainda não foi fechada."""
    try:
        with open(_caminho_parcial_teclado(dispositivo), "w", encoding="utf-8") as f:
            f.write(st.get("texto", ""))
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        pass


def _fechar_linha_teclado(dispositivo, st, motivo="flush"):
    """Grava a sequência acumulada em UMA linha no .log e limpa o parcial."""
    texto = st.get("texto", "")
    if not texto:
        return
    linha = {
        "ts": datetime.datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "dispositivo": str(dispositivo),
        "app": st.get("app"),
        "texto": texto,
        "motivo": motivo,
    }
    try:
        with open(_caminho_log_teclado(dispositivo), "a", encoding="utf-8") as f:
            f.write(json.dumps(linha, ensure_ascii=False, default=str) + "\n")
            f.flush()
            os.fsync(f.fileno())
    except Exception as e:
        try:
            print(f"[TECLADO FALHA] {e} :: {texto}", flush=True)
        except Exception:
            pass
    # Espelha na trilha única de inputs: 1 linha = 1 sessão de digitação.
    try:
        registrar_input("texto_digitado", dispositivo, texto=texto,
                        app=st.get("app"), motivo=motivo)
    except Exception:
        pass
    # Linha fechada: remove o parcial.
    try:
        os.remove(_caminho_parcial_teclado(dispositivo))
    except Exception:
        pass


def registrar_tecla(dispositivo, tecla, app=None):
    """Registra UMA tecla no instante do toque, acumulando na MESMA linha.

    tecla: caractere cru vindo do app — letra/dígito/símbolo, " " (espaço),
           "\\b" (backspace/apagar) ou "\\n" (enter/confirmar).
    Nunca levanta exceção."""
    if not dispositivo or tecla is None:
        return
    agora = time.time()
    with _trava_teclado:
        st = _buffers_teclado.get(dispositivo)
        # Ociosidade: fecha a linha anterior e começa outra.
        if st and (agora - st.get("ultimo_ts", agora)) > IDLE_LINHA_TECLADO_S:
            _fechar_linha_teclado(dispositivo, st, motivo="idle")
            st = None
        # Troca de app: fecha a linha anterior (contexto diferente).
        if st and app and st.get("app") and app != st.get("app"):
            _fechar_linha_teclado(dispositivo, st, motivo="troca_app")
            st = None
        if st is None:
            st = {"texto": "", "app": app, "ultimo_ts": agora}
            _buffers_teclado[dispositivo] = st
        st["ultimo_ts"] = agora
        if app:
            st["app"] = app
        if tecla == "\b":
            # Apagar: remove o último caractere (espelha o backspace do usuário).
            st["texto"] = st["texto"][:-1]
            _persistir_parcial_teclado(dispositivo, st)
        elif tecla == "\n":
            # Enter: fecha a linha agora (confirma/envia).
            _fechar_linha_teclado(dispositivo, st, motivo="enter")
            _buffers_teclado.pop(dispositivo, None)
        else:
            st["texto"] += tecla
            _persistir_parcial_teclado(dispositivo, st)


def finalizar_teclado(dispositivo=None):
    """Fecha a(s) linha(s) em andamento (ao desconectar o dispositivo ou no
    shutdown). Sem argumento, fecha todas."""
    with _trava_teclado:
        nomes = [dispositivo] if dispositivo else list(_buffers_teclado.keys())
        for n in nomes:
            st = _buffers_teclado.pop(n, None)
            if st:
                _fechar_linha_teclado(n, st, motivo="desconexao")


# ---------------------------------------------------------------------------
# Metadados: append atômico em historico_gravacoes.json (lista de objetos).
# Mantém compatibilidade estrutural com o historico.json do projeto (lista de
# dicts serializados em UTF-8). Escrita via .tmp + os.replace = atômica.
# ---------------------------------------------------------------------------
_trava_historico = threading.Lock()


def _salvar_metadados(entrada):
    with _trava_historico:
        historico = []
        if os.path.exists(ARQUIVO_HISTORICO):
            try:
                with open(ARQUIVO_HISTORICO, "r", encoding="utf-8") as f:
                    conteudo = json.load(f)
                if isinstance(conteudo, list):
                    historico = conteudo
            except Exception:
                historico = []  # arquivo corrompido/truncado → recomeça (backup implícito)
        historico.append(entrada)
        if len(historico) > MAX_HISTORICO_ENTRADAS:
            historico = historico[-MAX_HISTORICO_ENTRADAS:]
        tmp = ARQUIVO_HISTORICO + ".tmp"
        ok = False
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(historico, f, ensure_ascii=False, indent=2, default=str)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, ARQUIVO_HISTORICO)  # atômico no mesmo filesystem
            ok = True
        except Exception as e:
            registrar_auditoria("metadados_erro", erro=str(e),
                                sessao=entrada.get("sessao_id"))
            try:
                os.remove(tmp)
            except Exception:
                pass
        return ok


def listar_historico():
    """Retorna a lista de metadados gravados (para a rota /gravacoes do painel)."""
    try:
        if os.path.exists(ARQUIVO_HISTORICO):
            with open(ARQUIVO_HISTORICO, "r", encoding="utf-8") as f:
                conteudo = json.load(f)
            return conteudo if isinstance(conteudo, list) else []
    except Exception:
        pass
    return []


# ---------------------------------------------------------------------------
# GravadorVideo: consome frames JPEG de uma fila e escreve em .avi via OpenCV.
# Roda em thread própria para NÃO bloquear o loop asyncio do Socket.IO.
# ---------------------------------------------------------------------------
class GravadorVideo:
    def __init__(self, dispositivo, operador=None):
        if cv2 is None:
            raise RuntimeError(ERRO_IMPORT or "OpenCV indisponível")
        self.dispositivo = str(dispositivo)
        self.operador = operador
        self.sessao_id = uuid.uuid4().hex[:12]
        self.inicio_ts = time.time()
        self.inicio_iso = (datetime.datetime.now().astimezone()
                           .isoformat(timespec="seconds"))
        self.nome_arquivo = (
            f"{_slug_dispositivo(self.dispositivo)}_"
            f"{int(self.inicio_ts)}_{self.sessao_id}{EXTENSAO}"
        )
        self.caminho_video = os.path.join(PASTA_VIDEOS, self.nome_arquivo)
        self._fila = queue.Queue(maxsize=MAX_FILA)
        self._writer = None
        self._resolucao = None
        self._frames_escritos = 0
        self._frames_descartados = 0
        self._erro = None
        self._finalizado = threading.Event()
        self._thread = threading.Thread(
            target=self._loop_escrita, daemon=True,
            name=f"grav-{self.sessao_id}",
        )
        self._thread.start()
        registrar_auditoria(
            "gravacao_iniciada", dispositivo=self.dispositivo,
            sessao=self.sessao_id, caminho=self.caminho_video,
            operador=operador, codec=CODEC, fps=FPS_ALVO,
        )

    # -- API pública -------------------------------------------------------
    def enfileirar(self, jpeg_bytes):
        """Chamado a cada frame recebido no handler 'enviar_tela'.
        NUNCA descarta: se a fila estiver cheia, bloqueia (backpressure) até
        liberar espaço. Por ser bloqueante, o server.py deve chamar este
        método via run_in_executor (asyncio) para não travar o loop de eventos.
        """
        if self._finalizado.is_set():
            return
        dados = bytes(jpeg_bytes)
        while not self._finalizado.is_set():
            try:
                self._fila.put(dados, block=True, timeout=1.0)
                return
            except queue.Full:
                # Backpressure: avisa e continua tentando. O produtor
                # desacelera; nenhum frame é perdido.
                if self._fila.qsize() >= MAX_FILA:
                    registrar_auditoria(
                        "fila_cheia_backpressure",
                        dispositivo=self.dispositivo, sessao=self.sessao_id,
                        tamanho=self._fila.qsize(), max_fila=MAX_FILA)
                continue

    def finalizar(self):
        """Encerra a gravação, drena a fila, libera o VideoWriter e persiste
        metadados. Retorna o dict de metadados da sessão."""
        self._finalizado.set()
        # SINCRONIZAÇÃO COMPLETA: aguarda a thread drenar 100% da fila e dar
        # release() no arquivo. Timeout dinâmico e generoso (não mais 15s fixo,
        # que cortava os segundos finais quando a fila estava cheia).
        _pendentes = self._fila.qsize()
        _timeout = max(60.0, (_pendentes / max(FPS_ALVO, 1.0)) * 4.0 + 30.0)
        self._thread.join(timeout=_timeout)
        if self._thread.is_alive():
            registrar_auditoria(
                "finalizar_timeout_join", dispositivo=self.dispositivo,
                sessao=self.sessao_id, timeout=_timeout,
                frames_pendentes=_pendentes,
                frames_escritos=self._frames_escritos)
        fim_ts = time.time()
        fim_iso = (datetime.datetime.now().astimezone()
                   .isoformat(timespec="seconds"))
        metadados = {
            "sessao_id": self.sessao_id,
            "dispositivo": self.dispositivo,
            "operador": self.operador,
            "inicio_iso": self.inicio_iso,
            "fim_iso": fim_iso,
            "duracao_s": round(fim_ts - self.inicio_ts, 2),
            "frames_gravados": self._frames_escritos,
            "frames_descartados": self._frames_descartados,
            "resolucao": list(self._resolucao) if self._resolucao else None,
            "codec": CODEC,
            "fps": FPS_ALVO,
            "caminho_video": self.caminho_video,
            "tamanho_bytes": (os.path.getsize(self.caminho_video)
                              if os.path.exists(self.caminho_video) else 0),
            "erro": self._erro,
        }
        _salvar_metadados(metadados)
        _copiar_para_fps(self.caminho_video)  # gravação dupla: também em FPS/videos
        registrar_auditoria(
            "gravacao_finalizada", dispositivo=self.dispositivo,
            sessao=self.sessao_id, frames=self._frames_escritos,
            descartados=self._frames_descartados,
            duracao_s=metadados["duracao_s"],
            tamanho_bytes=metadados["tamanho_bytes"], erro=self._erro,
        )
        return metadados

    # -- loop interno (thread) --------------------------------------------
    def _loop_escrita(self):
        fourcc = cv2.VideoWriter_fourcc(*CODEC)
        # SEGURANÇA NO FECHAMENTO: try/finally garante o release() do VideoWriter
        # mesmo que uma exceção inesperada escape do laço (fila, decode, etc.).
        # Sem release(), o arquivo .avi fica com o índice MJPG incompleto e abre
        # corrompido. O finally abaixo fecha SEMPRE.
        try:
          while not self._finalizado.is_set() or not self._fila.empty():
            try:
                jpeg = self._fila.get(timeout=0.05)  # prioridade alta de drenagem
            except queue.Empty:
                continue
            try:
                arr = np.frombuffer(jpeg, dtype=np.uint8)
                frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if frame is None:
                    self._frames_descartados += 1
                    continue
                h, w = frame.shape[:2]
                if self._writer is None:
                    self._resolucao = (w, h)
                    self._writer = cv2.VideoWriter(
                        self.caminho_video, fourcc, FPS_ALVO, (w, h))
                    if not self._writer.isOpened():
                        self._erro = (
                            f"VideoWriter não abriu (codec {CODEC}/"
                            f"resolução {w}x{h} incompatível)"
                        )
                        try:
                            import sys as _sys_vw
                            print(f"[GRAVACAO ERRO] {self._erro} | "
                                  f"arquivo={self.caminho_video}",
                                  file=_sys_vw.stderr, flush=True)
                        except Exception:
                            pass
                        registrar_auditoria("videowriter_erro",
                                            dispositivo=self.dispositivo,
                                            erro=self._erro,
                                            caminho=self.caminho_video)
                        self._finalizado.set()
                        break
                # CORREÇÃO CRÍTICA: cv2.VideoWriter.write DESCARTA
                # SILENCIOSAMENTE frames de tamanho diferente do inicializado
                # (causa real do "efeito congelamento"/vídeo acelerado quando o
                # Android muda de resolução no meio da transmissão).
                # Redimensiona sempre para a resolução do primeiro frame.
                if (w, h) != self._resolucao:
                    frame = cv2.resize(frame, self._resolucao,
                                       interpolation=cv2.INTER_AREA)
                self._writer.write(frame)
                self._frames_escritos += 1
            except Exception as e:
                self._erro = str(e)
                self._frames_descartados += 1
        except Exception as _e_loop:
            # Exceção INESPERADA fora do try por-frame: registra e cai no finally
            # (que fecha o arquivo), em vez de deixar a thread morrer com o
            # VideoWriter aberto e o .avi corrompido.
            self._erro = f"loop_escrita: {_e_loop}"
            try:
                registrar_auditoria("loop_escrita_erro",
                                    dispositivo=self.dispositivo,
                                    sessao=self.sessao_id, erro=self._erro)
            except Exception:
                pass
        finally:
            if self._writer is not None:
                try:
                    self._writer.release()
                except Exception:
                    pass


class GravadorFallback:
    """Gravador SEM OpenCV: salva cada frame JPEG recebido como arquivo
    individual em uma pasta de sessão. Usado automaticamente quando o cv2
    não carrega (ex: .exe PyInstaller sem as DLLs do OpenCV). Garante que a
    gravação FUNCIONE SEMPRE — zero perda de frames. Os JPEGs podem ser
    convertidos a vídeo depois com: ffmpeg -framerate 15 -i frame_%06d.jpg out.mp4
    """

    def __init__(self, dispositivo, operador=None):
        self.dispositivo = str(dispositivo)
        self.operador = operador
        self.sessao_id = uuid.uuid4().hex[:12]
        self.inicio_ts = time.time()
        self.inicio_iso = (datetime.datetime.now().astimezone()
                           .isoformat(timespec="seconds"))
        slug = _slug_dispositivo(self.dispositivo)
        self.pasta_sessao = os.path.join(
            PASTA_VIDEOS, f"{slug}_{int(self.inicio_ts)}_{self.sessao_id}")
        os.makedirs(self.pasta_sessao, exist_ok=True)
        self.caminho_video = self.pasta_sessao  # fallback = pasta de frames
        self._fila = queue.Queue(maxsize=MAX_FILA)
        self._frames_escritos = 0
        self._frames_descartados = 0
        self._erro = None
        self._finalizado = threading.Event()
        self._thread = threading.Thread(target=self._loop_escrita, daemon=True,
                                        name=f"grav-fb-{self.sessao_id}")
        self._thread.start()
        registrar_auditoria("gravacao_iniciada_fallback", dispositivo=self.dispositivo,
                            sessao=self.sessao_id, caminho=self.caminho_video,
                            operador=operador, codec="JPEG_SEQUENCE", fps=FPS_ALVO)

    def enfileirar(self, jpeg_bytes):
        """NUNCA descarta (backpressure). Mesma semântica do GravadorVideo."""
        if self._finalizado.is_set():
            return
        dados = bytes(jpeg_bytes)
        while not self._finalizado.is_set():
            try:
                self._fila.put(dados, block=True, timeout=1.0)
                return
            except queue.Full:
                continue

    def _loop_escrita(self):
        while not self._finalizado.is_set() or not self._fila.empty():
            try:
                jpeg = self._fila.get(timeout=0.05)  # prioridade alta de drenagem
            except queue.Empty:
                continue
            try:
                self._frames_escritos += 1
                _caminho_frame = os.path.join(
                    self.pasta_sessao, f"frame_{self._frames_escritos:06d}.jpg")
                with open(_caminho_frame, "wb") as _f:
                    _f.write(jpeg)
            except Exception as e:
                self._erro = str(e)
                self._frames_descartados += 1

    def finalizar(self):
        self._finalizado.set()
        # SINCRONIZAÇÃO COMPLETA: aguarda a thread drenar 100% da fila e dar
        # release() no arquivo. Timeout dinâmico e generoso (não mais 15s fixo,
        # que cortava os segundos finais quando a fila estava cheia).
        _pendentes = self._fila.qsize()
        _timeout = max(60.0, (_pendentes / max(FPS_ALVO, 1.0)) * 4.0 + 30.0)
        self._thread.join(timeout=_timeout)
        if self._thread.is_alive():
            registrar_auditoria(
                "finalizar_timeout_join", dispositivo=self.dispositivo,
                sessao=self.sessao_id, timeout=_timeout,
                frames_pendentes=_pendentes,
                frames_escritos=self._frames_escritos)
        fim_ts = time.time()
        fim_iso = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        try:
            _tam = sum(os.path.getsize(os.path.join(self.pasta_sessao, f))
                       for f in os.listdir(self.pasta_sessao)) if os.path.isdir(self.pasta_sessao) else 0
        except Exception:
            _tam = 0
        metadados = {
            "sessao_id": self.sessao_id,
            "dispositivo": self.dispositivo,
            "operador": self.operador,
            "inicio_iso": self.inicio_iso,
            "fim_iso": fim_iso,
            "duracao_s": round(fim_ts - self.inicio_ts, 2),
            "frames_gravados": self._frames_escritos,
            "frames_descartados": self._frames_descartados,
            "resolucao": None,
            "codec": "JPEG_SEQUENCE",
            "fps": FPS_ALVO,
            "caminho_video": self.caminho_video,
            "tamanho_bytes": _tam,
            "erro": self._erro,
            "modo": "fallback_sem_opencv",
        }
        _salvar_metadados(metadados)
        _copiar_para_fps(self.caminho_video)  # gravação dupla: também em FPS/videos
        registrar_auditoria("gravacao_finalizada_fallback", dispositivo=self.dispositivo,
                            sessao=self.sessao_id, frames=self._frames_escritos,
                            duracao_s=metadados["duracao_s"], erro=self._erro)
        return metadados


def _slug_dispositivo(nome):
    """Sanitiza o nome do dispositivo para ser seguro em nome de arquivo."""
    import re
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", str(nome)).strip("_") or "device"


# ---------------------------------------------------------------------------
# Estado global + funções de controle (usadas pelo server.py).
# ---------------------------------------------------------------------------
gravadores_ativos = {}          # nome_dispositivo -> GravadorVideo
_trava_gravadores = threading.Lock()


def iniciar_gravacao(dispositivo, operador=None):
    if not dispositivo:
        return {"ok": False, "erro": "Dispositivo não informado"}
    # Escolhe o gravador: OpenCV (.avi) se disponível, senão fallback JPEG.
    # Assim a gravação FUNCIONA SEMPRE, mesmo se o PyInstaller não empacotou
    # as DLLs do cv2 (cenário comum em .exe --windowed sem console).
    ClsGravador = GravadorVideo if cv2 is not None else GravadorFallback
    # Idempotente e atômico (um único lock): se já existe gravação ativa para
    # este dispositivo, NÃO reinicia (evita perder vídeo) — retorna ok com a
    # flag ja_ativa. Isso permite o gatilho por primeiro frame (chamado a cada
    # frame recebido em 'enviar_tela') sem risco de recomeço concorrente.
    with _trava_gravadores:
        _existente = gravadores_ativos.get(dispositivo)
        if _existente is not None:
            return {"ok": True, "ja_ativa": True,
                    "sessao_id": _existente.sessao_id,
                    "caminho": _existente.caminho_video}
        try:
            g = ClsGravador(dispositivo, operador=operador)
        except Exception as e:
            registrar_auditoria("gravacao_inicio_erro", dispositivo=dispositivo,
                                erro=str(e))
            return {"ok": False, "erro": str(e)}
        gravadores_ativos[dispositivo] = g
        return {"ok": True, "sessao_id": g.sessao_id, "caminho": g.caminho_video}


def finalizar_gravacao(dispositivo):
    with _trava_gravadores:
        g = gravadores_ativos.pop(dispositivo, None)
    if g is None:
        return {"ok": False, "erro": "Nenhuma gravação ativa para este dispositivo"}
    try:
        metadados = g.finalizar()
        return {"ok": True, **metadados}
    except Exception as e:
        registrar_auditoria("gravacao_finalizacao_erro", dispositivo=dispositivo,
                            erro=str(e))
        return {"ok": False, "erro": str(e)}


def finalizar_todas_gravacoes():
    """Hardening de shutdown: finaliza todas as gravações pendentes."""
    with _trava_gravadores:
        nomes = list(gravadores_ativos.keys())
    return [finalizar_gravacao(n) for n in nomes]


def estado_gravacao(dispositivo=None):
    """Retorna estado(s) das gravações ativas (para o painel consultar)."""
    with _trava_gravadores:
        if dispositivo:
            g = gravadores_ativos.get(dispositivo)
            return {dispositivo: {"sessao_id": g.sessao_id,
                                  "inicio_iso": g.inicio_iso,
                                  "caminho": g.caminho_video} if g else None}
        return {n: {"sessao_id": g.sessao_id, "inicio_iso": g.inicio_iso,
                    "caminho": g.caminho_video}
                for n, g in gravadores_ativos.items()}


def exportar_auditoria(dispositivo, caminho_saida=None):
    """Compila em um .zip: histórico de sessões (metadados JSON), vídeos do
    dispositivo, logs de input (teclado/cliques/gestos) e trilha de auditoria
    filtrada. Usado pela rota /gravacoes/exportar/{dispositivo}.
    Retorna o caminho absoluto do .zip gerado."""
    import zipfile
    slug = slug_dispositivo(dispositivo)
    if caminho_saida is None:
        # Exporta também estritamente dentro de PASTA_BASE (sem temp do Windows).
        _ts_exp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        caminho_saida = os.path.join(PASTA_GRAVACOES,
                                     f"export_{slug}_{_ts_exp}.zip")
    historico = [e for e in listar_historico()
                 if str(e.get("dispositivo")) == str(dispositivo)]
    n_inputs = 0
    with zipfile.ZipFile(caminho_saida, "w", zipfile.ZIP_DEFLATED) as zf:
        # 1) Metadados das sessões de gravação (estrutura de lista preservada)
        zf.writestr(f"{slug}/sessoes.json",
                    json.dumps(historico, ensure_ascii=False, indent=2,
                               default=str))
        # 2) Logs de input (JSONL) filtrados por dispositivo — stream linha a linha
        _buf_inputs = io.StringIO()
        if os.path.exists(ARQUIVO_AUDITORIA_INPUTS):
            with open(ARQUIVO_AUDITORIA_INPUTS, "r", encoding="utf-8",
                      errors="replace") as _f:
                for _linha in _f:
                    try:
                        _obj = json.loads(_linha)
                    except Exception:
                        continue
                    if str(_obj.get("dispositivo")) == str(dispositivo):
                        _buf_inputs.write(_linha if _linha.endswith("\n")
                                          else _linha + "\n")
                        n_inputs += 1
        zf.writestr(f"{slug}/inputs.jsonl", _buf_inputs.getvalue())
        # 3) Trilha de auditoria geral filtrada (menções ao dispositivo)
        _buf_aud = io.StringIO()
        if os.path.exists(ARQUIVO_AUDITORIA):
            with open(ARQUIVO_AUDITORIA, "r", encoding="utf-8",
                      errors="replace") as _f:
                for _linha in _f:
                    if str(dispositivo) in _linha:
                        _buf_aud.write(_linha if _linha.endswith("\n")
                                       else _linha + "\n")
        zf.writestr(f"{slug}/auditoria.log", _buf_aud.getvalue())
        # 4) Vídeos gravados deste dispositivo
        _n_videos = 0
        if os.path.isdir(PASTA_VIDEOS):
            for _nome_arq in os.listdir(PASTA_VIDEOS):
                if not _nome_arq.startswith(slug + "_"):
                    continue
                _caminho_completo = os.path.join(PASTA_VIDEOS, _nome_arq)
                if os.path.isfile(_caminho_completo):
                    zf.write(_caminho_completo, arcname=f"{slug}/videos/{_nome_arq}")
                    _n_videos += 1
                elif os.path.isdir(_caminho_completo):
                    # Sessão fallback (sequência de JPEG): inclui todos os frames.
                    for _raiz, _dirs, _arqs in os.walk(_caminho_completo):
                        for _a in sorted(_arqs):
                            _full = os.path.join(_raiz, _a)
                            _rel = os.path.relpath(_full, PASTA_VIDEOS)
                            zf.write(_full, arcname=f"{slug}/videos/{_rel}")
                            _n_videos += 1
        # 5) Manifesto do pacote (índice para auditoria)
        _manifesto = {
            "dispositivo": dispositivo,
            "slug": slug,
            "gerado_em": (datetime.datetime.now().astimezone()
                          .isoformat(timespec="seconds")),
            "sessoes": len(historico),
            "eventos_input": n_inputs,
            "videos_incluidos": _n_videos,
            "codec": CODEC,
        }
        zf.writestr(f"{slug}/manifesto.json",
                    json.dumps(_manifesto, ensure_ascii=False, indent=2,
                               default=str))
    registrar_auditoria("export_zip_gerado", dispositivo=dispositivo,
                        caminho=caminho_saida, sessoes=len(historico),
                        eventos_input=n_inputs)
    return caminho_saida


# Alias público do sanitizador de nome de dispositivo (usado pelo server.py).
slug_dispositivo = _slug_dispositivo

# Sinaliza ao server.py se o módulo está operacional.
# Gravador SEMPRE disponível: se OpenCV ausente, usa fallback JPEG.
OPENCV_DISPONIVEL = cv2 is not None
DISPONIVEL = True
