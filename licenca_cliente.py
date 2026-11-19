import sys
import os
import json
import platform
import uuid
import hashlib
from PyQt6.QtWidgets import (
    QApplication, QDialog, QVBoxLayout, QLabel, QLineEdit,
    QPushButton, QMessageBox
)
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QIcon

# ---------------------------------------------------------------------------
# SSL/TLS no .exe (PyInstaller): o httpx/supabase precisam do cacert.pem do
# certifi. Quando congelado (frozen), apontamos SSL_CERT_FILE / REQUESTS_CA_BUNDLE
# para certifi.where() ANTES de importar o supabase, evitando o erro
# "[Errno 2] No such file or directory" ao criar o cliente.
# ---------------------------------------------------------------------------
if getattr(sys, "frozen", False):
    try:
        import certifi
        _cacert = certifi.where()
        os.environ.setdefault("SSL_CERT_FILE", _cacert)
        os.environ.setdefault("REQUESTS_CA_BUNDLE", _cacert)
    except Exception:
        pass

from supabase import create_client, Client


def caminho_recurso(nome="icone.ico"):
    """Resolve o caminho de um recurso tanto rodando do codigo-fonte quanto
    empacotado pelo PyInstaller (sys._MEIPASS)."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, nome)

# CREDENCIAIS DO CLIENTE
SUPABASE_URL = "https://zwuvyxsczptzkfxdoybw.supabase.co"
SUPABASE_ANON_KEY = "sb_publishable_E7F6wOuj6O3qVgaeipAVyQ_hR9Ye-xQ"

CONFIG_FILE = os.path.join(os.path.expanduser("~"), ".acessomaterno_licenca.json")

def obter_hwid():
    """Gera um HWID único baseado na placa-mãe/sistema do cliente."""
    try:
        mac = str(uuid.getnode())
        node = platform.node()
        raw = f"{mac}-{node}"
        return hashlib.sha256(raw.encode('utf-8')).hexdigest()
    except Exception:
        return "fallback-hwid-desconhecido"

class TelaAtivacao(QDialog):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Ativação - Acesso Materno")
        try:
            self.setWindowIcon(QIcon(caminho_recurso("icone.ico")))
        except Exception:
            pass
        self.setFixedSize(400, 250)
        self.setWindowFlags(Qt.WindowType.WindowCloseButtonHint)
        self.licenca_valida = False
        self.server_url = None  # preenchido pela RPC ao validar a chave

        try:
            self.supabase: Client = create_client(SUPABASE_URL, SUPABASE_ANON_KEY)
        except Exception as e:
            QMessageBox.critical(None, "Erro", f"Erro ao inicializar cliente Supabase: {e}")
            sys.exit(1)

        self.hwid = obter_hwid()

        layout = QVBoxLayout(self)

        layout.addWidget(QLabel("<b>Insira sua Chave de Acesso para continuar:</b>"))
        
        self.input_chave = QLineEdit()
        self.input_chave.setPlaceholderText("EX: ACESSOMATERNO-XXXX-XXXX")
        layout.addWidget(self.input_chave)

        self.btn_ativar = QPushButton("Ativar e Entrar")
        self.btn_ativar.setStyleSheet("background-color: #2563EB; color: white; font-weight: bold; padding: 8px;")
        self.btn_ativar.clicked.connect(self.validar_chave)
        layout.addWidget(self.btn_ativar)

        # Rótulo visual do servidor vinculado (abaixo da chave). Começa neutro e
        # passa a "✅ Servidor Conectado: <url>" quando a RPC valida a chave.
        self.lbl_servidor = QLabel("🔌 Servidor Vinculado: Aguardando chave...")
        self.lbl_servidor.setWordWrap(True)
        self.lbl_servidor.setStyleSheet(
            "color:#6B7280; font-size:12px; padding:6px 2px;")
        layout.addWidget(self.lbl_servidor)

        # Tentar carregar licença salva anteriormente em segundo plano
        self.tentar_autologin_local()

    def tentar_autologin_local(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r") as f:
                    data = json.load(f)
                    chave_salva = data.get("license_key")
                    if chave_salva:
                        self.input_chave.setText(chave_salva)
                        self.executar_validacao_rpc(chave_salva, mostrar_erros=False)
            except Exception:
                pass

    def validar_chave(self):
        chave = self.input_chave.text().strip()
        if not chave:
            QMessageBox.warning(self, "Aviso", "Digite uma chave de licença válida.")
            return
        
        self.executar_validacao_rpc(chave, mostrar_erros=True)

    def executar_validacao_rpc(self, chave, mostrar_erros=True):
        self.btn_ativar.setEnabled(False)
        self.btn_ativar.setText("Validando no servidor...")
        QApplication.processEvents()

        try:
            response = self.supabase.rpc(
                "validar_e_ativar_licenca",
                {
                    "p_license_key": chave,
                    "p_hwid": self.hwid
                }
            ).execute()

            resultado = response.data

            if isinstance(resultado, list):
                resultado = resultado[0] if resultado else {}

            if resultado.get("valid") is True:
                days_left = resultado.get("days_left", 0)
                # server_url (e demais metadados) vêm da RPC validar_e_ativar_licenca.
                server_url = resultado.get("server_url") or ""
                cliente_nome = resultado.get("cliente_nome") or ""
                route_cidr = resultado.get("route_cidr") or ""
                self.server_url = server_url or None

                dados_local = {"license_key": chave}
                if server_url:
                    dados_local["server_url"] = server_url
                if cliente_nome:
                    dados_local["cliente_nome"] = cliente_nome
                if route_cidr:
                    dados_local["route_cidr"] = route_cidr
                with open(CONFIG_FILE, "w") as f:
                    json.dump(dados_local, f)

                # Atualiza o rótulo visual do servidor conectado abaixo da chave.
                if server_url:
                    self.lbl_servidor.setText(f"✅ Servidor Conectado: {server_url}")
                    self.lbl_servidor.setStyleSheet(
                        "color:#16A34A; font-size:12px; font-weight:bold; padding:6px 2px;")
                else:
                    self.lbl_servidor.setText("✅ Licença válida (sem servidor vinculado).")
                    self.lbl_servidor.setStyleSheet(
                        "color:#16A34A; font-size:12px; padding:6px 2px;")
                QApplication.processEvents()

                self.licenca_valida = True
                if mostrar_erros:
                    extra = f"\nServidor: {server_url}" if server_url else ""
                    QMessageBox.information(self, "Sucesso", f"Licença Ativa!\nDias restantes: {days_left}{extra}")
                self.accept()
            else:
                msg = resultado.get("message", "Licença inválida ou expirada.")
                self.lbl_servidor.setText("🔌 Servidor Vinculado: Aguardando chave...")
                self.lbl_servidor.setStyleSheet(
                    "color:#6B7280; font-size:12px; padding:6px 2px;")
                if mostrar_erros:
                    QMessageBox.critical(self, "Acesso Negado", msg)
                self.btn_ativar.setEnabled(True)
                self.btn_ativar.setText("Ativar e Entrar")
                if not mostrar_erros and os.path.exists(CONFIG_FILE):
                    os.remove(CONFIG_FILE)

        except Exception as e:
            if mostrar_erros:
                QMessageBox.critical(self, "Erro de Conexão", f"Não foi possível validar com o servidor:\n{e}")
            self.btn_ativar.setEnabled(True)
            self.btn_ativar.setText("Ativar e Entrar")

def verificar_licenca_ou_sair():
    app = QApplication.instance() or QApplication(sys.argv)
    dialogo = TelaAtivacao()
    
    if dialogo.licenca_valida:
        return True
        
    if dialogo.exec() == QDialog.DialogCode.Accepted and dialogo.licenca_valida:
        return True
        
    return False
