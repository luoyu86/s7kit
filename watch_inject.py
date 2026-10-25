#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""watch_inject.py — 秤保持指向PC期间, 一旦它醒来(巡检连PC/发0x10宣告),
立即向其 UDP 7550 注入新的服务器地址(手机APP), 实现无感改指向。

配合: 另开 s7_server.py --port 8899 --log <file> 让秤巡检有应答、保持正常状态。
触发源: 1) tail日志文件中的 '==== CONNECT <ip>:' 行  2) UDP 7551~7553 的 0x10 宣告帧
"""
import argparse
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from s7_provision import (AnnounceListener, build_response_ip, xor55,
                          UDP_SCALE_TX_PORT, hx)

KNOWN_MAC = bytes.fromhex("a47b9d03aadf")            # 秤MAC(历次抓包一致)
KNOWN_PRODUCT = b"\x00\x00\x00\x01\x00\x00\x00\x01"  # S7 产品ID


def inject(scale_ip, mac, product, srv_ip, srv_port):
    frame = xor55(build_response_ip(mac, socket.inet_aton(srv_ip), srv_port, product))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        for i in range(10):
            s.sendto(frame, (scale_ip, UDP_SCALE_TX_PORT))
            print(f"    [{i + 1}/10] 已发送 -> {scale_ip}:{UDP_SCALE_TX_PORT}", flush=True)
            time.sleep(0.5)
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server-ip", required=True, help="手机APP的IP")
    ap.add_argument("--server-port", type=int, default=8899)
    ap.add_argument("--tail-log", default=None, help="s7_server日志文件(从CONNECT行取秤IP)")
    ap.add_argument("--budget", type=int, default=1800, help="观察秒数")
    ap.add_argument("--cooldown", type=int, default=120, help="两次注入最小间隔秒数")
    args = ap.parse_args()

    print(f"[*] 注入目标: {args.server_ip}:{args.server_port}  秤MAC={hx(KNOWN_MAC)}", flush=True)
    listener = AnnounceListener()
    listener.start()

    pos = 0
    if args.tail_log and os.path.exists(args.tail_log):
        pos = os.path.getsize(args.tail_log)   # 只看新写入的
    last_inject = 0.0
    injected = []
    deadline = time.time() + args.budget
    while time.time() < deadline:
        trig = None
        if listener.joined.wait(timeout=0):
            trig = (listener.scale_ip, bytes(listener.mac), listener.product_bytes, "0x10宣告帧")
        elif args.tail_log and os.path.exists(args.tail_log):
            size = os.path.getsize(args.tail_log)
            if size > pos:
                with open(args.tail_log, encoding="utf-8", errors="replace") as f:
                    f.seek(pos)
                    new = f.read()
                pos = size
                for line in new.splitlines():
                    if "==== CONNECT" in line:
                        ip = line.split("CONNECT", 1)[1].split(":")[0].strip()
                        if ip:
                            trig = (ip, KNOWN_MAC, KNOWN_PRODUCT, "巡检连接PC")
                            break
        if trig:
            ip, mac, product, why = trig
            listener.joined.clear()
            if time.time() - last_inject >= args.cooldown:
                print(f"\n[+] 秤出现({why}) IP={ip} MAC={hx(mac)} -> 注入 {args.server_ip}:{args.server_port}", flush=True)
                inject(ip, mac, product, args.server_ip, args.server_port)
                last_inject = time.time()
                injected.append((why, ip, time.strftime("%H:%M:%S")))
            else:
                print(f"[..] {why} 来自 {ip}, 冷却中跳过", flush=True)
        time.sleep(0.5)
    print(f"\n[*] 观察结束({args.budget}s). 共注入: {injected or '无'}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
