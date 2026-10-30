"""
Servidor WebSocket de TESTE para o AcessoMaterno (somente biblioteca padrão do Python).

Uso:  python tools/servidor_teste.py
      (emuladores do Android Studio e MSI App Player chegam aqui por ws://10.0.2.2:8080;
       celular via USB precisa de "adb reverse tcp:8080 tcp:8080" e da URL 127.0.0.1)
Depois ative a "Escuta Remota" no app (build debug, no emulador) e digite comandos aqui:

  texto Instalar            -> clica no elemento com esse texto
  exato Abrir               -> clica só se o texto for idêntico
  clique 300 800            -> clica nas coordenadas
  swipe 100 1000 900 1000   -> desliza
  voltar | home | notificacoes
  {"action": "..."}         -> envia o JSON exatamente como digitado
  sair

Escuta apenas em 127.0.0.1 (o NAT dos emuladores e o adb reverse entregam por aqui; outros computadores da rede não).
"""
import base64
import hashlib
import json
import socket
import struct
import threading

HOST, PORT, PATH = "127.0.0.1", 8080, "/ws"
AUTH_TOKEN = "teste-local"  # o mesmo da build debug em app/build.gradle.kts
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

clients = []
lock = threading.Lock()


def recv_exact(conn, n):
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise ConnectionError
        data += chunk
    return data


def send_frame(conn, opcode, payload=b""):
    header = bytes([0x80 | opcode])
    size = len(payload)
    if size < 126:
        header += bytes([size])
    elif size < 65536:
        header += bytes([126]) + struct.pack(">H", size)
    else:
        header += bytes([127]) + struct.pack(">Q", size)
    conn.sendall(header + payload)


def handshake(conn):
    request = b""
    while b"\r\n\r\n" not in request:
        chunk = conn.recv(4096)
        if not chunk:
            return False
        request += chunk
    lines = request.decode("latin-1").split("\r\n")
    path = lines[0].split(" ")[1] if len(lines[0].split(" ")) > 1 else ""
    headers = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}

    if path != PATH or "sec-websocket-key" not in headers:
        conn.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
        return False
    if headers.get("authorization") != f"Bearer {AUTH_TOKEN}":
        print("\n[!] Conexão recusada: token inválido")
        conn.sendall(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
        return False

    accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + GUID).encode()).digest()).decode()
    conn.sendall(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
    )
    return True


def handle_client(conn, addr):
    try:
        if not handshake(conn):
            return
        with lock:
            clients.append(conn)
        print(f"\n[+] Celular conectado ({addr[0]})\n> ", end="", flush=True)
        while True:
            b1, b2 = recv_exact(conn, 2)
            opcode, size = b1 & 0x0F, b2 & 0x7F
            if size == 126:
                size = struct.unpack(">H", recv_exact(conn, 2))[0]
            elif size == 127:
                size = struct.unpack(">Q", recv_exact(conn, 8))[0]
            mask = recv_exact(conn, 4) if b2 & 0x80 else b"\0\0\0\0"
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(recv_exact(conn, size)))

            if opcode == 0x8:  # close
                send_frame(conn, 0x8, payload[:2])
                break
            if opcode == 0x9:  # ping do OkHttp -> responde pong (senão a conexão cai)
                send_frame(conn, 0xA, payload)
            elif opcode == 0x1:
                print(f"\n[resposta] {payload.decode('utf-8', 'replace')}\n> ", end="", flush=True)
    except (ConnectionError, OSError):
        pass
    finally:
        with lock:
            if conn in clients:
                clients.remove(conn)
                print("\n[-] Celular desconectado\n> ", end="", flush=True)
        conn.close()


def parse_command(line):
    if line.startswith("{"):
        json.loads(line)  # valida antes de enviar
        return line
    cmd, _, rest = line.partition(" ")
    cmd, args = cmd.lower(), rest.split()
    if cmd == "texto":
        return json.dumps({"action": "click_text", "text": rest.strip()})
    if cmd == "exato":
        return json.dumps({"action": "click_text", "text": rest.strip(), "exact": True})
    if cmd == "clique" and len(args) == 2:
        return json.dumps({"action": "click", "x": float(args[0]), "y": float(args[1])})
    if cmd == "swipe" and len(args) == 4:
        x1, y1, x2, y2 = map(float, args)
        return json.dumps({"action": "swipe", "x1": x1, "y1": y1, "x2": x2, "y2": y2})
    if cmd in ("voltar", "home", "notificacoes"):
        return json.dumps({"action": {"voltar": "back", "notificacoes": "notifications"}.get(cmd, cmd)})
    return None


def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen()
    print(f"Servidor de teste em ws://{HOST}:{PORT}{PATH} (no emulador: ws://10.0.2.2:{PORT}{PATH})")
    print("Aguardando o celular... Comandos: texto <t>, exato <t>, clique x y, swipe x1 y1 x2 y2, voltar, home, notificacoes, sair")

    def accept_loop():
        while True:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()

    threading.Thread(target=accept_loop, daemon=True).start()

    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue
        if line == "sair":
            break
        try:
            message = parse_command(line)
        except ValueError:
            message = None
        if message is None:
            print("Comando não reconhecido.")
            continue
        with lock:
            targets = list(clients)
        if not targets:
            print("Nenhum celular conectado.")
            continue
        for conn in targets:
            try:
                send_frame(conn, 0x1, message.encode("utf-8"))
            except OSError:
                pass
        print(f"[enviado] {message}")


if __name__ == "__main__":
    main()
