import os
import sys
import subprocess
import time


def _pasta_base():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def _encontrar_server(pasta):
    for candidata in (pasta, os.path.join(pasta, "AcessoMaterno-master")):
        alvo = os.path.join(candidata, "server.py")
        if os.path.exists(alvo):
            return candidata, alvo
    # sobe dois niveis procurando
    for _ in range(2):
        pasta = os.path.dirname(pasta)
        alvo = os.path.join(pasta, "server.py")
        if os.path.exists(alvo):
            return pasta, alvo
    return None, None


def _encontrar_python(pasta_server):
    for p in (pasta_server, os.path.dirname(pasta_server)):
        venv_py = os.path.join(p, "venv", "Scripts", "python.exe")
        if os.path.exists(venv_py):
            return venv_py
        venv_py2 = os.path.join(p, "venv_desktop", "Scripts", "python.exe")
        if os.path.exists(venv_py2):
            return venv_py2
    return "pythonw.exe"  # fallback: python sem console do sistema


def main():
    base = _pasta_base()
    pasta_server, server_py = _encontrar_server(base)
    if not server_py:
        try:
            with open(os.path.join(base, "ERRO_LANCADOR.txt"), "w", encoding="utf-8") as f:
                f.write("server.py nao encontrado a partir de: " + base)
        except Exception:
            pass
        return
    python_exe = _encontrar_python(pasta_server)

    # Mata processos antigos nas portas 27103/27104 antes de subir
    try:
        for porta in ("27103", "27104"):
            out = subprocess.check_output(["netstat", "-ano"], text=True, errors="ignore")
            for linha in out.splitlines():
                if (":" + porta) in linha and "LISTENING" in linha.upper():
                    partes = linha.split()
                    if partes and partes[-1].isdigit():
                        subprocess.run(["taskkill", "/F", "/PID", partes[-1]], capture_output=True)
                        time.sleep(1)
    except Exception:
        pass

    CREATE_NO_WINDOW = 0x08000000
    DETACHED_PROCESS = 0x00000008
    try:
        subprocess.Popen(
            [python_exe, server_py, "--sem-janela"],
            cwd=pasta_server,
            creationflags=CREATE_NO_WINDOW | DETACHED_PROCESS,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        # fallback: usa python.exe normal (pode abrir console brevemente)
        subprocess.Popen(
            ["python.exe", server_py, "--sem-janela"],
            cwd=pasta_server,
            creationflags=DETACHED_PROCESS,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    time.sleep(1)


if __name__ == "__main__":
    main()
