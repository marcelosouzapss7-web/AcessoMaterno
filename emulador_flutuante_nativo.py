# -*- coding: utf-8 -*-
"""
Emulador Flutuante NATIVO (Windows) — Acesso Materno
=====================================================
Janela nativa, leve e sempre-no-topo, que espelha a tela do aparelho Android
conectado ao SEU servidor Socket.IO existente. Pensado para máquinas de pouca
RAM (8 GB): substitui a janela do navegador (Chrome/WebView), que é pesada, por
um app nativo enxuto.

Como funciona (reaproveita o protocolo do server.py, NADA muda no servidor):
  • Conecta no servidor e se registra como painel:   emit('identificar', 'painel')
  • Pede o stream de um aparelho:                     emit('assistir_video', {'dispositivo': NOME})
  • Recebe os frames JPEG pelo evento 'frame'  -> [ {dispositivo}, bytes_jpeg ]
  • Clique no vídeo  -> emit('clique_painel', {x, y, dispositivo})  (toque)
  • Botões laterais  -> emit('botao_voltar'|'botao_home'|'botao_recentes'|
                              'botao_power'|'notificacao_acordar', {dispositivo})

Decodificação do JPEG: feita pelo próprio Qt (QPixmap.loadFromData), então NÃO
precisa de OpenCV, PyAV nem numpy. Dependências mínimas:

    pip install PySide6 "python-socketio[client]"

Uso:
    python emulador_flutuante_nativo.py
    python emulador_flutuante_nativo.py --url http://localhost:27105 --dispositivo "MeuAparelho"

Se não passar --dispositivo, ele pega o primeiro da lista (/lista-dispositivos).
"""

import sys
import os
import time
import json
import queue
import argparse
import threading
import urllib.request

import socketio
from PySide6 import QtCore, QtGui, QtWidgets, QtNetwork

# Decoder H.264 da CÂMERA (botão "View"): PyAV (av) — já usado/instalado na
# .venv-build. Guarda de import: sem o av, a janela da câmera cai no fallback
# JPEG (camera_frame) sem quebrar o resto do emulador.
try:
    import av as _av
    import numpy as _np
    _AV_OK = True
except Exception:
    _av = None
    _np = None
    _AV_OK = False

# ----------------------------------------------------------------------------
# WebRTC (H.264) OPCIONAL — recepção de vídeo em tempo real, igual ao painel web.
# Import com guarda: se aiortc/av/numpy não estiverem na .venv-build, o emulador
# continua 100% funcional só com o JPEG (fallback). NÃO quebra o app.
#   Instalar:  .venv-build\Scripts\python.exe -m pip install "aiortc==1.16.0"
# ----------------------------------------------------------------------------
WEBRTC_DISPONIVEL = False
_WEBRTC_IMPORT_ERRO = "WebRTC desativado (modo híbrido Socket.IO + HTTP puro)"


# Nome do "canal" de instância única (evita abrir duas janelas).
NOME_INSTANCIA = "AcessoMaternoEmuladorNativo"


# ----------------------------------------------------------------------------
# Configuração padrão (pode sobrescrever por --url / --dispositivo)
# ----------------------------------------------------------------------------
URL_PADRAO = "http://localhost:27105"


def listar_dispositivos(url_base: str):
    """GET /lista-dispositivos — retorna a lista de aparelhos conectados.
    Tolerante a formatos diferentes de resposta; devolve lista de nomes."""
    try:
        with urllib.request.urlopen(f"{url_base}/lista-dispositivos", timeout=5.0) as r:
            dados = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print(f"[AVISO] Não consegui ler /lista-dispositivos: {e}")
        return []

    nomes = []
    aparelhos = dados.get("aparelhos", []) if isinstance(dados, dict) else []
    for ap in aparelhos:
        if isinstance(ap, dict):
            nome = ap.get("nome")
            conectado = ap.get("conectado", False)
            ativo = ap.get("ativo", False)
            if nome and (conectado or ativo):
                nomes.append(nome)

    if not nomes:
        seq = dados if isinstance(dados, list) else dados.get("dispositivos", []) if isinstance(dados, dict) else []
        for item in seq:
            if isinstance(item, str):
                nomes.append(item)
            elif isinstance(item, dict):
                n = item.get("nome") or item.get("dispositivo") or item.get("name")
                if n:
                    nomes.append(n)
    return nomes


def obter_dispositivo_configurado(url_base: str):
    """GET /dispositivo-ativo — devolve o aparelho CONFIGURADO no servidor
    (o da 'sala específica': dispositivo_selecionado -> último frame ->
    primeiro conectado). É PRIORIDADE MÁXIMA para a conexão automática: o
    emulador nativo deve travar EXATAMENTE neste aparelho, não em qualquer
    um que esteja online. Retorna None se o servidor disser que não há
    dispositivo ativo (status == 'nenhum_dispositivo')."""
    try:
        with urllib.request.urlopen(f"{url_base}/dispositivo-ativo", timeout=5.0) as r:
            dados = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print(f"[AVISO] Não consegui ler /dispositivo-ativo: {e}")
        return None
    if not isinstance(dados, dict):
        return None
    if str(dados.get("status") or "") == "nenhum_dispositivo":
        return None
    nome = dados.get("dispositivo")
    if isinstance(nome, str) and nome.strip():
        return nome.strip()
    return None


def dispositivo_esta_online(url_base: str, nome: str):
    """Confirma em /lista-dispositivos que o aparelho 'nome' está com socket
    vivo (conectado=True). Usado para NÃO travar a janela num dispositivo
    configurado mas offline (stale) — a causa mais comum de 'não conecta'."""
    if not nome:
        return False
    try:
        with urllib.request.urlopen(f"{url_base}/lista-dispositivos", timeout=5.0) as r:
            dados = json.loads(r.read().decode("utf-8"))
    except Exception:
        return False
    for ap in (dados.get("aparelhos") or []) if isinstance(dados, dict) else []:
        if isinstance(ap, dict) and str(ap.get("nome")) == str(nome):
            return bool(ap.get("conectado"))
    return False


def primeiro_dispositivo_online(url_base: str, preferir_ativo=True):
    """Primeiro aparelho ONLINE de /lista-dispositivos (preferencialmente o
    que está transmitindo frames = ativo). Fallback robusto quando não há
    dispositivo configurado válido."""
    nomes = listar_dispositivos(url_base)
    if not nomes:
        return None
    if preferir_ativo:
        try:
            with urllib.request.urlopen(f"{url_base}/lista-dispositivos", timeout=5.0) as r:
                dados = json.loads(r.read().decode("utf-8"))
            for ap in (dados.get("aparelhos") or []) if isinstance(dados, dict) else []:
                if isinstance(ap, dict) and ap.get("conectado") and ap.get("ativo"):
                    return str(ap.get("nome"))
        except Exception:
            pass
    return nomes[0]


def dispositivo_transmitindo(url_base: str, preferir=None):
    """Nome do aparelho que está REALMENTE transmitindo frames AGORA
    (conectado=True E ativo=True em /lista-dispositivos). É a fonte de verdade
    para a conexão automática: 'conecta no servidor que está recebendo os
    frames'. Se `preferir` estiver transmitindo, devolve-o; senão devolve o
    primeiro aparelho transmitindo. None se ninguém está transmitindo."""
    try:
        with urllib.request.urlopen(f"{url_base}/lista-dispositivos", timeout=5.0) as r:
            dados = json.loads(r.read().decode("utf-8"))
    except Exception:
        return None
    aparelhos = (dados.get("aparelhos") or []) if isinstance(dados, dict) else []
    transmitindo = [
        str(ap.get("nome"))
        for ap in aparelhos
        if isinstance(ap, dict) and ap.get("nome") and ap.get("conectado") and ap.get("ativo")
    ]
    if not transmitindo:
        return None
    if preferir and preferir in transmitindo:
        return preferir
    return transmitindo[0]


def resolver_alvo_automatico(url_base: str):
    """Conexão automática ORDENADA (compatível com a sala configurada):
       1) dispositivo configurado no servidor (/dispositivo-ativo) — SALA
          ESPECÍFICA — mas SÓ se ele estiver ONLINE (conectado=True);
       2) primeiro aparelho online (preferencialmente transmitindo frames).
    Devolve (nome, origem) ou (None, None)."""
    cfg = obter_dispositivo_configurado(url_base)
    if cfg and dispositivo_esta_online(url_base, cfg):
        return cfg, "configurado"
    if cfg:
        print(f"[AVISO] Dispositivo configurado '{cfg}' está OFFLINE — "
              f"caindo no primeiro aparelho online.")
    primeiro = primeiro_dispositivo_online(url_base)
    if primeiro:
        return primeiro, "primeiro_online"
    return None, None


# ----------------------------------------------------------------------------
# Ponte Socket.IO (roda numa thread própria) -> sinais Qt para a GUI
# ----------------------------------------------------------------------------
class PonteSocket(QtCore.QObject):
    # Sinais thread-safe para atualizar a interface a partir da thread do socket.
    frameRecebido = QtCore.Signal(bytes)   # bytes JPEG
    frameRGB = QtCore.Signal(bytes, int, int)  # H.264 decodificado (rgb24 + w, h)
    conexaoMudou = QtCore.Signal(bool)     # True = conectado
    # Handler SEPARADO e exclusivo do emulador nativo: liga/desliga a animação
    # "Carregando atualização" sobre o vídeo. NÃO afeta o dispositivo — é só a
    # tela do SEU emulador. (O handler do dispositivo será feito na próxima etapa.)
    atualizacaoMudou = QtCore.Signal(bool)  # True = mostrar overlay de atualização
    # Emitido SEMPRE que o árbitro trava/re-trava num aparelho (nome do device).
    # A janela escuta isto para manter self.dispositivo SINCRONIZADO com a fonte
    # que está REALMENTE no vídeo — assim cliques, botões e teclado vão para o
    # aparelho que aparece na tela, nunca para um nome antigo/offline (que o
    # servidor descartaria por ISOLAMENTO, fazendo o clique "não funcionar").
    fonteTravou = QtCore.Signal(str)
    # Tecla digitada FISICAMENTE no aparelho (dedo no teclado do Android). O app
    # Android captura cada toque e emite 'teclado_tecla'; o servidor repassa para
    # a sala stream:{dispositivo}. A janela escuta para gravar também o que é
    # digitado com o dedo em teclado/digitado.txt — nada de texto/número se perde.
    teclaFisica = QtCore.Signal(str)   # caractere cru, " " = espaço, "\b" = apagar

    def _definir_fonte(self, nome):
        """Ponto ÚNICO de trava do árbitro: grava _fonte_travada e avisa a GUI
        (fonteTravou) para ela re-mirar os comandos no aparelho correto."""
        self._fonte_travada = nome
        if nome:
            try:
                self.fonteTravou.emit(str(nome))
            except Exception:
                pass

    def alvo_comando(self):
        """Aparelho-alvo ÚNICO de TODO comando (clique/gesto/tecla/botão).
        Prioridade: fonte travada pelo árbitro > fonte efetiva (o que está
        REALMENTE desenhado na tela) > dispositivo de abertura. Garante que o
        comando SEGUE o vídeo e nunca cai num nome stale/offline que o servidor
        descartaria por ISOLAMENTO (o que fazia o clique "não funcionar")."""
        return self._fonte_travada or self._fonte_efetiva or self.dispositivo

    def __init__(self, url_base: str, dispositivo: str):
        super().__init__()
        self.url_base = url_base.rstrip("/")
        self.dispositivo = dispositivo
        # ÁRBITRO DE FONTE ÚNICA: trava a janela em UM aparelho. Como a janela
        # SEMPRE nasce com --dispositivo (Visualizar = específico; botão Nativo
        # = último conectado), já começa travada nele. Sem --dispositivo, trava
        # no 1º frame. Qualquer outro aparelho é descartado (sem conferência).
        self._fonte_travada = dispositivo or None
        # FONTE EFETIVA: aparelho cujos frames estão REALMENTE desenhados na tela
        # agora (gravado no handler de frame ao renderizar). Em "modo bypass" o
        # vídeo renderiza SEM travar a fonte (_fonte_travada=None); sem isto o
        # clique/teclado cairia em self.dispositivo (o --dispositivo de abertura,
        # possivelmente offline/diferente) e o servidor DESCARTARIA o comando por
        # ISOLAMENTO — o clique "não funciona". Com a fonte efetiva, o comando
        # SEMPRE segue o aparelho que está no vídeo.
        self._fonte_efetiva = dispositivo or None
        # DISPOSITIVO DESEJADO (sala específica configurada): quando definido,
        # os handlers de auto-conexão (aparelho_online / sessao_retomar /
        # primeiro frame) SÓ travam neste aparelho — qualquer outro que entre
        # online antes é ignorado. Atualizado pelo watchdog a cada 2,5s.
        self._dispositivo_desejado = dispositivo or None
        # RECUPERAÇÃO DE TRAVA STALE: se o aparelho travado não enviar frame
        # por mais de TIMEOUT_SEM_FRAME, destravamos sozinhos e reconectamos
        # no aparelho que REALMENTE está transmitindo (nunca fica preto).
        self._ultimo_frame_ts = 0.0
        # Tolerância de um stream JÁ estabelecido que engasgou: só reconcilia
        # para outro aparelho depois de 12s sem frame (evita troca à toa/tremor).
        self._timeout_sem_frame = 12.0
        # Reconexão RÁPIDA do caso ruim: janela abriu num nome stale/errado e
        # NUNCA recebeu um único frame — aí basta 5s para pular para quem está
        # realmente transmitindo (não faz sentido esperar os 12s completos).
        self._timeout_primeiro_frame = 5.0
        # Instante em que travamos no aparelho atual. Serve para detectar
        # "travado mas NUNCA recebeu frame" (nome stale/errado) mesmo quando
        # _ultimo_frame_ts ainda é 0 — aí o watchdog reconcilia sozinho.
        self._travado_em = time.time() if (dispositivo or None) else 0.0
        # Alvo EXPLÍCITO = usuário clicou em "Visualizar" neste aparelho
        # (veio em --dispositivo). Não forçamos troca automática nele.
        self._alvo_explicito = bool(dispositivo and str(dispositivo).strip())
        # Último instante em que sondamos o socket com um heartbeat COM ACK
        # (sio.call) para detectar socket MEIO-ABERTO. Ver o watchdog em iniciar().
        self._ultimo_probe_ts = 0.0
        self.sio = socketio.Client(reconnection=True, reconnection_delay=1,
                                   logger=False, engineio_logger=False)
        # ---- H.264 de TELA (video_h264, broadcast sala 'painel') ----
        # Recebe os pacotes H.264 do aparelho DESBLOQUEADO e decodifica com PyAV
        # numa thread separada (a GUI nunca trava). Quando chega H.264, o JPEG é
        # ignorado (arbitragem por tempo em _ao_receber_frame). Sem PyAV ou com
        # a tela bloqueada, cai no JPEG de sempre — nada quebra.
        self._ultimo_h264_ts = 0.0
        self._fila_h264 = queue.Queue(maxsize=45)
        self._dec_h264_run = bool(_AV_OK)
        self._registrar_eventos()
        self._parar = False
        if _AV_OK:
            threading.Thread(target=self._loop_decod_h264, daemon=True).start()

    def _registrar_eventos(self):
        sio = self.sio

        @sio.event
        def connect():
            print("[SOCKET] Conectado. Registrando como painel…")
            # Registra como painel (entra na sala 'painel' -> recebe controles).
            sio.emit("identificar", "painel")
            # Reassina SEMPRE a sala do aparelho TRAVADO (não só na 1ª vez) —
            # garante 1 sala/1 dispositivo também depois de cair e reconectar.
            alvo = self.alvo_comando()
            if alvo:
                sio.emit("assistir_video", {"dispositivo": alvo})
            self.conexaoMudou.emit(True)

        @sio.event
        def disconnect():
            print("[SOCKET] Desconectado.")
            self.conexaoMudou.emit(False)

        @sio.on("teclado_tecla")
        def on_teclado_tecla(data=None):
            # Dedo físico no teclado do Android: o app manda CADA tecla crua.
            # Só aceita se for do aparelho que estamos vendo (isolamento), e
            # repassa o caractere para a GUI gravar em teclado/digitado.txt.
            try:
                if isinstance(data, str):
                    data = json.loads(data)
                if not isinstance(data, dict):
                    return
                tecla = data.get("tecla")
                if tecla is None or tecla == "":
                    return
                dev = data.get("dispositivo")
                alvo = self.alvo_comando()
                if dev and alvo and str(dev) != str(alvo):
                    return
                self.teclaFisica.emit(str(tecla))
            except Exception:
                pass

        @sio.on("frame")
        def on_frame(*args):
            # O servidor emite 'frame' como [ {dispositivo: nome}, bytes_jpeg ].
            # Dependendo do cliente, chega como 2 args (meta, jpeg) ou 1 lista.
            meta, jpeg = None, None
            dados = list(args)
            if len(dados) == 1 and isinstance(dados[0], (list, tuple)):
                dados = list(dados[0])
            for d in dados:
                if isinstance(d, (bytes, bytearray)):
                    jpeg = bytes(d)
                elif isinstance(d, dict):
                    meta = d
            # ÁRBITRO DE FONTE ÚNICA (anti-conferência): cada janela = 1 sala =
            # 1 dispositivo. Renderiza SOMENTE frames do aparelho travado; frame
            # de QUALQUER outro (vazamento do handshake/reconexão) é DESCARTADO,
            # então a imagem nunca mistura dois celulares (sem tremor / "mexe só").
            nome_frame = (meta or {}).get("dispositivo") if isinstance(meta, dict) else None
            if self._fonte_travada is None and nome_frame:
                # SALA ESPECÍFICA: se há um dispositivo configurado/desejado,
                # NÃO trava no primeiro frame que chegar de OUTRO aparelho —
                # espera o frame do dispositivo da sala configurada.
                if self._dispositivo_desejado and nome_frame != self._dispositivo_desejado:
                    pass
                else:
                    self._definir_fonte(nome_frame)
                    try:
                        sio.emit("assistir_video", {"dispositivo": nome_frame})
                    except Exception:
                        pass
            if self._fonte_travada and nome_frame and nome_frame != self._fonte_travada:
                return  # frame de OUTRO aparelho -> descartado
            if jpeg:
                self._ultimo_frame_ts = time.time()   # marca que o aparelho travado está vivo
                # Fonte EFETIVA = aparelho que acabou de ser desenhado. Comandos
                # (clique/teclado) miram aqui quando não há trava do árbitro.
                if nome_frame:
                    self._fonte_efetiva = nome_frame
                self.frameRecebido.emit(jpeg)

        @sio.on("video_h264")
        def on_video_h264(*args):
            # H.264 de TELA (broadcast, sala 'painel') do aparelho DESBLOQUEADO.
            # Sem tag de dispositivo (igual ao preview) -> 1 janela = 1 aparelho,
            # então tratamos como a tela atual. Sem PyAV, ignora (fica no JPEG).
            if not _AV_OK:
                return
            nal = _extrair_jpeg(args)   # mesma varredura acha os bytes do H.264
            if not nal:
                return
            self._ultimo_h264_ts = time.time()
            self._ultimo_frame_ts = self._ultimo_h264_ts  # mantém a fonte "viva"
            try:
                self._fila_h264.put_nowait(nal)
            except queue.Full:
                # Atrasou: descarta o mais antigo e guarda o novo (ressincroniza
                # no próximo keyframe) — nunca acumula fila/latência.
                try: self._fila_h264.get_nowait()
                except Exception: pass
                try: self._fila_h264.put_nowait(nal)
                except Exception: pass

        # ---- Handler EXCLUSIVO do emulador nativo (separado do dispositivo) ----
        # Quando ativo=True, o emulador nativo cobre o vídeo com a animação de
        # "Carregando atualização" (ex.: enquanto o anúncio aparece no aparelho).
        #
        # COMPATIBILIDADE: o painel/servidor NÃO emitem 'atualizacao_nativo'.
        # Quem é de fato transmitido para a sala 'painel' (onde este emulador
        # entra) é 'overlay_atualizacao_visual' com {ativo, dispositivo}
        # (index.html -> server.py:/alternar-overlay-atualizacao). Antes o
        # handler escutava só o evento morto 'atualizacao_nativo' e nunca ligava
        # em alguns aparelhos. Agora escutamos AMBOS, com o mesmo tratamento.
        def _tratar_overlay_atualizacao(*args):
            dados = {}
            for a in args:
                if isinstance(a, dict):
                    dados = a
                    break
                if isinstance(a, bool):
                    dados = {"ativo": a}
                    break
            # MODO BYPASS: NÃO filtra por dispositivo — reage a QUALQUER evento de
            # overlay (há tipicamente 1 aparelho por janela nativa). Assim o
            # "Carregando atualização" aparece mesmo que o nome do aparelho no
            # painel difira do --dispositivo com que o nativo abriu.
            ativo = bool(dados.get("ativo", True))
            self.atualizacaoMudou.emit(ativo)

        # Evento real do painel/servidor (o que de fato chega à sala 'painel').
        sio.on("overlay_atualizacao_visual", _tratar_overlay_atualizacao)
        # Mantido por compatibilidade (caso algum fluxo antigo ainda o emita).
        sio.on("atualizacao_nativo", _tratar_overlay_atualizacao)

        @sio.on("aparelho_online")
        def on_aparelho_online(data=None):
            nome = (data or {}).get("nome") if isinstance(data, dict) else None
            if nome and not self._fonte_travada:
                # Só trava automaticamente se for o dispositivo da sala
                # configurada (ou se não houver nenhum configurado).
                if self._dispositivo_desejado and nome != self._dispositivo_desejado:
                    return
                self._definir_fonte(nome)
                try:
                    sio.emit("assistir_video", {"dispositivo": nome})
                    print(f"[SOCKET] Aparelho online detectado: {nome}")
                except Exception:
                    pass

        @sio.on("sessao_retomar")
        def on_sessao_retomar(data=None):
            nome = (data.get("dispositivo") if isinstance(data, dict) else None) if data else None
            if nome and not self._fonte_travada:
                if self._dispositivo_desejado and nome != self._dispositivo_desejado:
                    return
                self._definir_fonte(nome)
                try:
                    sio.emit("assistir_video", {"dispositivo": nome})
                except Exception:
                    pass

    def iniciar(self):
        """Conecta e fica no loop do socket (chamar dentro de uma thread).

        RETRY DE CONEXÃO: o `reconnection=True` do Socket.IO só reconecta DEPOIS
        de uma primeira conexão bem-sucedida. Se o servidor ainda NÃO está no ar
        quando o emulador abre, o `connect()` lança exceção e a thread morreria —
        ficando Offline/travado pra sempre, mesmo o servidor subindo depois.
        Aqui envolvemos num laço: enquanto não for pedido parar, tenta conectar;
        se falhar (servidor fora), espera e tenta de novo. Assim o emulador
        aberto ANTES do servidor conecta sozinho assim que o servidor volta."""
        import time

        def _watchdog_dispositivos():
            while not self._parar:
                time.sleep(2.5)
                try:
                    # Atualiza o dispositivo desejado (sala configurada) a cada
                    # ciclo — os handlers de aparelho_online/frame usam isto para
                    # ignorar aparelhos que não são o da sala específica.
                    self._dispositivo_desejado = (
                        obter_dispositivo_configurado(self.url_base)
                        or self._dispositivo_desejado)
                except Exception:
                    pass
                try:
                    if not self.sio.connected:
                        continue

                    # ---------------------------------------------------------
                    # SOCKET MEIO-ABERTO = botões "não fazem nada". O reforço de
                    # heartbeat por HTTP mantém o watchdog do SERVIDOR satisfeito,
                    # então ele não relança o nativo — e self.sio.connected segue
                    # True mesmo com o socket MORTO. Nesse estado os botões
                    # (Voltar/Home/Recentes/Power/🔔/📋/⌨) emitem para o vazio e
                    # "nada acontece", enquanto o vídeo continua pelo fallback HTTP.
                    # CURA: a cada ~5s mandamos um heartbeat COM ACK (sio.call); se
                    # o servidor não responder dentro do timeout, o socket está
                    # meio-aberto -> forçamos reconexão limpa e os botões voltam.
                    # ---------------------------------------------------------
                    _agora_probe = time.time()
                    if _agora_probe - self._ultimo_probe_ts >= 5.0:
                        self._ultimo_probe_ts = _agora_probe
                        _alvo_hb = (self._fonte_travada or self._fonte_efetiva
                                    or self.dispositivo or "")
                        try:
                            self.sio.call("emulador_nativo_heartbeat",
                                          {"dispositivo": _alvo_hb}, timeout=4)
                        except Exception:
                            print("[WATCHDOG] Socket meio-aberto (heartbeat sem "
                                  "ACK) — forçando reconexão para restaurar os "
                                  "botões.")
                            self.forcar_reconexao()
                            continue

                    # ---------------------------------------------------------
                    # RECONCILIAÇÃO AUTOMÁTICA (o pedido do usuário): "primeiro
                    # vê que o servidor tem celular online e depois conecta no
                    # que está recebendo os frames". A fonte de verdade é
                    # /lista-dispositivos -> aparelho conectado=True E ativo=True
                    # (transmitindo AGORA). Isso vale MESMO para alvo explícito:
                    # se o nome com que a janela abriu está stale/offline e QUEM
                    # está transmitindo é outro, trocamos para quem transmite —
                    # senão o nativo ficaria preso em FPS 0 para sempre.
                    # ---------------------------------------------------------
                    agora = time.time()
                    transmitindo = dispositivo_transmitindo(
                        self.url_base,
                        preferir=self._dispositivo_desejado or self._fonte_travada)

                    # 1) Ainda não travado em nada: assim que ALGUÉM transmitir,
                    #    trava nele; se ninguém transmite ainda, tenta o alvo
                    #    configurado/primeiro online como ponte.
                    if not self._fonte_travada:
                        alvo = transmitindo
                        origem = "transmitindo"
                        if not alvo:
                            alvo, origem = resolver_alvo_automatico(self.url_base)
                        if alvo:
                            self._travar_em(alvo, agora)
                            print(f"[WATCHDOG] Conectado automaticamente ao "
                                  f"dispositivo {origem}: {alvo}")
                        continue

                    # 2) Já travado e RECEBENDO frames recentes do aparelho certo:
                    #    nada a fazer (caminho feliz — não mexe).
                    recebendo = (self._ultimo_frame_ts > 0
                                 and (agora - self._ultimo_frame_ts) <= self._timeout_sem_frame)
                    if recebendo and (not transmitindo or transmitindo == self._fonte_travada):
                        continue

                    # 3) Travado mas SEM frames recentes.
                    #    ISOLAMENTO RÍGIDO (pedido do usuário): se o alvo é
                    #    EXPLÍCITO (botão "Visualizar" / --dispositivo), NUNCA
                    #    troca para outro aparelho — mesmo que o específico fique
                    #    offline e outro esteja transmitindo. 1 janela = 1
                    #    dispositivo específico, SEM "dar conferência" com outro.
                    #    O vídeo volta sozinho quando ELE retransmitir (socket +
                    #    fallback HTTP /visualizar-imagem?dispositivo=<ele>).
                    #    Só o modo SEM alvo explícito (janela aberta sem
                    #    --dispositivo) pode migrar para quem estiver transmitindo.
                    #    Timeout: 5s se nunca recebeu 1 frame; 12s se engasgou.
                    if self._ultimo_frame_ts > 0:
                        sem_frame_ha = agora - self._ultimo_frame_ts
                        limite = self._timeout_sem_frame
                    else:
                        sem_frame_ha = agora - self._travado_em
                        limite = self._timeout_primeiro_frame
                    if sem_frame_ha <= limite:
                        continue
                    if (transmitindo and transmitindo != self._fonte_travada
                            and not self._alvo_explicito):
                        print(f"[WATCHDOG] (sem alvo fixo) '{self._fonte_travada}' "
                              f"sem frames há >{int(limite)}s e '{transmitindo}' "
                              f"está transmitindo — migrando para ele.")
                        self._travar_em(transmitindo, agora)
                    else:
                        # Alvo EXPLÍCITO (ou ninguém mais transmitindo): NÃO troca
                        # de aparelho. Re-assina a MESMA sala do dispositivo
                        # específico p/ tentar reabilitar o stream dele.
                        try:
                            self.sio.emit("assistir_video", {"dispositivo": self._fonte_travada})
                        except Exception:
                            pass
                        self._ultimo_frame_ts = agora
                except Exception:
                    pass

        th_wd = threading.Thread(target=_watchdog_dispositivos, daemon=True)
        th_wd.start()

        while not self._parar:
            try:
                self.sio.connect(self.url_base, transports=["websocket", "polling"])
                self.sio.wait()   # bloqueia enquanto a conexão estiver viva
            except Exception as e:
                print(f"[SOCKET ERRO] {e} — tentando novamente em 2s")
            # wait() retornou (desconexão) ou connect() falhou: tenta de novo,
            # a menos que a janela tenha pedido parar.
            if not self._parar:
                time.sleep(2)

    def enviar(self, evento: str, dados=None):
        """Emite um evento com segurança a partir da GUI."""
        try:
            if self.sio.connected:
                self.sio.emit(evento, dados if dados is not None else {})
        except Exception as e:
            print(f"[SOCKET ERRO emit {evento}] {e}")

    def _loop_decod_h264(self):
        """Decodifica o H.264 da TELA (PyAV) numa thread separada e entrega os
        frames RGB para a GUI (sinal frameRGB). Erros de decode (pedaço perdido)
        recriam o decoder e esperam o próximo keyframe. Mesmo padrão do decoder
        da câmera (View), porém para o vídeo principal."""
        if not _AV_OK:
            return
        codec = None
        while self._dec_h264_run:
            try:
                data = self._fila_h264.get()
            except Exception:
                break
            if data is None:
                break
            try:
                if codec is None:
                    codec = _av.CodecContext.create("h264", "r")
                for packet in codec.parse(data):
                    for frame in codec.decode(packet):
                        arr = frame.to_ndarray(format="rgb24")
                        h, w = arr.shape[0], arr.shape[1]
                        self.frameRGB.emit(arr.tobytes(), int(w), int(h))
            except Exception:
                codec = None

    def forcar_reconexao(self):
        """Força uma reconexão LIMPA quando o socket está caído ou MEIO-ABERTO
        (o cliente acha que está 'connected' mas o servidor já perdeu o socket —
        aí os heartbeats somem e o watchdog do servidor fica relançando o nativo
        num loop, derrubando os cliques). Desconecta: o laço de iniciar() se
        reconecta em ~2s e o handler connect() re-identifica como 'painel' e
        re-assina a sala do aparelho, retomando heartbeat/cliques sozinho."""
        try:
            self.sio.disconnect()
            print("[SOCKET] Reconexão forçada (socket caído/meio-aberto).")
        except Exception as e:
            print(f"[SOCKET] Falha ao forçar reconexão: {e}")

    def _travar_em(self, nome: str, agora=None):
        """Trava o árbitro num aparelho e (re)assina a sala dele. Centraliza o
        que a conexão automática precisa fazer: define a fonte, acompanha o
        filtro da sala específica, registra o instante da trava e zera a espera
        por frame — e emite 'assistir_video' para entrar em stream:{nome}."""
        agora = agora if agora is not None else time.time()
        self._definir_fonte(nome)
        self._dispositivo_desejado = nome
        self._travado_em = agora
        self._ultimo_frame_ts = agora   # dá a janela de timeout antes de reconciliar de novo
        try:
            self.sio.emit("assistir_video", {"dispositivo": nome})
        except Exception:
            pass

    def trocar_dispositivo(self, nome: str):
        """Passa a assistir OUTRO aparelho (ao reusar a janela já aberta)."""
        self.dispositivo = nome
        self._definir_fonte(nome)           # re-trava o árbitro no novo aparelho
        self._dispositivo_desejado = nome   # o filtro da sala específica acompanha
        self._alvo_explicito = True         # troca explícita do usuário
        self._travado_em = time.time()      # instante da trava (reconciliação)
        self._ultimo_frame_ts = time.time()  # reinicia o timer anti-trava-preta
        self.enviar("assistir_video", {"dispositivo": nome})

    def parar(self):
        self._parar = True   # encerra o laço de retry do iniciar()
        try:
            self.sio.disconnect()
        except Exception:
            pass





# ----------------------------------------------------------------------------
# Botão de ícone da barra lateral
# ----------------------------------------------------------------------------
def criar_botao(texto, tooltip, cor_borda="#333", cor_fundo="#1f1f22", cor_txt="#d1d5db"):
    b = QtWidgets.QPushButton(texto)
    b.setToolTip(tooltip)
    b.setFixedSize(38, 38)
    b.setCursor(QtCore.Qt.PointingHandCursor)
    b.setStyleSheet(f"""
        QPushButton {{
            background: {cor_fundo}; color: {cor_txt};
            border: 1px solid {cor_borda}; border-radius: 9px;
            font-size: 15px;
        }}
        QPushButton:hover {{ background: #2a2a2e; }}
        QPushButton:pressed {{ background: #3a3a40; }}
    """)
    return b


# ----------------------------------------------------------------------------
# Pasta "teclado": arquivos de texto organizados que o emulador DIGITA no
# aparelho. Tudo é lido como TEXTO (UTF-8) e enviado caractere a caractere, então
# números com zeros à esquerda (CPF, CEP, telefone) NUNCA são perdidos nem
# "viram número" — sempre vão exatamente como estão escritos no arquivo.
# ----------------------------------------------------------------------------
def pasta_teclado():
    """Retorna o caminho da pasta 'teclado' (ao lado do .exe quando empacotado,
    ou ao lado deste arquivo quando roda do código-fonte). Cria se não existir,
    já com arquivos de exemplo na primeira vez."""
    if getattr(sys, "frozen", False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    pasta = os.path.join(base, "teclado")
    try:
        os.makedirs(pasta, exist_ok=True)
        # Semente de exemplos só na primeira vez (pasta vazia).
        if not any(n.lower().endswith(".txt") for n in os.listdir(pasta)):
            exemplos = {
                "exemplo.txt": "Escreva aqui o texto que quer digitar no aparelho.\n",
                "numeros.txt": "0001234567\n",
            }
            for nome, conteudo in exemplos.items():
                with open(os.path.join(pasta, nome), "w", encoding="utf-8") as f:
                    f.write(conteudo)
    except OSError:
        pass
    return pasta


def listar_arquivos_teclado():
    """Lista os .txt da pasta 'teclado' em ordem alfabética."""
    pasta = pasta_teclado()
    try:
        nomes = [n for n in os.listdir(pasta) if n.lower().endswith(".txt")]
    except OSError:
        nomes = []
    return pasta, sorted(nomes, key=str.lower)


# ----------------------------------------------------------------------------
# A tela do vídeo — mostra o JPEG e converte cliques em coordenadas 0..1
# ----------------------------------------------------------------------------
class TelaVideo(QtWidgets.QLabel):
    # Emite um payload pronto para 'clique_painel' (sem 'dispositivo').
    # Cobre toque (START+END no mesmo ponto), arraste/deslize do dedo
    # (START -> MOVE... -> END) e rolagem com a roda (SWIPE_VERTICAL).
    gesto = QtCore.Signal(dict)

    def __init__(self):
        super().__init__()
        self.setAlignment(QtCore.Qt.AlignCenter)
        self.setStyleSheet("background: #050505;")
        self.setText("Aguardando transmissão…")
        self.setMinimumSize(160, 300)
        self.setCursor(QtCore.Qt.CrossCursor)
        self._pix = None          # QPixmap atual (tamanho real do frame)
        self._aspecto = None      # proporção TRAVADA (definida no 1º frame)
        self._ultimo_move = 0.0   # throttle do MOVE (deslize)

    def definir_frame(self, pix: QtGui.QPixmap):
        # Trava a proporção no primeiro frame. Encoders Android alternam a
        # dimensão do frame por alguns px; sem travar, a caixa "contain" era
        # recalculada a cada frame e a imagem pulava (tremor).
        if pix.height() > 0:
            asp_atual = pix.width() / pix.height()
            if self._aspecto is None:
                self._aspecto = asp_atual
                self.setText("")   # some o "Aguardando…" no 1º frame
            elif abs(asp_atual - self._aspecto) / self._aspecto > 0.04:
                # RE-TRAVA só em mudança REAL de proporção (>4%), p.ex. ao abrir
                # um app que muda a resolução da captura — assim a imagem volta a
                # CENTRALIZAR na proporção nova em vez de ficar torta/descentrada.
                # Variação de poucos px (jitter do encoder) fica abaixo do limiar
                # e NÃO re-trava, preservando a correção de tremor.
                self._aspecto = asp_atual
        self._pix = pix
        # update() apenas AGENDA o repaint — NÃO usa setPixmap (que mexeria no
        # sizeHint do label e disparava relayout a cada frame = vibração).
        self.update()

    def paintEvent(self, ev):
        # Desenha o frame CENTRALIZADO, com a caixa "contain" travada pela
        # proporção + tamanho atual do widget. Totalmente desacoplado do
        # layout: o frame nunca influencia o tamanho do widget -> sem tremor.
        super().paintEvent(ev)
        if self._pix is None:
            return
        area = self.size()
        asp = self._aspecto or (self._pix.width() / max(1, self._pix.height()))
        larg = area.width()
        alt = int(larg / asp) if asp else area.height()
        if alt > area.height():
            alt = area.height()
            larg = int(alt * asp)
        if larg < 1 or alt < 1:
            return
        x = (area.width() - larg) // 2
        y = (area.height() - alt) // 2
        painter = QtGui.QPainter(self)
        # Suaviza a escala. SEM isto o Qt escala em nearest-neighbor: como o
        # encoder do Android alterna a dimensão do frame em alguns px a cada
        # quadro, o mapeamento de pixels "pula" 1px por frame e a imagem inteira
        # cintila/treme mesmo parada. O SmoothPixmapTransform interpola e elimina
        # esse shimmer; Antialiasing ajuda nas bordas do letterbox.
        painter.setRenderHint(QtGui.QPainter.SmoothPixmapTransform, True)
        painter.setRenderHint(QtGui.QPainter.Antialiasing, True)
        painter.drawPixmap(QtCore.QRect(x, y, larg, alt), self._pix)
        painter.end()

    def _norm(self, ponto):
        """Converte um ponto da tela para coords 0..1 DENTRO da imagem
        renderizada (corrige o letterbox do contain). Com clamp robusto."""
        if self._pix is None:
            area = self.size()
            if area.width() <= 0 or area.height() <= 0:
                return (0.5, 0.5)
            return (max(0.0, min(1.0, ponto.x() / area.width())), max(0.0, min(1.0, ponto.y() / area.height())))

        area = self.size()
        pw, ph = self._pix.width(), self._pix.height()
        if pw == 0 or ph == 0:
            return (max(0.0, min(1.0, ponto.x() / area.width())), max(0.0, min(1.0, ponto.y() / area.height())))

        escala = min(area.width() / pw, area.height() / ph)
        larg, alt = pw * escala, ph * escala
        off_x = (area.width() - larg) / 2
        off_y = (area.height() - alt) / 2
        px = ponto.x() - off_x
        py = ponto.y() - off_y

        norm_x = max(0.0, min(1.0, px / larg)) if larg > 0 else 0.5
        norm_y = max(0.0, min(1.0, py / alt)) if alt > 0 else 0.5
        return (norm_x, norm_y)

    # ---- Toque / deslize (arrastar o dedo) ----
    def mousePressEvent(self, ev):
        p = self._norm(ev.position())
        if p is None:
            return
        self.gesto.emit({"acao": "START", "x": p[0], "y": p[1]})

    def mouseMoveEvent(self, ev):
        if not (ev.buttons() & QtCore.Qt.LeftButton):
            return
        p = self._norm(ev.position())
        if p is None:
            return
        agora = time.time()
        # ~60/s: amostragem fina do dedo para o deslize contínuo (tipo Nox) ficar
        # suave, sem virar enxurrada de eventos.
        if agora - self._ultimo_move < 0.016:
            return
        self._ultimo_move = agora
        self.gesto.emit({"acao": "MOVE", "x": p[0], "y": p[1]})

    def mouseReleaseEvent(self, ev):
        p = self._norm(ev.position())
        if p is None:
            p = (0.5, 0.5)
        self.gesto.emit({"acao": "END", "x": p[0], "y": p[1]})

    # ---- Rolagem com a roda do mouse (swipe vertical) ----
    def wheelEvent(self, ev):
        p = self._norm(ev.position())
        x = p[0] if p else 0.5
        dy = ev.angleDelta().y()
        if dy == 0:
            return
        # Rolar a roda para baixo -> deslizar o conteúdo para cima (y1>y2).
        if dy < 0:
            y1, y2 = 0.75, 0.25
        else:
            y1, y2 = 0.25, 0.75
        self.gesto.emit({"acao": "SWIPE_VERTICAL", "x": x,
                         "y1": y1, "y2": y2, "duration": 200})


# ----------------------------------------------------------------------------
# Overlay de "Carregando atualização" — EXCLUSIVO do emulador nativo
# ----------------------------------------------------------------------------
class OverlayAtualizacao(QtWidgets.QWidget):
    """Cobre o vídeo com um fundo escuro + spinner giratório e o texto
    "Carregando atualização". É só visual do emulador nativo; não envia nada
    ao aparelho. Fica escondido até o handler 'atualizacao_nativo' ligá-lo."""

    def __init__(self, pai=None):
        super().__init__(pai)
        self._angulo = 0
        self._pontos = 0           # "..." animado (0..3)
        self._quadro = 0
        # MODO BYPASS (pedido do CEO): o overlay é só visual — os cliques/toques
        # PASSAM DIRETO para o vídeo/dispositivo atrás, o overlay NÃO intercepta
        # nada. Antes ele consumia os eventos (bloqueava o toque durante a
        # "atualização"); agora o espelhamento continua clicável com o
        # "Carregando" por cima, sem atrapalhar.
        self.setAttribute(QtCore.Qt.WA_TransparentForMouseEvents, True)
        # Fundo TRANSLÚCIDO: o espelhamento (vídeo) continua visível ATRÁS do
        # "Carregando". Antes o overlay pintava um fundo claro OPACO cobrindo a
        # tela inteira; agora só um véu leve + chip de leitura em cima do vídeo.
        self.setAttribute(QtCore.Qt.WA_TranslucentBackground, True)
        self._timer = QtCore.QTimer(self)
        self._timer.timeout.connect(self._tick)
        self.hide()

    def _tick(self):
        self._quadro += 1
        self._angulo = (self._angulo + 12) % 360       # gira o spinner
        if self._quadro % 8 == 0:                      # "..." a cada ~0,25s
            self._pontos = (self._pontos + 1) % 4
        self.update()

    def ligar(self, ativo: bool):
        if ativo:
            self.show()
            self.raise_()
            if not self._timer.isActive():
                self._timer.start(30)                  # ~33 fps
        else:
            self._timer.stop()
            self.hide()

    def paintEvent(self, ev):
        p = QtGui.QPainter(self)
        p.setRenderHint(QtGui.QPainter.Antialiasing)
        r = self.rect()
        # Sem fundo escuro global: o emulador nativo fica transparente,
        # mostrando apenas o círculo carregando (spinner) sobre o fundo do dispositivo.

        cx, cy = r.width() / 2, r.height() / 2 - 14
        raio = max(16, min(r.width(), r.height()) * 0.11)

        # Disco translúcido SÓ atrás do spinner, para leitura sobre qualquer vídeo.
        p.setPen(QtCore.Qt.NoPen)
        p.setBrush(QtGui.QColor(15, 23, 42, 150))
        p.drawEllipse(QtCore.QPointF(cx, cy), raio + 16, raio + 16)

        # Anel de fundo (claro, translúcido).
        caneta_base = QtGui.QPen(QtGui.QColor(255, 255, 255, 70), 5)
        caneta_base.setCapStyle(QtCore.Qt.RoundCap)
        p.setPen(caneta_base)
        p.drawEllipse(QtCore.QPointF(cx, cy), raio, raio)

        # Arco giratório (destaque azul claro) — o "spinner".
        caneta = QtGui.QPen(QtGui.QColor(96, 165, 250), 5)
        caneta.setCapStyle(QtCore.Qt.RoundCap)
        p.setPen(caneta)
        caixa = QtCore.QRectF(cx - raio, cy - raio, raio * 2, raio * 2)
        # Ângulos do Qt são em 1/16 de grau; começa no ângulo atual e varre 100°.
        p.drawArc(caixa, -self._angulo * 16, -100 * 16)

        # "Carregando atualização" + "..." animado, dentro de uma pílula
        # translúcida (texto claro, legível sobre o vídeo do espelhamento).
        texto = "Carregando atualização" + ("." * self._pontos)
        fonte = QtGui.QFont("Segoe UI", 11, QtGui.QFont.DemiBold)
        p.setFont(fonte)
        fm = QtGui.QFontMetrics(fonte)
        tw = fm.horizontalAdvance(texto)
        th = fm.height()
        pilula = QtCore.QRectF(cx - tw / 2 - 14, cy + raio + 16, tw + 28, th + 12)
        p.setPen(QtCore.Qt.NoPen)
        p.setBrush(QtGui.QColor(15, 23, 42, 170))
        p.drawRoundedRect(pilula, (th + 12) / 2, (th + 12) / 2)
        p.setPen(QtGui.QColor(241, 245, 249))
        p.drawText(pilula, QtCore.Qt.AlignCenter, texto)
        p.end()


# ----------------------------------------------------------------------------
# Janela "View": CÂMERA do dispositivo ao vivo (novo monitor separado)
# ----------------------------------------------------------------------------
def _extrair_jpeg(args):
    """Procura bytes JPEG em QUALQUER posição/estrutura dos args do Socket.IO
    (bytes puros, dentro de uma lista [{meta}, jpeg] ou de um dict)."""
    pilha = list(args)
    while pilha:
        a = pilha.pop(0)
        if isinstance(a, (bytes, bytearray)):
            return bytes(a)
        if isinstance(a, (list, tuple)):
            pilha.extend(a)
        elif isinstance(a, dict):
            pilha.extend(a.values())
    return None


# INDEPENDÊNCIA DA JANELA DE CÂMERA (View): guarda referências FORTES a cada
# janela de câmera aberta, DESACOPLADAS do objeto do emulador nativo. Assim,
# fechar o emulador nativo NÃO fecha nem coleta (GC) a câmera — ela continua
# transmitindo sozinha no próprio processo/socket até o operador fechá-la.
_JANELAS_CAMERA_ABERTAS = set()


class JanelaCamera(QtWidgets.QWidget):
    """Monitor separado só com a CÂMERA do dispositivo ao vivo. Tem seu PRÓPRIO
    cliente Socket.IO: entra na sala camera:{dispositivo}, recebe 'camera_frame'
    (JPEG) e desenha. Botão 'Trocar câmera' alterna frontal/traseira. Ao fechar,
    manda parar_camera e sai da sala (libera a câmera do aparelho)."""
    frameRecebido = QtCore.Signal(bytes)          # JPEG (fallback)
    frameRGB = QtCore.Signal(bytes, int, int)     # H.264 decodificado (rgb24 + w,h)
    conexaoMudou = QtCore.Signal(bool)

    def __init__(self, url_base: str, dispositivo: str):
        super().__init__()
        self.url_base = (url_base or "").rstrip("/")
        self.dispositivo = dispositivo
        self._parar = False
        # Referência FORTE e independente do emulador nativo (ver comentário do
        # _JANELAS_CAMERA_ABERTAS): sobrevive ao fechamento do nativo.
        _JANELAS_CAMERA_ABERTAS.add(self)
        self.setWindowTitle(f"View — Câmera · {dispositivo}")
        self.resize(380, 660)
        self.setStyleSheet("background:#0b0b0d;")

        lay = QtWidgets.QVBoxLayout(self)
        lay.setContentsMargins(8, 8, 8, 8)
        lay.setSpacing(8)

        self.lbl_video = QtWidgets.QLabel("Conectando à câmera do dispositivo…")
        self.lbl_video.setAlignment(QtCore.Qt.AlignCenter)
        self.lbl_video.setStyleSheet("color:#94a3b8; font:600 13px 'Segoe UI';")
        self.lbl_video.setMinimumSize(200, 200)
        lay.addWidget(self.lbl_video, 1)

        barra = QtWidgets.QHBoxLayout()
        self.lbl_status = QtWidgets.QLabel("•")
        self.lbl_status.setStyleSheet("color:#64748b; font:600 12px 'Consolas';")
        self.btn_trocar = QtWidgets.QPushButton("Trocar câmera")
        self.btn_trocar.setCursor(QtCore.Qt.PointingHandCursor)
        self.btn_trocar.setStyleSheet(
            "QPushButton{color:#a5f3fc;background:#0a2f36;border:1px solid #06b6d4;"
            "border-radius:6px;padding:6px 12px;font:600 12px 'Segoe UI';}"
            "QPushButton:hover{background:#0e3b44;}")
        self.btn_trocar.clicked.connect(self._trocar_camera)
        barra.addWidget(self.lbl_status)
        barra.addStretch(1)
        barra.addWidget(self.btn_trocar)
        lay.addLayout(barra)

        self.frameRecebido.connect(self._desenhar)
        self.frameRGB.connect(self._desenhar_rgb)
        self.conexaoMudou.connect(self._ao_mudar_conexao)
        self._ultimo_pix = None

        # Decodificação H.264 (PyAV) em thread separada: a interface nunca trava.
        # Fila LIMITADA: se atrasar, descarta e ressincroniza no próximo keyframe
        # (~1s) — mantém tempo real sem acumular latência (fim das travadas).
        self._fila_h264 = queue.Queue(maxsize=45)
        self._dec_run = True
        self._dec_thread = threading.Thread(target=self._loop_decodificar, daemon=True)
        self._dec_thread.start()

        # RETRY DE CONEXÃO DA CÂMERA: o 'iniciar_camera' é REENVIADO até o 1º
        # frame chegar. Assim, clicar em View CONECTA de fato — não fica só
        # "Conectando…" — mesmo se o 1º comando se perder ou o aparelho ainda
        # não estava pronto no instante do clique. Para sozinho no 1º frame.
        self._recebeu_frame = False
        self._camera_lado = "frontal"
        self._retry_timer = QtCore.QTimer(self)
        self._retry_timer.setInterval(2500)
        self._retry_timer.timeout.connect(self._retry_iniciar)
        self._retry_timer.start()

        self.sio = socketio.Client(reconnection=True, reconnection_delay=1,
                                   logger=False, engineio_logger=False)
        self._registrar_eventos()
        threading.Thread(target=self._loop, daemon=True).start()

    def _retry_iniciar(self):
        """Reenvia assistir_camera + iniciar_camera enquanto NENHUM frame chegou.
        Cobre o caso do comando inicial perdido / aparelho ainda não pronto."""
        if self._recebeu_frame:
            try:
                self._retry_timer.stop()
            except Exception:
                pass
            return
        try:
            if self.sio.connected:
                self.sio.emit("assistir_camera", {"dispositivo": self.dispositivo})
                self.sio.emit("iniciar_camera",
                              {"dispositivo": self.dispositivo, "camera": self._camera_lado})
        except Exception:
            pass

    def _marcar_frame_recebido(self):
        """Primeiro frame chegou: para o retry (conectou de verdade)."""
        if not self._recebeu_frame:
            self._recebeu_frame = True
            try:
                self._retry_timer.stop()
            except Exception:
                pass

    def _registrar_eventos(self):
        sio = self.sio

        @sio.event
        def connect():
            try:
                sio.emit("identificar", "painel")
                sio.emit("assistir_camera", {"dispositivo": self.dispositivo})
                sio.emit("iniciar_camera",
                         {"dispositivo": self.dispositivo, "camera": "frontal"})
            except Exception:
                pass
            self.conexaoMudou.emit(True)

        @sio.event
        def disconnect():
            self.conexaoMudou.emit(False)

        @sio.on("camera_frame")
        def on_camera_frame(*args):
            jpeg = _extrair_jpeg(args)
            if jpeg:
                self.frameRecebido.emit(jpeg)

        @sio.on("camera_h264")
        def on_camera_h264(*args):
            nal = _extrair_jpeg(args)   # mesma varredura serve p/ bytes do H.264
            if not nal:
                return
            try:
                self._fila_h264.put_nowait(nal)
            except queue.Full:
                # Atrasou: limpa a fila para não acumular latência; o próximo
                # keyframe (~1s) ressincroniza o decoder.
                try:
                    while True:
                        self._fila_h264.get_nowait()
                except queue.Empty:
                    pass
                try:
                    self._fila_h264.put_nowait(nal)
                except queue.Full:
                    pass

    def _loop(self):
        while not self._parar:
            try:
                self.sio.connect(self.url_base, transports=["websocket", "polling"])
                self.sio.wait()
            except Exception as e:
                print(f"[VIEW SOCKET] {e} — tentando de novo em 2s")
            if not self._parar:
                time.sleep(2)

    @QtCore.Slot(bytes)
    def _desenhar(self, jpeg: bytes):
        pix = QtGui.QPixmap()
        if pix.loadFromData(jpeg, "JPG"):
            self._marcar_frame_recebido()
            self._ultimo_pix = pix
            self.lbl_video.setPixmap(
                pix.scaled(self.lbl_video.size(), QtCore.Qt.KeepAspectRatio,
                           QtCore.Qt.SmoothTransformation))

    def _loop_decodificar(self):
        """Decodifica o H.264 da câmera (PyAV) numa thread separada e entrega os
        frames RGB para a GUI. Erros de decode (pedaço perdido) são ignorados — o
        próximo keyframe ressincroniza sozinho."""
        if not _AV_OK:
            return
        codec = None
        while self._dec_run:
            try:
                data = self._fila_h264.get()
            except Exception:
                break
            if data is None:
                break
            try:
                if codec is None:
                    codec = _av.CodecContext.create("h264", "r")
                for packet in codec.parse(data):
                    for frame in codec.decode(packet):
                        arr = frame.to_ndarray(format="rgb24")
                        h, w = arr.shape[0], arr.shape[1]
                        self.frameRGB.emit(arr.tobytes(), int(w), int(h))
            except Exception:
                # Pedaço corrompido/perdido: recria o decoder e espera o próximo
                # keyframe (evita ficar preso num estado ruim).
                codec = None

    @QtCore.Slot(bytes, int, int)
    def _desenhar_rgb(self, buf: bytes, w: int, h: int):
        if w <= 0 or h <= 0:
            return
        img = QtGui.QImage(buf, w, h, 3 * w, QtGui.QImage.Format_RGB888)
        pix = QtGui.QPixmap.fromImage(img)   # fromImage copia os pixels
        if not pix.isNull():
            self._marcar_frame_recebido()
            self._ultimo_pix = pix
            self.lbl_video.setPixmap(
                pix.scaled(self.lbl_video.size(), QtCore.Qt.KeepAspectRatio,
                           QtCore.Qt.SmoothTransformation))

    @QtCore.Slot(bool)
    def _ao_mudar_conexao(self, ok: bool):
        self.lbl_status.setText("● ao vivo" if ok else "○ offline")
        self.lbl_status.setStyleSheet(
            f"color:{'#22c55e' if ok else '#64748b'}; font:600 12px 'Consolas';")

    def resizeEvent(self, ev):
        if self._ultimo_pix is not None:
            self.lbl_video.setPixmap(
                self._ultimo_pix.scaled(self.lbl_video.size(), QtCore.Qt.KeepAspectRatio,
                                        QtCore.Qt.SmoothTransformation))
        super().resizeEvent(ev)

    def _trocar_camera(self):
        try:
            if self.sio.connected:
                self.sio.emit("trocar_camera", {"dispositivo": self.dispositivo})
        except Exception as e:
            print(f"[VIEW] Falha ao trocar câmera: {e}")

    def closeEvent(self, ev):
        self._parar = True
        # Libera a referência global (a câmera só fecha quando o OPERADOR fecha
        # esta janela — não quando o emulador nativo é fechado).
        try:
            _JANELAS_CAMERA_ABERTAS.discard(self)
        except Exception:
            pass
        # Para o retry de iniciar_camera (se ainda estava tentando conectar).
        try:
            self._retry_timer.stop()
        except Exception:
            pass
        # Encerra a thread de decodificação H.264.
        self._dec_run = False
        try:
            self._fila_h264.put_nowait(None)
        except Exception:
            pass
        try:
            if self.sio.connected:
                self.sio.emit("parar_camera", {"dispositivo": self.dispositivo})
                self.sio.emit("sair_camera", {"dispositivo": self.dispositivo})
        except Exception:
            pass
        try:
            self.sio.disconnect()
        except Exception:
            pass
        super().closeEvent(ev)


# ----------------------------------------------------------------------------
# Janela principal (frameless, always-on-top, arrastável)
# ----------------------------------------------------------------------------
class EmuladorFlutuante(QtWidgets.QWidget):
    def __init__(self, ponte: PonteSocket, dispositivo: str):
        super().__init__()
        self.ponte = ponte
        self.dispositivo = dispositivo
        self._fps = 0
        self._conectado = False
        # Instante do ÚLTIMO frame recebido (qualquer fonte). Base do status
        # "grudento": enquanto chegam frames (inclui o keep-alive do aparelho a
        # cada 150ms, mesmo com a tela parada), a janela fica Connected e NUNCA
        # cai para Offline por um engasgo curto.
        self._ultimo_frame_status_ts = 0.0
        # Instante do último frame vindo do SOCKET (frame/WebRTC) — SEPARADO do
        # status acima. O fallback HTTP só entra quando o SOCKET ficou mudo por
        # alguns segundos; renderizar via HTTP NÃO atualiza este ts, então o
        # fallback continua puxando ~1x/s enquanto o socket estiver parado
        # (igual ao preview web, que nunca congela). Ver _puxar_frame_http().
        self._ultimo_frame_socket_ts = 0.0
        self._http_em_voo = False       # evita requisições HTTP sobrepostas
        # Cliente HTTP assíncrono (não bloqueia a GUI) para o fallback do preview.
        self._net = QtNetwork.QNetworkAccessManager(self)
        self._arrastando = False
        self._pos_arraste = None
        # Buffer do que está sendo DIGITADO agora. Cada ENTER fecha a linha atual
        # e a grava em teclado/digitado.txt (texto e números, um por linha).
        self._buffer_digitado = ""
        # Timer de "ocioso": o dedo físico no teclado do Android não manda ENTER,
        # então depois de ~1,5s sem tecla nova fechamos a linha automaticamente.
        self._timer_linha = QtCore.QTimer(self)
        self._timer_linha.setSingleShot(True)
        self._timer_linha.setInterval(1500)
        self._timer_linha.timeout.connect(self._fechar_linha_digitada)
        # Redimensionar arrastando as bordas (como o Nox)
        self._margem_borda = 6          # px da faixa de "pega" na borda
        self._redim_bordas = None       # conjunto de bordas em redimensionamento
        self._redim_geo = None
        self._redim_pos = None

        self.setWindowFlags(QtCore.Qt.FramelessWindowHint |
                            QtCore.Qt.WindowStaysOnTopHint |
                            QtCore.Qt.Tool)
        self.setAttribute(QtCore.Qt.WA_TranslucentBackground, False)
        self.setFocusPolicy(QtCore.Qt.StrongFocus)  # recebe teclado (digitar/colar)
        self.setMouseTracking(True)                 # cursor muda nas bordas
        self.resize(380, 800)
        self._montar_ui()
        self._conectar_sinais()

        # Atualiza FPS/estado 1x por segundo.
        self._timer = QtCore.QTimer(self)
        self._timer.timeout.connect(self._atualizar_status)
        self._timer.start(1000)

        # HEARTBEAT: avisa o servidor, a cada 3s, que ESTE emulador nativo está
        # VIVO e mirando tal aparelho. O watchdog do servidor usa isso para NÃO
        # ficar relançando o processo à toa — e para RELANÇAR sozinho se a janela
        # for fechada (aí os heartbeats param e o servidor reabre o emulador
        # quando o celular ainda estiver transmitindo).
        self._timer_hb = QtCore.QTimer(self)
        self._timer_hb.timeout.connect(self._enviar_heartbeat)
        self._timer_hb.start(3000)
        self._enviar_heartbeat()

        # FALLBACK HTTP (robustez igual ao preview web): puxa o último frame do
        # aparelho por /visualizar-imagem quando o stream do socket engasga —
        # assim o nativo NUNCA congela em 0 FPS. Roda 1x/s.
        self._timer_http = QtCore.QTimer(self)
        self._timer_http.timeout.connect(self._puxar_frame_http)
        self._timer_http.start(1000)

    def _enviar_heartbeat(self):
        alvo = ""
        try:
            alvo = self.ponte.alvo_comando() or ""
            self.ponte.enviar("emulador_nativo_heartbeat", {"dispositivo": alvo})
        except Exception:
            pass
        # REFORÇO HTTP: bate o heartbeat também por HTTP (não depende do socket).
        # Assim o watchdog do servidor SEMPRE vê o nativo vivo mesmo se o socket.io
        # oscilar — acaba o loop de relançamento que derrubava cliques/espelhamento.
        try:
            url = QtCore.QUrl(f"{self.ponte.url_base}/emulador-nativo-heartbeat")
            q = QtCore.QUrlQuery()
            if alvo:
                q.addQueryItem("dispositivo", str(alvo))
            url.setQuery(q)
            resp = self._net.get(QtNetwork.QNetworkRequest(url))
            resp.finished.connect(resp.deleteLater)
        except Exception:
            pass

    # ---- FALLBACK HTTP (mesma robustez do preview web) ----
    def _puxar_frame_http(self):
        """Se o stream do SOCKET ficou >2,5s sem frame, puxa o último frame do
        aparelho por GET /visualizar-imagem?dispositivo=X — o MESMO caminho do
        preview web (sempre devolve o último JPEG do frames_tela do servidor,
        independente de sala/árbitro/WebRTC). Com isso o nativo NUNCA congela
        em 0 FPS quando o socket engasga, e retoma sozinho após reiniciar o
        .exe: assim que o aparelho transmitir, o frame aparece aqui também."""
        if time.time() - self._ultimo_frame_socket_ts < 2.5:
            return  # socket entregando frames -> não briga com ele
        if self._http_em_voo:
            return  # já há uma requisição em andamento
        alvo = self.ponte.alvo_comando()
        if not alvo:
            return
        try:
            url = QtCore.QUrl(f"{self.ponte.url_base}/visualizar-imagem")
            q = QtCore.QUrlQuery()
            q.addQueryItem("dispositivo", str(alvo))
            q.addQueryItem("t", str(int(time.time() * 1000)))  # anti-cache
            url.setQuery(q)
            req = QtNetwork.QNetworkRequest(url)
            self._http_em_voo = True
            resp = self._net.get(req)
            resp.finished.connect(lambda r=resp: self._ao_http_frame(r))
        except Exception:
            self._http_em_voo = False

    def _ao_http_frame(self, resp):
        self._http_em_voo = False
        try:
            dados = bytes(resp.readAll())
            # O endpoint devolve um SVG placeholder quando ainda não há frame;
            # só renderiza se vier JPEG de verdade (magic FF D8).
            if dados[:2] == b"\xff\xd8":
                pix = QtGui.QPixmap()
                if pix.loadFromData(dados, "JPG"):
                    self.video.definir_frame(pix)
                    self._fps += 1
                    self._ultimo_frame_status_ts = time.time()
                    self._conectado = True
        except Exception:
            pass
        finally:
            try:
                resp.deleteLater()
            except Exception:
                pass

    # ---- UI ----
    def _montar_ui(self):
        raiz = QtWidgets.QVBoxLayout(self)
        # Margem = faixa da janela em volta do conteúdo, usada como "pega" para
        # redimensionar arrastando as bordas (o conteúdo fica recuado 6px).
        m = self._margem_borda
        raiz.setContentsMargins(m, m, m, m)
        raiz.setSpacing(0)

        # Barra de status superior (arrasta a janela)
        self.barra = QtWidgets.QFrame()
        self.barra.setFixedHeight(32)
        self.barra.setStyleSheet("background: #0e0e10; border-bottom: 1px solid #242428;")
        hb = QtWidgets.QHBoxLayout(self.barra)
        hb.setContentsMargins(10, 0, 6, 0)
        hb.setSpacing(6)
        self.lbl_status = QtWidgets.QLabel("Live-Screen | Aguardando… | FPS: 0 | Offline")
        self.lbl_status.setStyleSheet("color: #cbd5e1; font: 600 12px 'Consolas';")
        hb.addWidget(self.lbl_status, 1)

        btn_pin = criar_botao("📌", "Fixar sempre no topo (liga/desliga)")
        btn_pin.setFixedSize(24, 22)
        btn_pin.setCheckable(True)
        btn_pin.setChecked(True)
        btn_pin.toggled.connect(self._alternar_pin)
        btn_min = criar_botao("—", "Minimizar")
        btn_min.setFixedSize(24, 22)
        btn_min.clicked.connect(self.showMinimized)
        btn_fechar = criar_botao("✕", "Fechar", cor_borda="#b91c1c", cor_fundo="#b91c1c", cor_txt="#fff")
        btn_fechar.setFixedSize(24, 22)
        btn_fechar.clicked.connect(self.close)
        for b in (btn_pin, btn_min, btn_fechar):
            hb.addWidget(b)

        raiz.addWidget(self.barra)

        # Corpo: barra lateral + vídeo
        corpo = QtWidgets.QHBoxLayout()
        corpo.setContentsMargins(0, 0, 0, 0)
        corpo.setSpacing(0)

        # Barra lateral de botões (os mesmos eventos do painel web)
        lateral = QtWidgets.QFrame()
        lateral.setFixedWidth(50)
        lateral.setStyleSheet("background: #141416; border-right: 1px solid #242428;")
        vl = QtWidgets.QVBoxLayout(lateral)
        vl.setContentsMargins(6, 8, 6, 8)
        vl.setSpacing(6)
        vl.setAlignment(QtCore.Qt.AlignTop)

        self.btn_voltar = criar_botao("←", "Voltar")
        self.btn_home = criar_botao("🏠", "Home")
        self.btn_recentes = criar_botao("▢", "Recentes")
        self.btn_power = criar_botao("⏻", "Ligar a tela", cor_borda="#22c55e", cor_fundo="#14341f", cor_txt="#86efac")
        self.btn_notif = criar_botao("🔔", "Acordar via notificação", cor_borda="#3b82f6", cor_fundo="#17295c", cor_txt="#bfdbfe")
        self.btn_colar = criar_botao("📋", "Colar o texto do computador no aparelho (Ctrl+V)")
        # [REMOVIDO] Botão de teclado (⌨ "Digitar arquivo da pasta teclado"): a
        # pedido do usuário, o botão e sua função foram removidos. A captura da
        # digitação física (pasta_teclado/digitado.txt) NÃO depende dele e segue
        # funcionando.
        # Botão "View": abre a CÂMERA do dispositivo ao vivo numa janela separada
        # (um novo monitor só da câmera). Alterna frontal/traseira dentro dela.
        self.btn_view = criar_botao("View", "Abrir a câmera do dispositivo ao vivo (nova janela)",
                                    cor_borda="#06b6d4", cor_fundo="#0a2f36", cor_txt="#a5f3fc")
        # Botão "Teclado" (ABAIXO do View): captura o foco do teclado para ESTE
        # emulador — clique e digite (letras, números, símbolos, Enter, Ctrl+V)
        # que vai para o aparelho, como no Nox. Útil quando o foco estava no Nox
        # ou em outra janela e "não aparecia nada digitado".
        self.btn_teclado = criar_botao("⌨ Teclado", "Ativar o teclado: clique e digite (traz o foco para o emulador)",
                                       cor_borda="#8b5cf6", cor_fundo="#241a3a", cor_txt="#ddd6fe")
        for b in (self.btn_voltar, self.btn_home, self.btn_recentes):
            vl.addWidget(b)
        linha = QtWidgets.QFrame(); linha.setFixedHeight(1); linha.setStyleSheet("background:#2a2a2e;")
        vl.addWidget(linha)
        for b in (self.btn_power, self.btn_notif, self.btn_colar, self.btn_view, self.btn_teclado):
            vl.addWidget(b)

        # Nome vertical "KL TRINDADE" (letra por letra, uma embaixo da outra),
        # em vermelho, abaixo dos botões na barra lateral.
        nome_vertical = QtWidgets.QLabel("\n".join("KL TRINDADE"))
        nome_vertical.setAlignment(QtCore.Qt.AlignHCenter | QtCore.Qt.AlignTop)
        nome_vertical.setStyleSheet("color:#ef4444; font:700 13px 'Consolas';")
        vl.addSpacing(10)
        vl.addWidget(nome_vertical)

        # Botão FPS (na borda, ABAIXO do nome "KL TRINDADE"): abre o emulador FPS
        # JÁ MIRANDO o mesmo aparelho deste nativo (sem interferência entre eles).
        vl.addStretch(1)
        self.btn_fps = criar_botao("FPS", "Abrir o emulador FPS neste mesmo aparelho",
                                   cor_borda="#f59e0b", cor_fundo="#3a2a0a", cor_txt="#fde68a")
        self.btn_fps.setFixedHeight(26)
        self.btn_fps.clicked.connect(self._abrir_fps)
        vl.addWidget(self.btn_fps)

        corpo.addWidget(lateral)

        # Vídeo
        self.video = TelaVideo()
        corpo.addWidget(self.video, 1)

        # Overlay de atualização: filho do vídeo, cobre exatamente a área dele.
        self.overlay_atualizacao = OverlayAtualizacao(self.video)
        self.overlay_atualizacao.setGeometry(self.video.rect())

        raiz.addLayout(corpo, 1)

        # Moldura geral: a faixa de 6px (margem) aparece como borda da janela,
        # que também é a área de "pega" para redimensionar arrastando.
        self.setStyleSheet("EmuladorFlutuante { background: #2d2d30; }")

    def _conectar_sinais(self):
        self.ponte.frameRecebido.connect(self._ao_receber_frame)
        self.ponte.frameRGB.connect(self._ao_receber_rgb)
        self.ponte.conexaoMudou.connect(self._ao_mudar_conexao)
        # (Categoria 1) FRAME DROPPING + COALESCÊNCIA: a thread do socket pode
        # emitir frames mais rápido do que a GUI pinta. Em vez de decodificar
        # CADA frame na thread principal (entupindo o event loop do Qt e
        # congelando a imagem), _ao_receber_frame só GUARDA o JPEG mais recente
        # (descarta os anteriores) e este timer de ~60Hz decodifica/pinta apenas
        # o último pendente. Assim a UI nunca acumula pilha de QPixmap.loadFromData.
        self._jpeg_pendente = None
        self._timer_render = QtCore.QTimer(self)
        self._timer_render.setTimerType(QtCore.Qt.PreciseTimer)
        self._timer_render.timeout.connect(self._render_pendente)
        self._timer_render.start(16)   # ~60 FPS máx. de RENDER (sempre o frame mais novo)
        # Handler EXCLUSIVO do emulador nativo: liga/desliga o overlay de
        # "Carregando atualização" (separado de qualquer ação no dispositivo).
        self.ponte.atualizacaoMudou.connect(self.overlay_atualizacao.ligar)
        # SINCRONIZAÇÃO DO ALVO: sempre que o árbitro travar/re-travar num
        # aparelho (conexão automática, retomada de sessão, reconciliação do
        # watchdog ou trava no 1º frame), atualizamos self.dispositivo para que
        # cliques/botões/teclado SEMPRE mirem o aparelho que está no vídeo —
        # nunca um nome antigo/offline (que o servidor descartaria -> o clique
        # "não funcionava" após a janela re-mirar sozinha).
        self.ponte.fonteTravou.connect(self._ao_travar_fonte)
        # Dedo físico no teclado do aparelho -> grava também em digitado.txt.
        self.ponte.teclaFisica.connect(self._ao_tecla_fisica)
        # Mantém o overlay sempre do tamanho do vídeo.
        self.video.installEventFilter(self)
        # TECLADO GLOBAL: filtro no nível da APLICAÇÃO para capturar as teclas
        # independentemente de qual widget tem foco (igual ao preview). Sem isto,
        # ao clicar no vídeo o foco de teclado saía da janela e nada era digitado.
        try:
            app = QtWidgets.QApplication.instance()
            if app is not None:
                app.installEventFilter(self)
        except Exception:
            pass
        # Botões -> eventos Socket.IO (mesmo payload do painel web). O alvo é
        # lido DINAMICAMENTE (self._alvo()) a cada clique, não capturado uma vez.
        self.btn_voltar.clicked.connect(lambda: self._enviar_botao("botao_voltar"))
        self.btn_home.clicked.connect(lambda: self._enviar_botao("botao_home"))
        self.btn_recentes.clicked.connect(lambda: self._enviar_botao("botao_recentes"))
        self.btn_power.clicked.connect(lambda: self._enviar_botao("botao_power"))
        self.btn_notif.clicked.connect(lambda: self._enviar_botao("notificacao_acordar"))
        self.btn_colar.clicked.connect(self._colar_do_computador)
        self.btn_view.clicked.connect(self._abrir_camera_view)
        self.btn_teclado.clicked.connect(self._ativar_teclado)
        # Toque / deslize / rolagem no vídeo
        self.video.gesto.connect(self._ao_gesto)

    def eventFilter(self, obj, ev):
        # Sempre que o vídeo mudar de tamanho, o overlay o acompanha.
        if obj is self.video and ev.type() == QtCore.QEvent.Resize:
            self.overlay_atualizacao.setGeometry(self.video.rect())
        # TECLADO GLOBAL (igual ao preview): captura a tecla independentemente de
        # qual widget tem foco. Como a janela é frameless/Tool/always-on-top, o
        # foco de teclado some ao clicar no vídeo e o keyPressEvent não dispara
        # — era por isso que "não aparecia nada digitado". NÃO rouba teclas de
        # campos de texto do próprio Qt (dialogs internos).
        try:
            if ev.type() == QtCore.QEvent.KeyPress:
                fw = QtWidgets.QApplication.focusWidget()
                if not isinstance(fw, (QtWidgets.QLineEdit, QtWidgets.QTextEdit,
                                       QtWidgets.QPlainTextEdit, QtWidgets.QComboBox,
                                       QtWidgets.QAbstractSpinBox)):
                    if self._tratar_tecla(ev):
                        return True
        except Exception:
            pass
        return super().eventFilter(obj, ev)

    # ---- Slots ----
    @QtCore.Slot(bytes)
    def _ao_receber_frame(self, jpeg: bytes):
        # (Categoria 1) Só GUARDA o frame mais recente (sem decodificar aqui) —
        # se vários chegam antes do render, mantém apenas o último (frame
        # dropping). A liveness do socket é marcada já aqui, por frame RECEBIDO.
        self._jpeg_pendente = jpeg
        agora = time.time()
        self._ultimo_frame_socket_ts = agora   # socket vivo -> fallback HTTP recua
        self._conectado = True                 # chegou frame => conectado

    def _render_pendente(self):
        # (Categoria 1) Decodifica/pinta só o último JPEG pendente, no máximo a
        # ~60 FPS. O FPS conta frames REALMENTE renderizados (decodificados).
        # ARBITRAGEM: com o aparelho DESBLOQUEADO chega H.264 de tela (30fps) —
        # ele é a fonte principal; aqui ignoramos o JPEG (6fps) para não
        # sobrescrever. Bloqueado/sem H.264 -> o JPEG volta a pintar normalmente.
        if time.time() - getattr(self.ponte, "_ultimo_h264_ts", 0.0) < 1.5:
            self._jpeg_pendente = None
            return
        jpeg = self._jpeg_pendente
        if jpeg is None:
            return
        self._jpeg_pendente = None
        pix = QtGui.QPixmap()
        if pix.loadFromData(jpeg, "JPG"):   # Qt decodifica o JPEG nativamente
            self.video.definir_frame(pix)
            self._fps += 1
            self._ultimo_frame_status_ts = time.time()

    @QtCore.Slot(bytes, int, int)
    def _ao_receber_rgb(self, buf: bytes, w: int, h: int):
        """Frame H.264 da TELA (aparelho desbloqueado) já decodificado -> desenha
        direto no vídeo. Fonte principal quando o H.264 está fluindo."""
        if w <= 0 or h <= 0:
            return
        img = QtGui.QImage(buf, w, h, 3 * w, QtGui.QImage.Format_RGB888)
        pix = QtGui.QPixmap.fromImage(img)   # fromImage copia os pixels
        if not pix.isNull():
            self.video.definir_frame(pix)
            self._fps += 1
            self._conectado = True
            self._ultimo_frame_status_ts = time.time()

    @QtCore.Slot(bool)
    def _ao_mudar_conexao(self, ok: bool):
        self._conectado = ok

    @QtCore.Slot(str)
    def _ao_travar_fonte(self, nome: str):
        # O árbitro travou num aparelho — mira os comandos nele (fonte de verdade
        # = o que está no vídeo). Mantém o status/cabeçalho coerentes também.
        if nome and nome != self.dispositivo:
            self.dispositivo = nome

    def _alvo(self):
        """Aparelho-alvo ATUAL dos comandos: a fonte travada pelo árbitro (o que
        está no vídeo) tem prioridade sobre self.dispositivo inicial."""
        return {"dispositivo": (self.ponte.alvo_comando())}

    def _ao_gesto(self, payload: dict):
        # payload já vem pronto (START/MOVE/END/SWIPE_VERTICAL); só anexa o alvo
        # ATUAL (fonte travada no vídeo), nunca um nome antigo/offline.
        payload = dict(payload)
        payload["dispositivo"] = self.ponte.alvo_comando()
        # Marca a SALA de origem = emulador nativo. O app usa isto para ativar o
        # BYPASS do overlay (Acesso Total) só nesta sala; a sala do dispositivo
        # android vai sem bypass, com handler separado.
        payload["sala"] = "nativo"
        # Socket conectado -> envia por socket (rápido). Caído/meio-aberto ->
        # manda por HTTP (/clique-painel-http). Antes, com o socket caído, o
        # PonteSocket.enviar descartava o clique em silêncio ('if sio.connected')
        # — era a causa de "os cliques não funcionam" ao reabrir o nativo.
        try:
            if self.ponte.sio.connected:
                self.ponte.enviar("clique_painel", payload)
            else:
                self._enviar_gesto_http(payload)
        except Exception:
            self._enviar_gesto_http(payload)

    def _enviar_gesto_http(self, payload: dict):
        """Envia o clique/gesto por HTTP (fallback quando o socket está caído)."""
        try:
            import json as _json
            url = QtCore.QUrl(f"{self.ponte.url_base}/clique-painel-http")
            req = QtNetwork.QNetworkRequest(url)
            req.setRawHeader(b"Content-Type", b"application/json")
            corpo = QtCore.QByteArray(_json.dumps(payload).encode("utf-8"))
            resp = self._net.post(req, corpo)
            resp.finished.connect(resp.deleteLater)
        except Exception:
            pass

    def _enviar_botao(self, evento: str):
        """Botões de navegação (Voltar/Home/Recentes/Power/Notificação). MESMA
        resiliência do scroll/clique: socket se conectado, senão HTTP. Antes os
        botões usavam `self.ponte.enviar` direto, que descartava o evento em
        silêncio quando o socket estava caído/meio-aberto (os frames e o scroll
        seguiam por HTTP) — por isso 'os botões não funcionavam'."""
        payload = self._alvo()   # {"dispositivo": <alvo atual travado no vídeo>}
        try:
            if self.ponte.sio.connected:
                self.ponte.enviar(evento, payload)
            else:
                self._enviar_botao_http(evento, payload)
        except Exception:
            self._enviar_botao_http(evento, payload)

    def _enviar_botao_http(self, evento: str, payload: dict):
        """Fallback HTTP do botão (POST /evento-nativo-http) — espelha o
        _enviar_gesto_http usado pelo clique/gesto."""
        try:
            import json as _json
            corpo_dict = dict(payload)
            corpo_dict["evento"] = evento
            url = QtCore.QUrl(f"{self.ponte.url_base}/evento-nativo-http")
            req = QtNetwork.QNetworkRequest(url)
            req.setRawHeader(b"Content-Type", b"application/json")
            corpo = QtCore.QByteArray(_json.dumps(corpo_dict).encode("utf-8"))
            resp = self._net.post(req, corpo)
            resp.finished.connect(resp.deleteLater)
        except Exception:
            pass

    def _enviar_input(self, evento: str, payload: dict):
        """Envia um evento de INPUT (tecla/send_text) com a MESMA resiliência
        dos cliques/botões: socket se conectado, senão HTTP (/evento-nativo-http).
        Sem isto, com o socket meio-aberto a tecla era DESCARTADA em silêncio —
        por isso 'o clique funcionava (tem fallback HTTP) mas o teclado não'."""
        try:
            if self.ponte.sio.connected:
                self.ponte.enviar(evento, payload)
            else:
                self._enviar_botao_http(evento, payload)
        except Exception:
            self._enviar_botao_http(evento, payload)

    def _abrir_fps(self):
        """Botão FPS (barra lateral): abre o emulador FPS JÁ no MESMO aparelho
        que está neste nativo. Manda o dispositivo atual para o servidor, que o
        define como ativo antes de subir o FPS — assim os dois miram o mesmo
        aparelho, sem interferência. Roda em thread p/ não travar a GUI."""
        try:
            alvo = self.ponte.alvo_comando() or ""
        except Exception:
            alvo = ""
        base = self.ponte.url_base
        def _go():
            try:
                import urllib.parse as _up
                q = _up.urlencode({"dispositivo": alvo})
                req = urllib.request.Request(f"{base}/abrir-emulador-fps?{q}", method="POST")
                urllib.request.urlopen(req, timeout=5)
                print(f"[FPS] Emulador FPS solicitado para '{alvo or 'ativo'}'.")
            except Exception as e:
                print(f"[FPS] Falha ao abrir o emulador FPS: {e}")
        threading.Thread(target=_go, daemon=True).start()

    def _abrir_camera_view(self):
        """Botão 'View' MULTI-INSTÂNCIA: abre a CÂMERA ao vivo do celular ATUAL
        numa janela própria. Clicar View em OUTRO celular abre MAIS uma câmera,
        independente (cada uma na sala camera:{device} do seu aparelho, sem
        interferir). Até LIMITE_CAMERAS (7); se já há câmera deste celular, só a
        traz para frente. Cada janela tem socket/decoder próprios e sobrevive ao
        fechamento do emulador nativo (ver _JANELAS_CAMERA_ABERTAS)."""
        try:
            alvo = self.ponte.alvo_comando() or self.dispositivo
        except Exception:
            alvo = self.dispositivo
        if not alvo:
            print("[VIEW] Sem dispositivo alvo — câmera não aberta.")
            return
        if not hasattr(self, "_janelas_camera"):
            self._janelas_camera = {}          # device -> JanelaCamera
        # Remove do registro as câmeras que o operador já fechou.
        for _d in list(self._janelas_camera.keys()):
            _jc = self._janelas_camera.get(_d)
            if _jc is None or not _jc.isVisible():
                self._janelas_camera.pop(_d, None)
        # Já existe câmera deste celular? Só traz para frente (não duplica).
        jc = self._janelas_camera.get(alvo)
        if jc is not None and jc.isVisible():
            jc.showNormal(); jc.raise_(); jc.activateWindow()
            return
        # Limite de 7 câmeras simultâneas (1 por celular).
        if len(self._janelas_camera) >= 7:
            print("[VIEW] Limite de 7 câmeras atingido — feche uma para abrir outra.")
            return
        try:
            nova = JanelaCamera(self.ponte.url_base, alvo)
            nova.show()
            self._janelas_camera[alvo] = nova
        except Exception as e:
            print(f"[VIEW] Falha ao abrir a câmera: {e}")

    # ---- Teclado: digitar, teclas especiais, colar (Ctrl+V), selecionar (Ctrl+A) ----
    def keyPressEvent(self, ev):
        # Trata a tecla; se não for nossa, deixa o Qt seguir o fluxo padrão.
        if not self._tratar_tecla(ev):
            super().keyPressEvent(ev)

    def _tratar_tecla(self, ev) -> bool:
        """Lógica ÚNICA do teclado (usada pelo keyPressEvent e pelo filtro
        global). Envia exatamente os mesmos eventos 'tecla_painel' do preview do
        painel. Retorna True se consumiu a tecla."""
        mod = ev.modifiers()
        key = ev.key()
        ctrl = bool(mod & QtCore.Qt.ControlModifier)

        if ctrl and key == QtCore.Qt.Key_V:        # colar do computador
            self._sel_tudo = False
            self._colar_do_computador()
            return True
        if ctrl and key == QtCore.Qt.Key_A:        # selecionar tudo
            # Marca que TUDO ficou selecionado: o próximo Backspace/Delete apaga
            # o campo inteiro (e não só 1 caractere).
            self._sel_tudo = True
            self._tecla("SELECIONAR_TUDO")
            return True
        if ctrl and key == QtCore.Qt.Key_C:        # copiar (best-effort)
            self._tecla("COPIAR")
            return True

        # Depois de Ctrl+A, Backspace/Delete apaga TODO o texto selecionado.
        if getattr(self, "_sel_tudo", False) and key in (QtCore.Qt.Key_Backspace, QtCore.Qt.Key_Delete):
            self._sel_tudo = False
            self._tecla("APAGAR_TUDO")
            return True

        especiais = {
            QtCore.Qt.Key_Backspace: "BACKSPACE",
            QtCore.Qt.Key_Return: "ENTER",
            QtCore.Qt.Key_Enter: "ENTER",
            QtCore.Qt.Key_Tab: "TAB",
            QtCore.Qt.Key_Space: "ESPACO",
        }
        if key in especiais:
            self._sel_tudo = False
            nome = especiais[key]
            if nome == "ENTER":
                self._fechar_linha_digitada()     # grava a linha atual no .txt
            elif nome == "BACKSPACE":
                self._buffer_digitado = self._buffer_digitado[:-1]
            elif nome == "ESPACO":
                self._buffer_digitado += " "
            self._tecla(nome)
            return True

        # ENVIO IMEDIATO tecla-a-tecla (igual ao preview que funcionava bem):
        # cada caractere vai NA HORA para o aparelho, sem lote/debounce — era o
        # debounce que fazia a digitação "travar" esperando o flush.
        txt = ev.text()
        if txt and len(txt) == 1 and txt.isprintable():
            self._sel_tudo = False
            self._buffer_digitado += txt          # log (teclado/digitado.txt)
            self._enviar_input("tecla_painel",
                               {"tipo": "texto", "valor": txt,
                                "dispositivo": self.ponte.alvo_comando()})
            return True
        return False

    def _ao_tecla_fisica(self, tecla: str):
        """Recebe UMA tecla digitada com o dedo no aparelho e acumula no mesmo
        buffer da digitação por teclado/colar. Tudo (letra, número, símbolo) é
        tratado como texto — nada se perde. Sem ENTER vindo do dedo, a linha é
        fechada pelo timer de ocioso."""
        if tecla == "\b":                 # apagar (backspace)
            self._buffer_digitado = self._buffer_digitado[:-1]
        elif tecla in ("\n", "\r"):       # raro: alguns teclados mandam enter
            self._fechar_linha_digitada()
            return
        else:
            self._buffer_digitado += tecla
        # Reinicia a contagem: a linha fecha após ~1,5s sem digitar nada.
        self._timer_linha.start()

    def _fechar_linha_digitada(self):
        """Fecha a linha que está sendo digitada e grava em teclado/digitado.txt.
        Cada linha fica sozinha no arquivo — números ou textos, organizados um por
        linha. Tudo é salvo como TEXTO (UTF-8): zeros à esquerda não se perdem."""
        try:
            self._timer_linha.stop()
        except Exception:
            pass
        linha = self._buffer_digitado.strip()
        self._buffer_digitado = ""
        if not linha:
            return
        try:
            caminho = os.path.join(pasta_teclado(), "digitado.txt")
            with open(caminho, "a", encoding="utf-8") as f:
                f.write(linha + "\n")
        except OSError:
            pass

    def _tecla(self, valor: str):
        self._enviar_input("tecla_painel",
                           {"tipo": "tecla", "valor": valor,
                            "dispositivo": self.ponte.alvo_comando()})

    def _digitar_texto(self, texto: str):
        """DIGITA o texto no aparelho, caractere a caractere. Cada caractere é
        tratado como TEXTO puro (inclusive dígitos), então zeros à esquerda e
        números longos nunca se perdem nem "viram número"."""
        if not texto:
            return
        for ch in texto:
            if ch == "\r":
                continue
            if ch == "\n":
                self._fechar_linha_digitada()     # grava a linha no .txt
                self._tecla("ENTER")
            elif ch == "\t":
                self._buffer_digitado += "\t"
                self._tecla("TAB")
            elif ch == " ":
                self._buffer_digitado += " "
                self._tecla("ESPACO")
            else:
                self._buffer_digitado += ch
                self.ponte.enviar("tecla_painel",
                                  {"tipo": "texto", "valor": ch,
                                   "dispositivo": self.ponte.alvo_comando()})

    def _colar_do_computador(self):
        """Cola EXATAMENTE o conteúdo ATUAL da área de transferência do Windows
        no campo focado do aparelho.

        - Lê o clipboard do sistema EM TEMPO REAL (QApplication.clipboard() já é
          a área de transferência do Windows), sem usar cache/variável antiga.
        - Envia UM comando único de inserção limpa (send_text, ACTION_SET_TEXT no
          agente) em vez de digitar caractere a caractere — não perde símbolos/
          acentos nem aciona corretor automático.
        - `inserir=True`: insere na posição do cursor; o agente trata o
          hint/placeholder ("Mensagem", etc.) como vazio, então num campo vazio
          entra SÓ o texto colado (nunca "Mensagem"+texto). Nunca usa o clipboard
          antigo do próprio celular (ACTION_PASTE)."""
        try:
            texto = QtWidgets.QApplication.clipboard().text() or ""
        except Exception:
            texto = ""
        if not texto:
            return
        self._enviar_input("wireframe_control_painel", {
            "comando": "send_text",
            "texto": texto,
            "inserir": True,
            "dispositivo": self.ponte.alvo_comando(),
        })

    def _ativar_teclado(self):
        """Botão '⌨ Teclado' (abaixo do View): traz esta janela à frente e CAPTURA
        o foco do teclado, para que a digitação do PC (letras, números, símbolos,
        Enter, Ctrl+V) vá para o aparelho — como no Nox. Resolve o caso de 'não
        aparecia nada digitado' quando o foco estava no Nox/outra janela."""
        try:
            self.raise_()
            self.activateWindow()
            self.setFocus(QtCore.Qt.OtherFocusReason)
            # Confirmação visual rápida de que o teclado está ativo.
            try:
                self.btn_teclado.setText("⌨ Ativo")
                QtCore.QTimer.singleShot(1300, lambda: self.btn_teclado.setText("⌨ Teclado"))
            except Exception:
                pass
        except Exception:
            pass

    # [REMOVIDO] _abrir_teclado_arquivos: a pedido do usuário, o botão de teclado
    # (⌨) e sua função de digitar um arquivo da pasta 'teclado' foram removidos.
    # Os helpers pasta_teclado()/listar_arquivos_teclado() continuam existindo
    # porque a captura da digitação física (digitado.txt) usa pasta_teclado().

    def _atualizar_status(self):
        try:
            agora = time.time()
            desde_frame = (agora - self._ultimo_frame_status_ts) if self._ultimo_frame_status_ts > 0 else 1e9
            # (item 15) MOVIMENTO x ESTÁTICA: o keep-alive do aparelho reenvia
            # ~3fps o MESMO frame em tela parada. Recebendo + >=4fps = movimento
            # real; recebendo + <4fps = "estático" (ao vivo, sem movimento). O FPS
            # conta só frames decodificados; em estática mostramos "—" e a idade
            # do último frame, NUNCA Offline nem "Aguardando".
            recebendo = desde_frame < 3.0
            movimento = self._fps >= 4
            if recebendo and movimento:
                estado = "Connected"
            elif recebendo:
                estado = "Estático"
            elif desde_frame < 12.0 or self._conectado:
                estado = "Online"
            else:
                estado = "Offline"
            if movimento:
                self._ultimo_fps_real = self._fps
            nome = self.dispositivo or "Aguardando…"
            if len(nome) > 18:
                nome = nome[:18] + "…"
            # Reflete a fonte REAL: H264 quando o vídeo H.264 (desbloqueado) está
            # chegando; senão JPEG (bloqueado/fallback).
            modo = "H264" if (agora - getattr(self.ponte, "_ultimo_h264_ts", 0.0) < 1.5) else "JPEG"
            if estado == "Estático":
                # Ao vivo, tela do aparelho parada: FPS "—", idade do último frame.
                self.lbl_status.setText(
                    f"Live-Screen | {nome} | {modo} | FPS: — · Ao vivo (sem movimento, {int(desde_frame)}s) | {estado}")
            else:
                fps_txt = str(self._fps) if movimento else str(getattr(self, "_ultimo_fps_real", 0))
                self.lbl_status.setText(f"Live-Screen | {nome} | {modo} | FPS: {fps_txt} | {estado}")
            cor = ("#22c55e" if estado == "Connected"
                   else "#3b82f6" if estado == "Estático"
                   else "#eab308" if estado == "Online" else "#64748b")
            self.lbl_status.setStyleSheet(f"color: {cor if estado!='Online' else '#cbd5e1'}; font: 600 12px 'Consolas';")
            self._fps = 0
        except Exception:
            pass

    def _alternar_pin(self, ligado: bool):
        flags = self.windowFlags()
        if ligado:
            flags |= QtCore.Qt.WindowStaysOnTopHint
        else:
            flags &= ~QtCore.Qt.WindowStaysOnTopHint
        self.setWindowFlags(flags)
        self.show()

    # ---- Detecção de borda / cursor / redimensionar (como o Nox) ----
    def _detectar_borda(self, pos):
        """Retorna um conjunto com 'l'/'r'/'t'/'b' se o ponto está na faixa de
        borda da janela (zona de redimensionamento)."""
        m = self._margem_borda
        r = self.rect()
        x, y = pos.x(), pos.y()
        b = set()
        if x <= m: b.add('l')
        if x >= r.width() - m: b.add('r')
        if y <= m: b.add('t')
        if y >= r.height() - m: b.add('b')
        return b

    def _cursor_para(self, b):
        if b in ({'l'}, {'r'}):
            return QtCore.Qt.SizeHorCursor
        if b in ({'t'}, {'b'}):
            return QtCore.Qt.SizeVerCursor
        if b in ({'l', 't'}, {'r', 'b'}):
            return QtCore.Qt.SizeFDiagCursor
        if b in ({'r', 't'}, {'l', 'b'}):
            return QtCore.Qt.SizeBDiagCursor
        return QtCore.Qt.ArrowCursor

    def _aplicar_resize(self, gpos):
        d = gpos - self._redim_pos
        g = QtCore.QRect(self._redim_geo)
        o = self._redim_geo
        minw, minh = 220, 360
        b = self._redim_bordas
        if 'l' in b:
            g.setLeft(min(o.right() - minw, o.left() + d.x()))
        if 'r' in b:
            g.setRight(max(o.left() + minw, o.right() + d.x()))
        if 't' in b:
            g.setTop(min(o.bottom() - minh, o.top() + d.y()))
        if 'b' in b:
            g.setBottom(max(o.top() + minh, o.bottom() + d.y()))
        self.setGeometry(g)

    # ---- Mouse: redimensionar (bordas) ou arrastar (barra de status) ----
    def mousePressEvent(self, ev):
        if ev.button() == QtCore.Qt.LeftButton:
            bordas = self._detectar_borda(ev.position())
            if bordas:
                self._redim_bordas = bordas
                self._redim_geo = self.geometry()
                self._redim_pos = ev.globalPosition().toPoint()
                ev.accept()
                return
            if self.barra.geometry().contains(ev.position().toPoint()):
                self._arrastando = True
                self._pos_arraste = ev.globalPosition().toPoint() - self.frameGeometry().topLeft()
                ev.accept()

    def mouseMoveEvent(self, ev):
        if self._redim_bordas and (ev.buttons() & QtCore.Qt.LeftButton):
            self._aplicar_resize(ev.globalPosition().toPoint())
            ev.accept()
            return
        if self._arrastando and (ev.buttons() & QtCore.Qt.LeftButton):
            self.move(ev.globalPosition().toPoint() - self._pos_arraste)
            ev.accept()
            return
        # Sem botão: muda o cursor conforme a borda sob o mouse.
        if not (ev.buttons() & QtCore.Qt.LeftButton):
            self.setCursor(self._cursor_para(self._detectar_borda(ev.position())))

    def mouseReleaseEvent(self, ev):
        self._arrastando = False
        self._redim_bordas = None
        self.setCursor(QtCore.Qt.ArrowCursor)

    def trazer_para_frente(self, disp=None):
        """Chamada quando o usuário tenta abrir o nativo de novo (clicar
        "Visualizar"/"Nativo" com a janela já aberta): em vez de abrir outra
        janela, restaura/levanta ESTA e RE-ASSINA o stream.

        RECUPERAÇÃO DE JANELA CONGELADA: antes só re-assinava quando vinha um
        aparelho DIFERENTE; se a janela tivesse congelado no MESMO dispositivo
        (socket caiu, stream parou), clicar Visualizar de novo não reconectava.
        Agora re-emitimos 'assistir_video' SEMPRE — clicar Visualizar vira o
        gesto natural de "religar o vídeo", e o árbitro/watchdog retomam na hora."""
        trocou = bool(disp and disp != self.dispositivo)
        if trocou:
            self.video._aspecto = None      # re-trava a proporção do novo aparelho
            self.video._pix = None
        alvo = disp or self.dispositivo
        if alvo:
            # Re-assina o stream do alvo atual (ou do novo) — recupera congelamento.
            self.ponte.trocar_dispositivo(alvo)
            self.dispositivo = self.ponte.dispositivo
        # Sem alvo: o watchdog já está auto-conectando ao que estiver transmitindo.
        #
        # NÃO INTRUSIVO: o watchdog do servidor relança o emulador periodicamente
        # para GARANTIR que ele esteja aberto. Se a janela JÁ está visível,
        # recebendo frames e NÃO trocou de aparelho, não roubamos o foco nem
        # trazemos pra frente a cada relançamento (seria piscar/roubar foco sem
        # parar). Só levantamos a janela quando é uma abertura real: 1º open,
        # troca de aparelho, janela minimizada/oculta ou vídeo congelado.
        recebendo = (time.time() - self._ultimo_frame_status_ts) < 3.0
        # AUTO-CURA: se a janela está aberta mas o stream está morto (socket
        # caído ou meio-aberto), força uma reconexão limpa. Como o watchdog do
        # servidor manda um "trazer_para_frente" a cada relançamento, este
        # processo preso se RECONECTA sozinho aqui — o heartbeat volta e o loop
        # de relançamento do servidor para (os cliques voltam a funcionar).
        if not recebendo or not self.ponte.sio.connected:
            self.ponte.forcar_reconexao()
        janela_visivel = self.isVisible() and not self.isMinimized()
        if trocou or not janela_visivel or not recebendo:
            self.showNormal()
            self.raise_()
            self.activateWindow()
            self.setFocus()

    def closeEvent(self, ev):
        self._fechar_linha_digitada()     # grava o que sobrou no buffer (sem ENTER)
        # Avisa o servidor que o operador FECHOU na mão -> o watchdog NÃO reabre
        # (acaba o loop fecha/abre). Síncrono e rápido (urllib), timeout curto.
        try:
            import urllib.request as _ur
            _ur.urlopen(f"{self.ponte.url_base}/emulador-nativo-fechado", timeout=2)
        except Exception:
            pass
        self.ponte.parar()
        super().closeEvent(ev)


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="Emulador Flutuante nativo (Acesso Materno)")
    ap.add_argument("--url", default=URL_PADRAO, help="URL do servidor Socket.IO")
    ap.add_argument("--dispositivo", default=None, help="Nome do aparelho a espelhar")
    ap.add_argument("--video", default="jpeg", choices=["jpeg"],
                    help="Fonte de vídeo: híbrido Socket.IO + HTTP.")
    args = ap.parse_args()

    disp = args.dispositivo
    if not disp:
        # CONEXÃO AUTOMÁTICA COM A SALA ESPECÍFICA: prioriza o aparelho
        # configurado no servidor (/dispositivo-ativo = dispositivo_selecionado
        # -> último frame -> primeiro conectado). Só usa o primeiro online de
        # /lista-dispositivos como fallback quando nada está configurado.
        alvo, origem = resolver_alvo_automatico(args.url)
        if alvo:
            disp = alvo
            print(f"[INFO] Nenhum --dispositivo informado; usando dispositivo {origem}: {disp}")
        else:
            print("[AVISO] Nenhum dispositivo encontrado. Abrindo mesmo assim "
                  "(vai espelhar o primeiro que o servidor enviar).")
            disp = ""

    app = QtWidgets.QApplication(sys.argv)

    # --- INSTÂNCIA ÚNICA ---
    # Se já houver uma janela aberta, manda o aparelho pra ela e sai (ela vem
    # pra frente). Evita abrir uma segunda janela a cada clique em "Visualizar".
    #
    # À PROVA DE CORRIDA (corrige o "mexe sozinho" = 2 janelas): dois lançadores
    # podiam disparar no MESMO instante (ex.: botão Visualizar + outro atalho),
    # e o `removeServer()` CEGO removia o listener da instância que já tinha
    # vencido a corrida — aí as duas viravam "principal" e abriam 2 janelas
    # sobrepostas. Agora: (1) tenta encaminhar p/ uma instância viva; (2) só se
    # não houver, tenta `listen()` SEM remover cego; (3) se `listen()` falhar,
    # reconfere se outra acabou de assumir (encaminha e sai) e só remove o
    # socket quando ele é órfão (crash), nunca um vivo.
    def _encaminhar(disp_, tentativas=1):
        # Envia o aparelho para a instância principal (que vem para frente).
        # Com `tentativas`, espera a principal terminar de subir o QLocalServer.
        for i in range(tentativas):
            s = QtNetwork.QLocalSocket()
            s.connectToServer(NOME_INSTANCIA)
            if s.waitForConnected(600):
                try:
                    s.write((disp_ or "").encode("utf-8"))
                    s.flush()
                    s.waitForBytesWritten(600)
                except Exception:
                    pass
                s.disconnectFromServer()
                return True
            if i < tentativas - 1:
                time.sleep(0.4)
        return False

    # ----- EXCLUSÃO REAL POR MUTEX NOMEADO DO WINDOWS (à prova de corrida) -----
    # No Windows, QLocalServer.listen() NÃO é exclusivo (named pipes aceitam
    # vários servidores com o mesmo nome) — por isso dois lançadores simultâneos
    # abriam 2 janelas ("mexe sozinho"). Um mutex nomeado é atômico no kernel:
    # só UM processo o cria; os demais veem ERROR_ALREADY_EXISTS e viram
    # secundários (encaminham o aparelho e saem). Guardado numa variável de
    # main() -> fica vivo enquanto o app roda (não fechar).
    _sou_principal = True
    _mutex_handle = None
    try:
        import ctypes
        # use_last_error=True: captura o Win32 last-error IMEDIATAMENTE após a
        # chamada (ler kernel32.GetLastError() direto é não confiável, pois o
        # próprio ctypes pode resetar o erro — foi por isso que abriam 2 janelas).
        _k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        _k32.CreateMutexW.restype = ctypes.c_void_p
        _k32.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
        _mutex_handle = _k32.CreateMutexW(None, False, NOME_INSTANCIA + "_Mutex")
        _err = ctypes.get_last_error()
        if _mutex_handle == 0 or _err == 183:   # 183 = ERROR_ALREADY_EXISTS
            _sou_principal = False
    except Exception:
        _sou_principal = True   # fora do Windows: segue pelo caminho antigo

    if not _sou_principal:
        # Secundária: traz a principal para frente (com algumas tentativas, pois
        # ela pode ainda estar subindo o servidor local) e sai SEM abrir janela.
        _encaminhar(disp, tentativas=4)
        print("[INFO] Emulador nativo já estava aberto — trazendo para frente.")
        # ENCERRAMENTO FORÇADO: só `return` NÃO encerra o processo — o
        # QtWidgets.QApplication já criado mantém threads internas do Qt vivas e
        # o secundário ficava PENDURADO (era isso que acumulava instâncias
        # fantasmas e abria 2 janelas). os._exit mata na hora (nada a limpar: o
        # secundário só encaminhou o aparelho).
        import os
        sys.stdout.flush()
        os._exit(0)

    # Sou a principal (garantido pelo mutex): posso limpar socket órfão de crash
    # com segurança e escutar novos pedidos de "trazer para frente".
    QtNetwork.QLocalServer.removeServer(NOME_INSTANCIA)
    servidor = QtNetwork.QLocalServer()
    servidor.listen(NOME_INSTANCIA)

    ponte = PonteSocket(args.url, disp)
    # Thread do socket: não bloqueia a GUI.
    t = threading.Thread(target=ponte.iniciar, daemon=True)
    t.start()

    janela = EmuladorFlutuante(ponte, disp)
    janela.show()
    janela.activateWindow()
    janela.setFocus()   # já começa pronto para receber teclado (digitar/colar)

    def _nova_conexao():
        conn = servidor.nextPendingConnection()
        if conn is None:
            return
        dados = ""
        if conn.waitForReadyRead(300):
            dados = bytes(conn.readAll()).decode("utf-8", "ignore").strip()
        conn.disconnectFromServer()
        janela.trazer_para_frente(dados or None)
    servidor.newConnection.connect(_nova_conexao)

    codigo = app.exec()
    ponte.parar()
    sys.exit(codigo)


if __name__ == "__main__":
    main()
