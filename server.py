import subprocess
import os
import sys
import time
import random
from fastapi import FastAPI, BackgroundTasks, Request
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


def _descobrir_url_tunel():
    """Descobre a URL pública para injetar no APK de produção.

    Ordem de prioridade:
      1. API local do ngrok em http://127.0.0.1:4040/api/tunnels (pega o public_url HTTPS do dia);
      2. variável de ambiente PANEL_URL;
      3. IP público da máquina (http://IP:27103) como último recurso;
      4. None (o Gradle então usa o placeholder padrão do build.gradle.kts).
    """
    import json as _json
    import urllib.request as _url

    # 1. Inspetor local do ngrok
    try:
        with _url.urlopen("http://127.0.0.1:4040/api/tunnels", timeout=2) as resp:
            dados = _json.loads(resp.read().decode("utf-8"))
        tuneis = dados.get("tunnels", [])
        https = [t.get("public_url") for t in tuneis if t.get("proto") == "https" and t.get("public_url")]
        if https:
            return https[0]
        qualquer = [t.get("public_url") for t in tuneis if t.get("public_url")]
        if qualquer:
            return qualquer[0]
    except Exception:
        pass

    # 2. Variável de ambiente
    env_url = os.environ.get("PANEL_URL")
    if env_url:
        return env_url.strip()

    # 3. IP público (fallback; exige HTTP liberado no app — prefira o ngrok/HTTPS)
    try:
        with _url.urlopen("https://api.ipify.org", timeout=2) as resp:
            ip = resp.read().decode("utf-8").strip()
        if ip:
            return f"http://{ip}:27103"
    except Exception:
        pass

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

    # Descobre a URL do túnel ativo e injeta no build via propriedade do Gradle.
    url_tunel = _descobrir_url_tunel()
    comando = [gradlew, "assembleRelease"]
    if url_tunel:
        comando.append(f"-PpanelUrl={url_tunel}")
        estado_build["mensagem"] = f"Compilando com painel: {url_tunel} ..."
        print(f"[BUILD] Injetando PANEL_URL no APK: {url_tunel}")
    else:
        print("[BUILD] Nenhum túnel detectado; usando o PANEL_URL padrão do build.gradle.kts.")

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

    sufixo = f" (painel: {url_tunel})" if url_tunel else ""
    estado_build.update(
        status="concluido",
        mensagem=f"Concluído! Pasta aberta com o APK em destaque.{sufixo}",
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

def conectar_celular_remoto(ip_cliente: str, nome_celular: str, porta_remota: int = 5555):
    """Pareia o ADB e abre o scrcpy garantindo CONTROLE TOTAL e suporte a múltiplos aparelhos"""
    if not os.path.exists(CAMINHO_SCRCPY):
        print(f"[ERRO CRÍTICO] scrcpy não encontrado em: {CAMINHO_SCRCPY}")
        return

    print(f"[SERVIDOR] Preparando conexão para {nome_celular} ({ip_cliente})...")
    
    # IMPORTANTE: Removido o 'adb disconnect' global para permitir múltiplos celulares simultâneos
    serial_dispositivo = ""
    
    # 1. Identifica e configura o canal de cada dispositivo de forma independente
    if ip_cliente in ["127.0.0.1", "localhost", "10.0.2.2"]:
        print("[ADB] Conectando ao dispositivo local/emulador...")
        resultado = subprocess.run([CAMINHO_ADB, "devices"], stdout=subprocess.PIPE, text=True, shell=True)
        print(f"[ADB] Dispositivos locais detectados:\n{resultado.stdout.strip()}")
        # Em conexões locais, o scrcpy gerencia automaticamente ou usa o alvo padrão
    else:
        # Conexão vinda de rede externa/túnel
        endereco_alvo = f"{ip_cliente}:{porta_remota}"
        serial_dispositivo = endereco_alvo
        print(f"[REDE REVERSA] Tentando pareamento com celular remoto: {endereco_alvo}")
        
        # Conecta o aparelho individualmente sem derrubar os outros ativos
        subprocess.run([CAMINHO_ADB, "connect", endereco_alvo], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, shell=True)
        time.sleep(0.2)
        
        # Cria o túnel reverso isolado para este celular receber os cliques de mouse e teclado
        subprocess.run([CAMINHO_ADB, "-s", endereco_alvo, "reverse", f"tcp:{porta_remota}", f"tcp:{porta_remota}"], shell=True)
    
    # 2. Configura os argumentos de inicialização do scrcpy
    argumentos_scrcpy = [
        CAMINHO_SCRCPY, 
        "--max-size", "1024",
        "--video-bitrate", "1.5M",       # Correção crítica da palavra (tudo junto)
        "--always-on-top", 
        "--window-title", f"Controle Assistivo - {nome_celular}",
        "--tunnel-host", "127.0.0.1",     # Força o fluxo de controle pelo túnel local do PC
        "--prefer-text"                   # Transforma teclas do teclado físico em texto no celular
    ]
    
    # Se houver um serial específico (múltiplos celulares externos), aponta diretamente para ele
    if serial_dispositivo:
        argumentos_scrcpy.extend(["--serial", serial_dispositivo])

    # Registra o título da janela para a GUI conseguir localizá-la e acoplá-la.
    estado_scrcpy["titulo"] = f"Controle Assistivo - {nome_celular}"

    # 3. Executa a janela do scrcpy forçando a exibição visual na tela do Windows
    print(f"[SCRCPY] Abrindo janela gráfica independente para: {nome_celular}")
    try:
        subprocess.Popen(argumentos_scrcpy, creationflags=subprocess.CREATE_NEW_CONSOLE)
        print(f"[SUCESSO] Janela de controle para {nome_celular} iniciada.")
    except Exception as e:
        print(f"[ERRO AO ABRIR SCRCPY] Falha operacional: {str(e)}")

@app.get("/visualizar-imagem")
def obtener_imagem():
    return RedirectResponse(url="https://picsum.photos")

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
    print(f"[SINAL] Requisição de ativação recebida do IP: {ip_origem}")
    
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
    
    print(f"[IMEDIATO] Celular registrado com sucesso: {nome} -> IP: {ip_origem}")
    return {"status": "registrado", "dispositivo": nome, "ip": ip_origem}

@app.get("/lista-dispositivos")
def lista_dispositivos():
    return {"dispositivos": dispositivos_ativos, "selecionado": dispositivo_selecionado}

@app.get("/selecionar-aparelho")
def selecionar_aparelho(nome: str, background_tasks: BackgroundTasks):
    """Rota chamada ao clicar no botão 'Conectar Este' dentro da lista lateral"""
    global dispositivo_selecionado
    if nome in dispositivos_ativos:
        dispositivo_selecionado = nome
        ip_alvo = dispositivos_ativos[nome]
        background_tasks.add_task(conectar_celular_remoto, ip_alvo, nome)
        return {"status": "conectando", "mensagem": f"Iniciando controle assistivo de {nome}"}
    return {"status": "erro", "mensagem": "Dispositivo não encontrado"}

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

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=27103)
