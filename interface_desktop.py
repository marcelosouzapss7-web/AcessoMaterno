# -*- coding: utf-8 -*-
"""
interface_desktop.py — Acesso Materno (Desktop PyQt6 / QWebEngine)
==================================================================
CORREÇÃO CRÍTICA: a GUI antes tentava carregar a porta legada 27103, mas o
backend FastAPI e o túnel Cloudflare (painel.megabrasiliautilidades.store)
estão na porta 27105. Isso causava "127.0.0.1 refused to connect" e também
fazia com que processos fantasmas (antigos) presos na memória do Windows
respondessem às requisições → HTTP 404 no botão "Gerar Aplicativo".

O que este arquivo garante:
  1. APP_URL / SERVER_PORT forçados para 27105 (nunca mais 27103).
  2. _limpar_processos_orfãos() roda NA LINHA 1 do __init__, matando qualquer
     processo em LISTENING nas portas 27103/27105 e instâncias presas de
     "Acesso Materno.exe", ANTES do backend subir.
  3. Inicia o backend FastAPI em thread (uvicorn) e só abre a janela depois
     de confirmar via /health que o servidor NOVO está respondendo.

Se o seu interface_desktop.py já tem outras funções (menus, atalhos, etc.),
mantenha-as — basta garantir que as 3 coisas abaixo existam:
   - constantes SERVER_PORT = 27105
   - função _limpar_processos_orfãos() (copie daqui)
   - chamada _limpar_processos_orfãos() na 1ª linha útil do __init__
"""

import os
import sys
import time
import json
import threading
import subprocess
import urllib.request

# ---------------------------------------------------------------------------
# 1) CONSTANTES FORÇADAS PARA A PORTA DO TÚNEL CLOUDFLARE (27105)
#    NUNCA mais usar 27103 — é a porta legada que causou o conflito.
# ---------------------------------------------------------------------------
APP_URL = "http://127.0.0.1"
SERVER_PORT = 27105
SERVIDOR_URL_COMPLETA = f"{APP_URL}:{SERVER_PORT}/"


# ---------------------------------------------------------------------------
# 2) LIMPEZA DE PROCESSOS ÓRFÃOS — roda ANTES de qualquer coisa no __init__
# ---------------------------------------------------------------------------
def _limpar_processos_orfãos():
    """Identifica o PID de qualquer processo ouvindo (LISTENING) nas portas
    27103 e 27105 e força taskkill /F /T (com fallback Stop-Process do
    PowerShell). Também mata instâncias presas de 'Acesso Materno.exe'.
    Só efetivo em Windows; em outros SOs não faz nada."""
    if os.name != "nt":
        return

    portas_alvo = (27103, 27105)
    pids_para_matar = set()

    # 2.1) Descobre os PIDs ocupantes via netstat -ano
    try:
        saida = subprocess.run(
            ["netstat", "-ano"],
            capture_output=True, text=True, timeout=15
        ).stdout
        for linha in saida.splitlines():
            if "LISTENING" not in linha:
                continue
            for porta in portas_alvo:
                if f":{porta} " in linha or linha.rstrip().endswith(f":{porta}"):
                    partes = linha.split()
                    if partes:
                        try:
                            pids_para_matar.add(int(partes[-1]))
                        except ValueError:
                            pass
    except Exception:
        pass

    # 2.2) Mata cada PID (menos a nós mesmos) — taskkill /F /T, depois PowerShell
    for pid in pids_para_matar:
        if pid == os.getpid():
            continue
        try:
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(pid)],
                capture_output=True, timeout=10
            )
        except Exception:
            try:
                subprocess.run(
                    ["powershell", "-NoProfile", "-Command",
                     f"Stop-Process -Id {pid} -Force -ErrorAction SilentlyContinue"],
                    capture_output=True, timeout=10
                )
            except Exception:
                pass

    # 2.3) Mata o .exe antigo por nome (build PyInstaller velho que respawna)
    try:
        subprocess.run(
            ["taskkill", "/F", "/IM", "Acesso Materno.exe", "/T"],
            capture_output=True, timeout=10
        )
    except Exception:
        pass

    # Dá tempo ao Windows liberar os sockets antes de subir o novo servidor
    time.sleep(1.5)


# ---------------------------------------------------------------------------
# 3) THREAD DO BACKEND FASTAPI (uvicorn na porta 27105)
# ---------------------------------------------------------------------------
def _iniciar_backend_thread():
    """Roda o uvicorn em thread. Importa o server.py (que expõe `asgi_app` =
    socketio.ASGIApp envolvendo o FastAPI). Se o import falhar, grava erro."""
    try:
        import uvicorn
        # O server.py registra todas as rotas (@app.get/@app.post) no topo e
        # expõe `asgi_app` em nível de módulo. O bloco if __name__=="__main__"
        # NÃO roda no import — então não há detach/dupla inicialização.
        import server as _mod_servidor
        asgi_app = getattr(_mod_servidor, "asgi_app", None)
        if asgi_app is None:
            print("[ERRO] server.py nao expoe 'asgi_app'. Verifique o server.py.")
            return
        uvicorn.run(asgi_app, host="127.0.0.1", port=SERVER_PORT, log_level="info")
    except Exception as e:
        print(f"[ERRO] Falha ao iniciar backend FastAPI: {e}")


def _esperar_servidor_pronto(timeout_seg=30):
    """Faz GET /health até confirmar que o servidor NOVO responde na 27105
    (rota_salvar_config_existe == True). Retorna (True, health_json) ou
    (False, None) se estourar o timeout."""
    fim = time.time() + timeout_seg
    while time.time() < fim:
        try:
            with urllib.request.urlopen(f"{APP_URL}:{SERVER_PORT}/health", timeout=3) as r:
                dados = json.loads(r.read().decode("utf-8", "ignore"))
            if dados.get("rota_salvar_config_existe"):
                return True, dados
        except Exception:
            pass
        time.sleep(1)
    return False, None


# ---------------------------------------------------------------------------
# 4) JANELA PRINCIPAL (PyQt6 QWebEngine)
# ---------------------------------------------------------------------------
from PyQt6.QtWidgets import QMainWindow, QMessageBox
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtCore import QUrl, QTimer
from PyQt6.QtGui import QIcon


def _caminho_icone():
    """Caminho do icone compativel com PyInstaller (sys._MEIPASS)."""
    base = getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "icone.ico")


class JanelaPrincipal(QMainWindow):
    def __init__(self):
        # ================================================================
        # LINHA 1 — LIMPEZA OBRIGATÓRIA ANTES DE QUALQUER COISA.
        # Garante que nenhum processo fantasma antigo ocupe a 27105.
        # ================================================================
        _limpar_processos_orfãos()

        super().__init__()
        self.setWindowTitle("Acesso Materno — Painel")
        try:
            _ico = _caminho_icone()
            if os.path.exists(_ico):
                self.setWindowIcon(QIcon(_ico))
        except Exception:
            pass
        self.resize(1280, 800)

        self.navegador = QWebEngineView(self)
        self.setCentralWidget(self.navegador)

        # 4.1) Sobe o backend FastAPI em thread
        self._th_backend = threading.Thread(target=_iniciar_backend_thread, daemon=True)
        self._th_backend.start()

        # 4.2) Tela de carregamento enquanto o servidor sobe
        self.navegador.setHtml(
            "<div style='font-family:Arial;padding:40px;color:#fff;background:#0d0d0d;"
            "height:100vh;text-align:center;'>"
            "<h2 style='color:#ef4444'>⚡ Acesso Materno</h2>"
            "<p>Iniciando servidor na porta 27105 e limpando processos antigos...</p>"
            "</div>"
        )

        # 4.3) Quando o servidor estiver pronto (confirmado via /health),
        #      carrega o painel. Se falhar, mostra erro orientando o usuário.
        QTimer.singleShot(800, self._verificar_e_carregar)

    def _verificar_e_carregar(self):
        ok, dados = _esperar_servidor_pronto(timeout_seg=30)
        if ok:
            self.navegador.setUrl(QUrl(SERVIDOR_URL_COMPLETA))
        else:
            self.navegador.setHtml(
                "<div style='font-family:Arial;padding:40px;color:#fff;background:#0d0d0d;"
                "height:100vh;'>"
                "<h2 style='color:#ef4444'>❌ O servidor não subiu na porta 27105</h2>"
                "<p>O backend FastAPI não respondeu em <b>http://127.0.0.1:27105/health</b>.</p>"
                "<p><b>O que fazer:</b></p>"
                "<ol>"
                "<li>Feche <b>python.exe</b>, <b>pythonw.exe</b> e <b>Acesso Materno.exe</b> no Gerenciador de Tarefas.</li>"
                "<li>Execute o programa <b>como Administrador</b> (clique direito → Executar como administrador).</li>"
                "<li>Confira o log <b>servidor_detalhado.log</b> na pasta do .exe.</li>"
                "</ol>"
                "<p style='color:#888;font-size:12px'>O túnel Cloudflare aponta para a porta 27105 — o servidor DEVE subir nela.</p>"
                "</div>"
            )


def main():
    os.environ["QTWEBENGINE_CHROMIUM_FLAGS"] = "--enable-gpu-rasterization --ignore-gpu-blocklist --disable-software-rasterizer --enable-zero-copy"
    from PyQt6.QtWidgets import QApplication
    app = QApplication(sys.argv)
    janela = JanelaPrincipal()
    janela.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()

