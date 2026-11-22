# -*- coding: utf-8 -*-
"""
PainelChaves_CEO.py — Gerador de Chaves do CEO (Acesso Materno)
================================================================
Painel LOCAL (só na máquina do CEO) para criar chaves de licença no Supabase
sem precisar escrever SQL. Preencha nome do cliente, subdomínio (servidor) e os
dias (ou marque Vitalício) e clique em "Criar Chave".

SEGURANÇA
---------
- Usa a chave SECRETA (service_role) lida de 'ceo_secret.local.json', que está
  no .gitignore e NUNCA deve ir para o cliente, GitHub ou build do .exe.
- Este arquivo é uma ferramenta do CEO; não é incluído no build do cliente.

Como abrir: dê 2 cliques em ABRIR_PAINEL_CHAVES.bat (ou rode: py PainelChaves_CEO.py)
"""

import os
import sys
import json
import random
import string
import datetime
import urllib.request
import urllib.error

from PyQt6.QtWidgets import (
    QApplication, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit,
    QPushButton, QSpinBox, QCheckBox, QMessageBox, QFrame, QPlainTextEdit
)
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QIcon, QGuiApplication


# ---------------------------------------------------------------------------
# Credenciais (lidas do arquivo local e secreto)
# ---------------------------------------------------------------------------
def _base_dir():
    return getattr(sys, "_MEIPASS", None) or os.path.dirname(os.path.abspath(__file__))


def carregar_credenciais():
    caminho = os.path.join(_base_dir(), "ceo_secret.local.json")
    if not os.path.exists(caminho):
        raise FileNotFoundError(
            "Arquivo 'ceo_secret.local.json' não encontrado na pasta.\n"
            "Ele guarda a chave secreta do Supabase (service_role)."
        )
    with open(caminho, "r", encoding="utf-8") as f:
        dados = json.load(f)
    url = (dados.get("SUPABASE_URL") or "").strip().rstrip("/")
    secret = (dados.get("SUPABASE_SERVICE_ROLE") or "").strip()
    if not url or not secret:
        raise ValueError("ceo_secret.local.json está sem SUPABASE_URL ou SUPABASE_SERVICE_ROLE.")
    return url, secret


# ---------------------------------------------------------------------------
# Supabase REST (tabela public.licenses)
# ---------------------------------------------------------------------------
# Evita caracteres ambíguos (0/O, 1/I) para o cliente não errar ao digitar.
_ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def gerar_license_key():
    g1 = "".join(random.choice(_ALFABETO) for _ in range(4))
    g2 = "".join(random.choice(_ALFABETO) for _ in range(4))
    return f"ACESSOMATERNO-{g1}-{g2}"


def _req(url, secret, metodo="GET", corpo=None, extra_headers=None):
    headers = {
        "apikey": secret,
        "Authorization": f"Bearer {secret}",
        "Content-Type": "application/json",
    }
    if extra_headers:
        headers.update(extra_headers)
    data = json.dumps(corpo).encode("utf-8") if corpo is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=metodo)
    with urllib.request.urlopen(req, timeout=30) as r:
        txt = r.read().decode("utf-8", "ignore")
        return json.loads(txt) if txt.strip() else []


def chave_existe(base_url, secret, chave):
    url = f"{base_url}/rest/v1/licenses?license_key=eq.{chave}&select=license_key"
    try:
        return len(_req(url, secret)) > 0
    except Exception:
        return False  # em caso de falha de rede, segue (o INSERT ainda protege)


def criar_chave(base_url, secret, cliente_nome, server_url, dias, vitalicio, route_cidr=""):
    # Gera uma chave única (tenta algumas vezes)
    chave = gerar_license_key()
    for _ in range(6):
        if not chave_existe(base_url, secret, chave):
            break
        chave = gerar_license_key()

    agora = datetime.datetime.now(datetime.timezone.utc)
    if vitalicio:
        duration_days = 36500  # ~100 anos
        expires_at = "2099-12-31T00:00:00+00:00"
    else:
        duration_days = int(dias)
        expires_at = (agora + datetime.timedelta(days=int(dias))).isoformat()

    registro = {
        "license_key": chave,
        "status": "active",
        "duration_days": duration_days,
        "expires_at": expires_at,
        "hwid": None,                       # vazio: trava na 1ª ativação do cliente
        "cliente_nome": cliente_nome or None,
        "server_url": server_url or None,
        "route_cidr": route_cidr or None,
    }

    url = f"{base_url}/rest/v1/licenses"
    _req(url, secret, metodo="POST", corpo=registro,
         extra_headers={"Prefer": "return=minimal"})
    return chave, expires_at, duration_days


def listar_ultimas(base_url, secret, limite=8):
    url = (f"{base_url}/rest/v1/licenses?select=license_key,cliente_nome,"
           f"server_url,status,expires_at,hwid&order=created_at.desc&limit={limite}")
    return _req(url, secret)


# ---------------------------------------------------------------------------
# Interface
# ---------------------------------------------------------------------------
ESTILO = """
QWidget { background: #0d0d0d; color: #e5e7eb; font-size: 13px; }
QLabel#titulo { font-size: 20px; font-weight: 800; color: #ef4444; }
QLabel#sub { color: #9ca3af; font-size: 12px; }
QLabel.campo { color: #cbd5e1; font-weight: 700; }
QLineEdit, QSpinBox {
    background: #1a1a1a; border: 1px solid #333; border-radius: 8px;
    padding: 8px; color: #fff;
}
QLineEdit:focus, QSpinBox:focus { border: 1px solid #ef4444; }
QPushButton#criar {
    background: #dc2626; color: #fff; font-weight: 800; padding: 11px;
    border: none; border-radius: 10px; font-size: 14px;
}
QPushButton#criar:hover { background: #ef4444; }
QPushButton { background: #1f2937; color: #e5e7eb; border: 1px solid #374151;
    border-radius: 8px; padding: 7px 10px; }
QPushButton:hover { background: #374151; }
QPlainTextEdit { background: #111; border: 1px solid #333; border-radius: 8px;
    color: #9ca3af; font-family: Consolas, monospace; font-size: 12px; }
QFrame#linha { background: #222; max-height: 1px; min-height: 1px; }
QCheckBox { font-weight: 700; }
"""


class PainelChaves(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Painel de Chaves — CEO (Acesso Materno)")
        try:
            ico = os.path.join(_base_dir(), "icone.ico")
            if os.path.exists(ico):
                self.setWindowIcon(QIcon(ico))
        except Exception:
            pass
        self.resize(560, 640)
        self.setStyleSheet(ESTILO)

        try:
            self.base_url, self.secret = carregar_credenciais()
        except Exception as e:
            QMessageBox.critical(self, "Erro de credencial", str(e))
            sys.exit(1)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(22, 20, 22, 20)
        lay.setSpacing(10)

        t = QLabel("🔑 Gerador de Chaves do CEO")
        t.setObjectName("titulo")
        lay.addWidget(t)
        s = QLabel("Preencha e clique em Criar Chave. A chave já é gravada no Supabase.")
        s.setObjectName("sub")
        lay.addWidget(s)

        linha = QFrame(); linha.setObjectName("linha"); lay.addWidget(linha)

        # Nome do cliente
        lay.addWidget(self._lbl("Nome do cliente"))
        self.in_nome = QLineEdit(); self.in_nome.setPlaceholderText("Ex: Maria Silva / Loja do João")
        lay.addWidget(self.in_nome)

        # Subdomínio / servidor
        lay.addWidget(self._lbl("Subdomínio (servidor do cliente)"))
        self.in_server = QLineEdit()
        self.in_server.setPlaceholderText("Ex: https://cliente1.megabrasiliautilidades.store")
        lay.addWidget(self.in_server)

        # Dias + vitalício
        linha_dias = QHBoxLayout()
        box_dias = QVBoxLayout()
        box_dias.addWidget(self._lbl("Dias de validade"))
        self.in_dias = QSpinBox(); self.in_dias.setRange(1, 36500); self.in_dias.setValue(30)
        box_dias.addWidget(self.in_dias)
        linha_dias.addLayout(box_dias)

        box_vit = QVBoxLayout()
        box_vit.addWidget(self._lbl("Plano"))
        self.chk_vitalicio = QCheckBox("♾️  Vitalício (até 2099)")
        self.chk_vitalicio.toggled.connect(
            lambda on: self.in_dias.setEnabled(not on))
        box_vit.addWidget(self.chk_vitalicio)
        linha_dias.addLayout(box_vit)
        lay.addLayout(linha_dias)

        # Botão criar
        self.btn_criar = QPushButton("＋  Criar Chave")
        self.btn_criar.setObjectName("criar")
        self.btn_criar.clicked.connect(self.ao_criar)
        lay.addWidget(self.btn_criar)

        # Resultado
        self.lbl_result = QLabel("")
        self.lbl_result.setWordWrap(True)
        self.lbl_result.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.lbl_result.setStyleSheet("font-size:16px; font-weight:800; color:#22c55e; padding:6px 0;")
        lay.addWidget(self.lbl_result)

        self.btn_copiar = QPushButton("📋  Copiar chave")
        self.btn_copiar.clicked.connect(self.copiar_chave)
        self.btn_copiar.setVisible(False)
        lay.addWidget(self.btn_copiar)

        linha2 = QFrame(); linha2.setObjectName("linha"); lay.addWidget(linha2)

        # Lista das últimas chaves
        cab = QHBoxLayout()
        cab.addWidget(self._lbl("Últimas chaves criadas"))
        btn_atualizar = QPushButton("🔄 Atualizar")
        btn_atualizar.clicked.connect(self.atualizar_lista)
        cab.addStretch(1); cab.addWidget(btn_atualizar)
        lay.addLayout(cab)

        self.lista = QPlainTextEdit(); self.lista.setReadOnly(True)
        self.lista.setMinimumHeight(170)
        lay.addWidget(self.lista)

        self._ultima_chave = ""
        self.atualizar_lista()

    def _lbl(self, txt):
        l = QLabel(txt); l.setProperty("class", "campo")
        l.setStyleSheet("color:#cbd5e1; font-weight:700; margin-top:4px;")
        return l

    def ao_criar(self):
        server = self.in_server.text().strip()
        if not self.chk_vitalicio.isChecked() and self.in_dias.value() < 1:
            QMessageBox.warning(self, "Aviso", "Informe os dias de validade.")
            return
        if not server:
            r = QMessageBox.question(
                self, "Sem subdomínio",
                "Você não preencheu o subdomínio (servidor).\n"
                "A chave vai funcionar, mas o cliente NÃO ficará ligado a um servidor.\n\n"
                "Deseja criar mesmo assim?")
            if r != QMessageBox.StandardButton.Yes:
                return

        self.btn_criar.setEnabled(False)
        self.btn_criar.setText("Criando...")
        QApplication.processEvents()
        try:
            chave, expires_at, dd = criar_chave(
                self.base_url, self.secret,
                cliente_nome=self.in_nome.text().strip(),
                server_url=server,
                dias=self.in_dias.value(),
                vitalicio=self.chk_vitalicio.isChecked(),
            )
            self._ultima_chave = chave
            quando = "Vitalício (2099)" if self.chk_vitalicio.isChecked() else f"{self.in_dias.value()} dias"
            self.lbl_result.setText(f"✅ Chave criada:\n{chave}\n({quando})")
            self.btn_copiar.setVisible(True)
            QGuiApplication.clipboard().setText(chave)
            self.atualizar_lista()
        except urllib.error.HTTPError as e:
            corpo = ""
            try: corpo = e.read().decode("utf-8", "ignore")
            except Exception: pass
            QMessageBox.critical(self, "Erro do Supabase", f"HTTP {e.code}\n{corpo}")
        except Exception as e:
            QMessageBox.critical(self, "Erro", f"Não foi possível criar a chave:\n{e}")
        finally:
            self.btn_criar.setEnabled(True)
            self.btn_criar.setText("＋  Criar Chave")

    def copiar_chave(self):
        if self._ultima_chave:
            QGuiApplication.clipboard().setText(self._ultima_chave)
            self.btn_copiar.setText("✔ Copiada!")
            QApplication.processEvents()

    def atualizar_lista(self):
        try:
            linhas = listar_ultimas(self.base_url, self.secret)
            if not linhas:
                self.lista.setPlainText("(nenhuma chave ainda)")
                return
            out = []
            for r in linhas:
                ativa = "🟢 em uso" if r.get("hwid") else "⚪ livre"
                nome = r.get("cliente_nome") or "—"
                srv = r.get("server_url") or "sem servidor"
                exp = (r.get("expires_at") or "")[:10]
                out.append(f"{r.get('license_key')}  |  {nome}  |  {srv}  |  exp {exp}  |  {ativa}")
            self.lista.setPlainText("\n".join(out))
        except Exception as e:
            self.lista.setPlainText(f"(não foi possível carregar a lista: {e})")


def main():
    app = QApplication(sys.argv)
    w = PainelChaves()
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
