# ============================================================================
# inicializar.py — Lançador do Painel Acesso Materno + Túnel Cloudflare (27105)
# ----------------------------------------------------------------------------
# Compile para .exe de clique duplo com o PyInstaller (comando no README).
#
# Fluxo:
#   1) cd  C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master
#   2) taskkill em python.exe / pythonw.exe / "Acesso Materno.exe" (libera 27105)
#   3) Sobe o servidor em JANELA PROPRIA (visivel/minimizada):
#         py -3.12 server.py --sem-janela
#      (--sem-janela = o servidor NAO dispara o .exe sozinho; evita duplicidade
#       e evita que o .exe compilado mate o processo python via _escolher_porta)
#   4) Aguarda 4s (estabilizacao de RAM)
#   5) Abre .\dist\Acesso Materno.exe COM SOU_EXEC_INTERFACE=1 (env isolado):
#      o .exe detecta a flag logo apos freeze_support(), abre so a janela
#      visual e morre em sys.exit(0) — nunca roda Uvicorn, nunca mata o servidor.
# ============================================================================
import os
import sys
import time
import subprocess

PASTA_PROJETO = r"C:\Users\suporte\Documents\acessomaterno\AcessoMaterno-master"
EXE_INTERFACE = os.path.join(PASTA_PROJETO, "dist", "Acesso Materno.exe")
URL_TUNEL = "https://megabrasiliautilidades.store"


def _log(msg):
    """Se estiver compilado com --windowed nao ha console; imprime mesmo assim."""
    try:
        print(msg, flush=True)
    except Exception:
        pass


def _matar_processos_antigos():
    """Libera a porta 27105 matando instancias antigas (Windows)."""
    if os.name != "nt":
        return
    for imagem in ("python.exe", "pythonw.exe", "Acesso Materno.exe"):
        try:
            subprocess.run(
                ["taskkill", "/F", "/IM", imagem],
                capture_output=True, timeout=10,
            )
        except Exception:
            pass
    # Pequena pausa para o SO fechar os sockets da porta 27105
    time.sleep(1.0)


def _subir_servidor():
    """Sobe py -3.12 server.py em console proprio (visivel). Ajuste a flag
    creationflags se quiser oculto: CREATE_NO_WINDOW em vez de CREATE_NEW_CONSOLE."""
    env_serv = os.environ.copy()
    # Sobra de seguranca: alem de --sem-janela, força a variavel tambem.
    env_serv["ACESSO_MATERNO_SEM_JANELA"] = "1"
    # Garante que o servidor NUNCA veja SOU_EXEC_INTERFACE (senão ele abre só
    # a janela e morre em sys.exit(0) sem subir Uvicorn).
    env_serv.pop("SOU_EXEC_INTERFACE", None)

    CREATE_NEW_CONSOLE = getattr(subprocess, "CREATE_NEW_CONSOLE", 0)
    subprocess.Popen(
        ["py", "-3.12", "server.py", "--sem-janela"],
        cwd=PASTA_PROJETO,
        env=env_serv,
        creationflags=CREATE_NEW_CONSOLE,  # janela de terminal visivel do servidor
    )
    _log("[OK] Servidor FastAPI+Socket.IO iniciado na porta 27105 (console proprio).")


def _abrir_interface():
    """Abre o .exe da interface COM SOU_EXEC_INTERFACE=1 isolado."""
    if os.path.exists(EXE_INTERFACE):
        env_iface = os.environ.copy()
        env_iface["SOU_EXEC_INTERFACE"] = "1"  # <=== anti-loop / anti-conflito
        # Destaque/isolamento no Windows
        flags = 0
        if os.name == "nt":
            flags = (
                getattr(subprocess, "DETACHED_PROCESS", 0)
                | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            )
        subprocess.Popen(
            [EXE_INTERFACE],
            cwd=os.path.dirname(EXE_INTERFACE),
            env=env_iface,
            creationflags=flags,
            close_fds=True,
        )
        _log(f"[OK] Interface visual aberta: {EXE_INTERFACE} (SOU_EXEC_INTERFACE=1).")
    else:
        _log(f"[AVISO] .exe nao encontrado em {EXE_INTERFACE}; abrindo túnel no navegador.")
        import webbrowser
        webbrowser.open(URL_TUNEL)


def main():
    os.chdir(PASTA_PROJETO)
    _log("[1/4] Limpeza de processos antigos (python/pythonw/Acesso Materno)...")
    _matar_processos_antigos()

    _log("[2/4] Subindo servidor em background (porta 27105)...")
    _subir_servidor()

    _log("[3/4] Aguardando 4s para estabilizacao da RAM...")
    time.sleep(4)

    _log("[4/4] Abrindo interface visual...")
    _abrir_interface()

    _log("Concluido. O servidor continua rodando no console proprio.")


if __name__ == "__main__":
    # OBRIGATORIO se este lançador tambem for compilado (PyInstaller/Windows).
    import multiprocessing
    multiprocessing.freeze_support()
    main()
