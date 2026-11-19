import os
import shutil
import subprocess
import sys

def limpar_ambiente():
    print("[1/5] Limpando pastas temporárias e builds antigos...")
    pastas_para_remover = ["build", "build_dev", "build_dist"]
    for pasta in pastas_para_remover:
        if os.path.exists(pasta):
            shutil.rmtree(pasta, ignore_errors=True)

    for arquivo in os.listdir("."):
        if arquivo.endswith(".exe") and arquivo != "dist":
            try:
                os.remove(arquivo)
                print(f"  -> Removido executável antigo na raiz: {arquivo}")
            except Exception:
                pass

    if os.path.exists("dist"):
        shutil.rmtree("dist", ignore_errors=True)
    os.makedirs("dist", exist_ok=True)

def gerar_env_vars(modo: str, url: str):
    conteudo = f"""# Gerado automaticamente pelo script de build - NÃO EDITAR MANUALMENTE
BUILD_MODE = "{modo}"
EMBEDDED_URL = "{url}"
"""
    with open("env_vars.py", "w", encoding="utf-8") as f:
        f.write(conteudo)
    print(f"  -> env_vars.py gerado para o modo: {modo.upper()}")

def compilar_dev():
    print("\n[2/5] Compilando ambiente de DESENVOLVIMENTO (CEO na raiz)...")
    gerar_env_vars(modo="dev", url="https://megabrasiliautilidades.store")

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "Acesso Materno.spec",
        "--distpath", ".",
        "--workpath", "build_dev",
        "--clean",
        "--noconfirm"
    ]
    subprocess.run(cmd, check=True)
    print("[3/5] Build DEV concluído na raiz do projeto (Acesso Materno.exe).")

def compilar_dist():
    print("\n[4/5] Compilando ambiente de DISTRIBUIÇÃO (dist/ para o cliente)...")
    gerar_env_vars(modo="dist", url="")

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "Acesso Materno.spec",
        "--distpath", "dist",
        "--workpath", "build_dist",
        "--clean",
        "--noconfirm"
    ]
    subprocess.run(cmd, check=True)

    config_exemplo = (
        '{\n'
        '    "panel_url": "https://srv1.megabrasiliautilidades.store",\n'
        '    "android_project_dir": ""\n'
        '}'
    )
    # O build e onedir (COLLECT): o .exe fica em dist\Acesso Materno\, entao o
    # config.json precisa ficar NESSA pasta (ao lado do executavel), que e onde
    # o server.py o procura via os.path.dirname(sys.executable). Gravar em
    # dist\ (um nivel acima) fazia o .exe NUNCA ler o config.json.
    pasta_exe_dist = os.path.join("dist", "Acesso Materno")
    os.makedirs(pasta_exe_dist, exist_ok=True)
    with open(os.path.join(pasta_exe_dist, "config.json"), "w", encoding="utf-8") as f:
        f.write(config_exemplo)

    print("[5/5] Build DIST concluído em /dist (com config.json de exemplo).")

if __name__ == "__main__":
    try:
        limpar_ambiente()
        compilar_dev()
        compilar_dist()
        print("\n==============================================")
        print("[OK] TODOS OS BUILDS FORAM GERADOS COM SUCESSO!")
        print(" - Dev (Raiz): URL embutida do CEO.")
        print(" - Dist (/dist): Lê config.json externo.")
        print("==============================================")
    except subprocess.CalledProcessError as e:
        print(f"\n[ERRO] Falha durante a execução do PyInstaller: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"\n[ERRO INESPERADO] {e}")
        sys.exit(1)
