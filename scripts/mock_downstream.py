#!/usr/bin/env python3
"""M1 Gate 手工验收用的下游 mock 服务。

基础用法（返回 200）:
    python3 scripts/mock_downstream.py --port 9001

M2 故障场景复用:
    python3 scripts/mock_downstream.py --port 9001 --status 500   # 恒定 500
    python3 scripts/mock_downstream.py --port 9001 --sleep 20     # 慢响应（触发 15s 超时）

压测（T4.2）用 --quiet：不逐条打印，只每 2000 条报一次进度。
    150 QPS 下逐条 flush 到终端本身就会成为瓶颈，把 mock 变成压测的短板。

每次收到请求打印一行：序号 | 方法 | 路径 | Authorization | body 长度。
"""
import argparse
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COUNTER = {"n": 0}
LOCK = threading.Lock()


def make_handler(status: int, sleep_s: float, quiet: bool = False):
    class Handler(BaseHTTPRequestHandler):
        def _respond(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            with LOCK:
                COUNTER["n"] += 1
                n = COUNTER["n"]
            if quiet:
                if n % 2000 == 0:
                    print(f"[mock] 已收 {n} 条", file=sys.stderr, flush=True)
            else:
                print(f"[mock #{n}] {self.command} {self.path} "
                      f"auth={self.headers.get('Authorization')} body_len={length}", flush=True)
            if sleep_s > 0:
                import time
                time.sleep(sleep_s)
            self.send_response(status)
            payload = b'{"ok":true}'
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _respond

        def log_message(self, *args):  # 关闭默认访问日志，只保留我们的一行式记录
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9001)
    ap.add_argument("--status", type=int, default=200)
    ap.add_argument("--sleep", type=float, default=0)
    ap.add_argument("--quiet", action="store_true", help="不逐条打印，只报进度（压测用）")
    args = ap.parse_args()

    srv = ThreadingHTTPServer(("127.0.0.1", args.port),
                              make_handler(args.status, args.sleep, args.quiet))
    print(f"mock downstream on 127.0.0.1:{args.port} status={args.status} sleep={args.sleep}s", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
