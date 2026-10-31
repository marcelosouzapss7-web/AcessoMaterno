import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

APP_URL = "http://127.0.0.1:27103/"
APP_TITLE = "Acesso Materno"
SERVER_HOST = "127.0.0.1"
SERVER_PORT = 27103

# Variável de ambiente usada para o executável se re-executar em "modo servidor".
ENV_MODO_SERVIDOR = "ACESSO_MATERNO_SERVER"


def _diretorio_base():
    """Pasta onde estão os recursos (index.html). Em .exe aponta para o bundle."""
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def _caminho_log():
    """Caminho do backend_error.log (ao lado do .exe ou na raiz do projeto)."""
    if getattr(sys, "frozen", False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "backend_error.log")


def executar_servidor():
    """Sobe o backend FastAPI. Chamado quando o processo roda em modo servidor."""
    base = _diretorio_base()
    # Garante que o server.py encontre o index.html por caminho relativo (_MEIPASS no .exe).
    os.chdir(base)
    print(f"[SERVIDOR] diretório base (cwd): {base}", flush=True)
    print(f"[SERVIDOR] index.html presente: {os.path.exists(os.path.join(base, 'index.html'))}", flush=True)
    import uvicorn
    from server import asgi_app
    uvicorn.run(asgi_app, host=SERVER_HOST, port=SERVER_PORT, log_level="info")


# ---------------------------------------------------------------------------
# Modo servidor: se iniciado com a flag de ambiente, apenas sobe o backend e
# encerra, sem carregar a interface gráfica. É isto que o .exe chama em si mesmo.
# ---------------------------------------------------------------------------
if os.environ.get(ENV_MODO_SERVIDOR) == "1":
    executar_servidor()
    sys.exit(0)


from PyQt6.QtCore import QUrl, QTimer
from PyQt6.QtWidgets import QApplication, QMainWindow, QWidget, QHBoxLayout
from PyQt6.QtWebEngineWidgets import QWebEngineView


# ---------------------------------------------------------------------------
# Utilitários Win32 (ctypes) para acoplar/destacar a janela nativa do scrcpy.
# ---------------------------------------------------------------------------
if os.name == "nt":
    import ctypes
    from ctypes import wintypes

    _GWL_STYLE = -16
    _WS_CHILD = 0x40000000
    _WS_POPUP = 0x80000000
    _WS_VISIBLE = 0x10000000
    _WS_OVERLAPPEDWINDOW = 0x00CF0000
    _SW_SHOW = 5
    _SWP_SHOWWINDOW = 0x0040

    _u = ctypes.windll.user32
    _u.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
    _u.FindWindowW.restype = wintypes.HWND
    _u.SetParent.argtypes = [wintypes.HWND, wintypes.HWND]
    _u.SetParent.restype = wintypes.HWND
    _u.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
    _u.GetWindowLongPtrW.restype = ctypes.c_longlong
    _u.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_longlong]
    _u.SetWindowLongPtrW.restype = ctypes.c_longlong
    _u.MoveWindow.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.BOOL]
    _u.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    _u.IsWindow.argtypes = [wintypes.HWND]
    _u.IsWindow.restype = wintypes.BOOL
    _u.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]

    def _localizar_janela(titulo):
        if not titulo:
            return 0
        return _u.FindWindowW(None, titulo) or 0

    def _janela_existe(hwnd):
        return bool(hwnd) and bool(_u.IsWindow(hwnd))

    def _acoplar(hwnd, parent_hwnd):
        estilo = _u.GetWindowLongPtrW(hwnd, _GWL_STYLE)
        estilo = (estilo & ~_WS_POPUP & ~_WS_OVERLAPPEDWINDOW) | _WS_CHILD | _WS_VISIBLE
        _u.SetWindowLongPtrW(hwnd, _GWL_STYLE, estilo)
        _u.SetParent(hwnd, parent_hwnd)
        _u.ShowWindow(hwnd, _SW_SHOW)

    def _ajustar(hwnd, largura, altura):
        if largura > 0 and altura > 0:
            _u.MoveWindow(hwnd, 0, 0, largura, altura, True)

    def _destacar(hwnd):
        _u.SetParent(hwnd, 0)
        estilo = _u.GetWindowLongPtrW(hwnd, _GWL_STYLE)
        estilo = (estilo & ~_WS_CHILD) | _WS_OVERLAPPEDWINDOW | _WS_VISIBLE
        _u.SetWindowLongPtrW(hwnd, _GWL_STYLE, estilo)
        _u.SetWindowPos(hwnd, 0, 120, 120, 420, 840, _SWP_SHOWWINDOW)
        _u.ShowWindow(hwnd, _SW_SHOW)


def _consultar(caminho):
    """GET rápido ao backend local; retorna o JSON ou None em caso de falha."""
    try:
        url = f"http://{SERVER_HOST}:{SERVER_PORT}{caminho}"
        with urllib.request.urlopen(url, timeout=0.4) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None


class JanelaPrincipal(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(APP_TITLE)
        self.resize(1200, 800)

        # 1. Sobe o backend automaticamente em segundo plano.
        self.servidor_proc = self._iniciar_servidor()

        # 2. Espera o servidor ficar realmente disponível antes de carregar a página.
        self._aguardar_servidor()

        # 3. Layout: WebView (painel) à esquerda + painel nativo do scrcpy à direita.
        central = QWidget()
        layout = QHBoxLayout(central)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self.navegador = QWebEngineView()
        # Auto-recarrega se o carregamento falhar (ex.: servidor ainda subindo).
        self._tentativas_reload = 0
        self.navegador.loadFinished.connect(self._ao_terminar_carregamento)

        self.container_scrcpy = QWidget()
        self.container_scrcpy.setMinimumWidth(360)
        # O HWND nativo (winId) é criado apenas quando o scrcpy é de fato acoplado,
        # para não interferir na renderização do QWebEngineView na inicialização
        # (widget nativo irmão da WebEngine pode causar tela branca).
        self.container_scrcpy.hide()

        layout.addWidget(self.navegador, 1)
        layout.addWidget(self.container_scrcpy, 0)
        self.setCentralWidget(central)

        # Só carrega a URL depois de ligar o tratamento de auto-reload.
        self.navegador.setUrl(QUrl(APP_URL))

        # 4. Timer que acopla/destaca a janela do scrcpy conforme o estado do servidor.
        self._hwnd_embutido = 0
        if os.name == "nt":
            self.timer_scrcpy = QTimer(self)
            self.timer_scrcpy.timeout.connect(self._tick_scrcpy)
            self.timer_scrcpy.start(600)

    def _iniciar_servidor(self):
        """Inicia o server.py em um processo separado, sem janela de console."""
        ambiente = os.environ.copy()
        ambiente[ENV_MODO_SERVIDOR] = "1"

        if getattr(sys, "frozen", False):
            # No .exe: o próprio executável se re-executa em modo servidor.
            comando = [sys.executable]
        else:
            # Em desenvolvimento: usa o interpretador saudável do venv_desktop.
            raiz = os.path.dirname(os.path.abspath(__file__))
            python_venv = os.path.join(raiz, "venv_desktop", "Scripts", "python.exe")
            interpretador = python_venv if os.path.exists(python_venv) else sys.executable
            comando = [interpretador, os.path.abspath(__file__)]

        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        # Salva a saída do backend em backend_error.log (handles válidos também são
        # necessários porque o app é --windowed). Assim, falhas de import, caminho
        # da index.html ou bind da porta ficam registradas para diagnóstico.
        try:
            self._log_backend = open(_caminho_log(), "w", encoding="utf-8", errors="replace")
            saida = self._log_backend
        except Exception:
            saida = subprocess.DEVNULL
        return subprocess.Popen(
            comando,
            env=ambiente,
            creationflags=flags,
            stdin=subprocess.DEVNULL,
            stdout=saida,
            stderr=subprocess.STDOUT,
        )

    def _aguardar_servidor(self, timeout=30.0):
        """Espera o backend RESPONDER de verdade (não só o bind) antes de carregar."""
        inicio = time.time()
        while time.time() - inicio < timeout:
            if _consultar("/estado-janela-scrcpy") is not None:
                return True
            time.sleep(0.3)
        return False

    def _ao_terminar_carregamento(self, ok):
        """Se a página falhar ao carregar, tenta recarregar automaticamente em 2s."""
        if ok:
            self._tentativas_reload = 0
            return
        if self._tentativas_reload < 15:
            self._tentativas_reload += 1
            QTimer.singleShot(2000, self.navegador.reload)

    def _tick_scrcpy(self):
        """Acopla a janela do scrcpy ao painel ou a libera, conforme o servidor."""
        estado = _consultar("/estado-janela-scrcpy")
        if not estado:
            return

        modo = estado.get("modo", "acoplado")
        hwnd = _localizar_janela(estado.get("titulo"))

        if modo == "acoplado":
            if hwnd:
                if self._hwnd_embutido != hwnd:
                    self.container_scrcpy.show()
                    _acoplar(hwnd, int(self.container_scrcpy.winId()))
                    self._hwnd_embutido = hwnd
                _ajustar(hwnd, self.container_scrcpy.width(), self.container_scrcpy.height())
            elif self._hwnd_embutido and not _janela_existe(self._hwnd_embutido):
                # A janela do scrcpy foi fechada: recolhe o painel.
                self._hwnd_embutido = 0
                self.container_scrcpy.hide()
        else:  # destacado
            if self._hwnd_embutido and _janela_existe(self._hwnd_embutido):
                _destacar(self._hwnd_embutido)
            self._hwnd_embutido = 0
            self.container_scrcpy.hide()

    def closeEvent(self, event):
        """Ao fechar no 'X', encerra o servidor e libera a porta 27103."""
        proc = getattr(self, "servidor_proc", None)
        if proc is not None and proc.poll() is None:
            try:
                if os.name == "nt":
                    # /T encerra também processos filhos (ex.: scrcpy) abertos pelo backend.
                    subprocess.run(
                        ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
                else:
                    proc.terminate()
                proc.wait(timeout=5)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
        event.accept()


def main():
    app = QApplication(sys.argv)
    app.setApplicationName(APP_TITLE)
    janela = JanelaPrincipal()
    janela.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
