#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
s7_server.py — 斐讯S7体脂秤 本地数据服务器 v3

实测协议(抓包验证, 2026-10):
  帧结构(明文视角): 55 AA | 00 <方向> | 产品ID(8) | 秤MAC(6) | 目标MAC(6) | 00 00
                    | <cmd> <sub> | 版本/参数 | ... | CRC16-MODBUS([2:L-4)) @ [L-2:L) 大端
  帧长可变: 32B帧CRC@[30:32), 48B帧CRC@[46:48)
  编码: 可能整帧XOR 0x55, 也可能明文 —— 服务器自动识别, 并用同编码回话
  观测命令:
    [3]=0x30(秤->服务器,长帧) / 0x20(秤->x,短帧) / 0x31(服务器->秤,应答,实验)
    [26]=0x80 [27]=0x00  hello/注册        (应答 0xC0/0x00 -> 秤回 0x80/0x52, 实测有效)
    [26]=0x80 [27]=0x52  hello的后续(时间/配置请求? 待解)
  应答规则(逆向自UDP配网规律): [26] |= 0x40, [3] |= 0x01, 其余回显
"""
import argparse
import base64
import datetime
import json
import socket
import sys
import threading
import urllib.error
import urllib.request

XOR_KEY = 0x55

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass


class GithubSync:
    """测量记录自动推送到GitHub仓库(Contents API, 无第三方依赖)"""

    def __init__(self, repo, token, path="measurements.jsonl", branch=None):
        self.repo, self.token, self.path, self.branch = repo, token, path, branch
        self.sha = None          # 已知文件sha, 避免每次list
        self.fail_until = 0

    def _api(self, method, url, body=None):
        req = urllib.request.Request(url, method=method, data=body)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/vnd.github+json")
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"{}")

    def push(self, full_text: str):
        import time as _t
        if _t.time() < self.fail_until:
            return False
        try:
            if self.sha is None:
                q = f"?ref={self.branch}" if self.branch else ""
                try:
                    st, js = self._api("GET",
                        f"https://api.github.com/repos/{self.repo}/contents/{self.path}{q}")
                    self.sha = js.get("sha")
                except urllib.error.HTTPError as e:
                    if e.code != 404:
                        raise
                    self.sha = None
            body = json.dumps({
                "message": f"weight {datetime.datetime.now():%Y-%m-%d %H:%M}",
                "content": base64.b64encode(full_text.encode()).decode(),
                **({"sha": self.sha} if self.sha else {}),
                **({"branch": self.branch} if self.branch else {}),
            }).encode()
            st, js = self._api("PUT",
                f"https://api.github.com/repos/{self.repo}/contents/{self.path}", body)
            self.sha = js.get("content", {}).get("sha", self.sha)
            print(f"[{ts()}] [GH] 已推送 -> {self.repo}/{self.path} (commit {js.get('commit',{}).get('sha','')[:7]})")
            return True
        except Exception as e:
            print(f"[{ts()}] [GH] 推送失败(10分钟内暂停): {e}")
            self.fail_until = _t.time() + 600
            return False


def ts() -> str:
    return datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]


def hx(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)


def crc16_modbus(data: bytes, init: int = 0xFFFF) -> int:
    crc = init
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def xor55(b: bytes) -> bytes:
    return bytes(x ^ XOR_KEY for x in b)


def unwrap(wire: bytes):
    """识别编码: 返回(明文, 是否XOR过) 或 (None, False)"""
    if len(wire) >= 4 and wire[0] == 0x55 and wire[1] == 0xAA:
        return wire, False
    dec = xor55(wire)
    if len(dec) >= 4 and dec[0] == 0x55 and dec[1] == 0xAA:
        return dec, True
    return None, False


def frame_crc(plain: bytes) -> int:
    return crc16_modbus(plain[2:len(plain) - 4])


def build_reply(plain: bytes) -> bytes:
    """通用应答: 回显请求帧, [3]|=0x01, [26]|=0x40, [18:24)=秤MAC, 重算CRC
    0x52(疑似时间请求)的应答扩展为40字节, param携带当前unix时间戳"""
    f = bytearray(plain)
    f[3] |= 0x01
    f[18:24] = f[12:18]
    f[26] |= 0x40
    if f[27] == 0x52:                      # 时间同步实验: 40字节帧携带unix时间
        import time as _t
        now = int(_t.time())
        body = bytearray(plain[:28])       # 复制头部(含cmd 80 52)
        body[3] = 0x21
        body[18:24] = body[12:18]
        body[26] = 0xC0
        body += now.to_bytes(4, "big")     # param: unix秒 大端 ×2 (格式未定,双保险)
        body += now.to_bytes(4, "big")
        crc = crc16_modbus(bytes(body[2:]))
        return bytes(body) + b"\x00\x00" + bytes([(crc >> 8) & 0xFF, crc & 0xFF])
    crc = crc16_modbus(bytes(f[2:len(f) - 4]))
    f[len(f) - 2] = (crc >> 8) & 0xFF
    f[len(f) - 1] = crc & 0xFF
    return bytes(f)


def parse_measure(dec: bytes) -> dict:
    """解析 0x62 称重记录帧 (实测63.00kg=0x189C锁定体重字段)"""
    p = dec[28:len(dec) - 4]
    out = {"raw_param": p.hex()}
    if len(p) >= 17:
        out.update({
            "count": p[0],
            "unknown7c": p[1],
            "timestamp_raw": int.from_bytes(p[2:6], "big"),
            "weight_kg": int.from_bytes(p[15:17], "big") / 100.0,
            "impedance_channels": [int.from_bytes(p[i:i + 2], "big")
                                   for i in range(18, min(30, len(p) - 1), 2)],
        })
    return out


def describe(dec: bytes) -> str:
    L = len(dec)
    crc_got = (dec[L - 2] << 8) | dec[L - 1]
    crc_ok = crc_got == frame_crc(dec)
    return (f"[{dec[3]:02x}|{dec[26]:02x}{dec[27]:02x}] MAC={dec[12:18].hex(':')} "
            f"param={dec[28:L-4].hex()} CRC={'OK' if crc_ok else 'FAIL'}")


def handle_conn(conn: socket.socket, addr, logf, reply_on: bool, gh=None, status=None):
    if status is not None:
        status["conns"] = status.get("conns", 0) + 1
        status["scale_ip"] = addr[0]
        status["last_seen"] = datetime.datetime.now().isoformat()
    print(f"[{ts()}] [+] 秤已连接: {addr[0]}:{addr[1]}")
    logf.write(f"\n==== CONNECT {addr[0]}:{addr[1]} @ {datetime.datetime.now().isoformat()} ====\n")
    logf.flush()
    conn.settimeout(120)
    total = 0
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                print(f"[{ts()}] [-] {addr[0]} 断开(EOF) 共{total}B")
                break
            total += len(data)
            print(f"[{ts()}] [<] {addr[0]} {len(data)}B(累计{total}B)")
            print(f"        wire: {hx(data)}")
            dec, was_xored = unwrap(data)
            if dec is None:
                print("        ?非标准帧(无55AA头)")
                logf.write(f"RECV {len(data)}B RAW\n{hx(data)}\n"); logf.flush()
                continue
            print(f"        明文{'(XOR解码)' if was_xored else '(明文)'}: {hx(dec)}")
            print(f"        解码: {describe(dec)}")
            logf.write(f"RECV {'XOR' if was_xored else 'RAW'}\n{hx(dec)}\n"); logf.flush()
            if dec[26] == 0x80 and dec[27] == 0x62:      # 称重记录
                m = parse_measure(dec)
                m["time_local"] = datetime.datetime.now().isoformat()
                m["mac"] = dec[12:18].hex(":")
                print(f"        ★ 称重: {m['weight_kg']:.2f} kg  时间戳raw={m['timestamp_raw']} "
                      f"阻抗={m.get('impedance_channels')}")
                with open("measurements.jsonl", "a", encoding="utf-8") as mf:
                    mf.write(json.dumps(m, ensure_ascii=False) + "\n")
                if status is not None:
                    status["last"] = m
                    status["last_seen"] = m["time_local"]
                if gh:
                    with open("measurements.jsonl", encoding="utf-8") as mf:
                        gh.push(mf.read())
            if reply_on:
                reply_plain = build_reply(dec)
                reply_wire = xor55(reply_plain) if was_xored else reply_plain
                try:
                    conn.sendall(reply_wire)
                    print(f"[{ts()}] [--] 应答: {hx(reply_wire)}")
                    logf.write(f"SEND {'XOR' if was_xored else 'RAW'}\n{hx(reply_plain)}\n"); logf.flush()
                except OSError as e:
                    print(f"[{ts()}] [!] 应答失败: {e}")
    except socket.timeout:
        print(f"[{ts()}] [-] {addr[0]} 120s无数据")
    except OSError as e:
        print(f"[{ts()}] [-] {addr[0]} 异常: {e}")
    finally:
        logf.write(f"==== DISCONNECT {addr[0]} @ {datetime.datetime.now().isoformat()} total={total}B ====\n")
        logf.flush()
        try:
            conn.close()
        except OSError:
            pass


def start_http(port_http: int, status: dict):
    """HTTP状态服务: 给桌面小部件/浏览器提供数据
    /widget  纯文本一行(适合小部件)   /latest 最新一条JSON
    /all     全部记录JSONL            /status  服务器状态"""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            try:
                if self.path == "/widget":
                    last = status.get("last")
                    body = (f"{last['weight_kg']:.1f} kg · "
                            f"{last['time_local'][5:16].replace('T', ' ')}"
                            if last else "暂无称重").encode()
                    ctype = "text/plain; charset=utf-8"
                elif self.path == "/latest":
                    import json as _j
                    body = _j.dumps(status.get("last") or {}, ensure_ascii=False).encode()
                    ctype = "application/json; charset=utf-8"
                elif self.path == "/all":
                    with open("measurements.jsonl", encoding="utf-8") as f:
                        body = f.read().encode()
                    ctype = "application/json; charset=utf-8"
                elif self.path == "/status":
                    import json as _j
                    body = _j.dumps({
                        "scale_ip": status.get("scale_ip"),
                        "connections": status.get("conns", 0),
                        "last_seen": status.get("last_seen"),
                        "started": status.get("started"),
                    }, ensure_ascii=False).encode()
                    ctype = "application/json; charset=utf-8"
                else:
                    self.send_response(404); self.end_headers(); return
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                self.send_response(500); self.end_headers()
                self.wfile.write(str(e).encode())

    srv = ThreadingHTTPServer(("0.0.0.0", port_http), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[*] HTTP状态服务 http://0.0.0.0:{port_http}/widget|/latest|/all|/status")
    return srv


def udp_sniff(port: int):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", port))
        s.settimeout(1.0)
    except OSError:
        return
    print(f"[*] UDP :{port} 旁听已启动")
    while True:
        try:
            data, addr = s.recvfrom(2048)
        except (socket.timeout, OSError):
            continue
        print(f"[{ts()}] [<UDP] {addr[0]}:{addr[1]} {len(data)}B: {hx(data[:64])}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--log", default="s7_capture.log")
    ap.add_argument("--no-reply", action="store_true", help="只抓包不应答")
    ap.add_argument("--gh-repo", default=None, help="GitHub仓库, 如 yourname/s7-data (私有仓库亦可)")
    ap.add_argument("--gh-token", default=None, help="GitHub fine-grained PAT(需contents写权限)")
    ap.add_argument("--gh-path", default="measurements.jsonl", help="仓库内文件路径")
    ap.add_argument("--gh-branch", default=None, help="分支(默认仓库默认分支)")
    ap.add_argument("--http-port", type=int, default=8898, help="HTTP状态端口(0=关闭, 默认8898)")
    args = ap.parse_args()

    gh = None
    if args.gh_repo and args.gh_token:
        gh = GithubSync(args.gh_repo, args.gh_token, args.gh_path, args.gh_branch)
        print(f"[*] GitHub同步已启用 -> {args.gh_repo}/{args.gh_path}")

    status = {"conns": 0, "last": None, "last_seen": None,
              "scale_ip": None, "started": datetime.datetime.now().isoformat()}
    try:                                    # 预载最近一条称重(重启后小组件仍有数据)
        with open("measurements.jsonl", encoding="utf-8") as f:
            lines = [ln for ln in f.read().splitlines() if ln.strip()]
        if lines:
            status["last"] = json.loads(lines[-1])
    except (OSError, ValueError):
        pass
    if args.http_port:
        try:
            start_http(args.http_port, status)
        except OSError as e:
            print(f"[!] HTTP状态服务启动失败: {e}")

    logf = open(args.log, "a", encoding="utf-8")
    threading.Thread(target=udp_sniff, args=(args.port,), daemon=True).start()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("", args.port))
    srv.listen(4)
    mode = "抓包+自动应答" if not args.no_reply else "纯抓包"
    print(f"[*] S7 数据服务器 v3 已启动 TCP 0.0.0.0:{args.port}  模式: {mode}  日志: {args.log}")
    print("[*] 等待秤连接... (每次称重秤会主动连入)")
    while True:
        try:
            conn, addr = srv.accept()
        except KeyboardInterrupt:
            break
        threading.Thread(target=handle_conn,
                         args=(conn, addr, logf, not args.no_reply, gh, status), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[*] 退出")
