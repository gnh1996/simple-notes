"""WSL docker.sock -> Windows TCP 2375 转发（零安装，供 Windows 侧 Testcontainers 用）。

仓库根目录下执行：wsl python3 scripts/wsl-docker-proxy.py
Windows 侧设置 $env:DOCKER_HOST='tcp://localhost:2375' 后再跑 ./mvnw test。
停止： taskkill 掉 wsl 下的 python3 进程即可。
"""

import socket
import threading

# 只绑回环地址：WSL2 的 localhost 自动映射到 Windows 宿主；
# 绝不能放 0.0.0.0，无认证的 daemon 暴露给局域网等于送 root。
LISTEN_HOST = "127.0.0.1"
LISTEN_PORT = 2375
SOCK_PATH = "/var/run/docker.sock"


def pipe(a: socket.socket, b: socket.socket) -> None:
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def handle(client: socket.socket) -> None:
    upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        upstream.connect(SOCK_PATH)
    except OSError:
        client.close()
        return
    t = threading.Thread(target=pipe, args=(client, upstream), daemon=True)
    t.start()
    pipe(upstream, client)


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(16)
    print(
        f"docker proxy listening on {LISTEN_HOST}:{LISTEN_PORT} -> {SOCK_PATH}",
        flush=True,
    )
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
