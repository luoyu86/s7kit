#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""inject_only.py — 已连网秤的"改指向"注入(跳过ESPTouch, 无需WiFi密码)

流程: 监听 UDP 7551~7553 等秤的 0x10"已连上路由器"宣告帧 →
      向 秤IP:7550 连发10次服务器地址帧 → 等秤回 0x20"已连上服务器"。
用法: python inject_only.py --server-ip 192.168.31.19 --server-port 8899 [--wait N]
"""
import argparse
import socket
import sys
import time

sys.path.insert(0, __file__.rsplit("\\", 1)[0] if "\\" in __file__ else ".")
from s7_provision import (AnnounceListener, build_response_ip, xor55,
                          UDP_SCALE_TX_PORT, hx)

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server-ip", required=True, help="手机APP的IP")
    ap.add_argument("--server-port", type=int, default=8899)
    ap.add_argument("--wait", type=int, default=360, help="等宣告帧秒数")
    args = ap.parse_args()

    print(f"[*] 目标服务器(手机): {args.server_ip}:{args.server_port}")
    print(f"[*] 等待秤的 0x10 宣告帧(最多{args.wait}s)... 称一次重或等周期巡检可触发")

    listener = AnnounceListener()
    listener.start()
    if not listener.joined.wait(timeout=args.wait):
        print("[X] 未等到秤的宣告帧。秤可能休眠中/不在配网巡检态 —— 站上秤称一次再试, "
              "或按说明书重置秤WiFi后用完整版 s7_provision.py 配网。")
        return 1

    scale_ip, mac = listener.scale_ip, listener.mac
    print(f"\n[+] 秤在网! IP={scale_ip} MAC={hx(mac)}")
    print(f"[*] 注入服务器地址 {args.server_ip}:{args.server_port} -> {scale_ip}:{UDP_SCALE_TX_PORT} x10")

    frame = xor55(build_response_ip(mac, socket.inet_aton(args.server_ip),
                                    args.server_port, listener.product_bytes))
    inj = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for i in range(10):
        inj.sendto(frame, (scale_ip, UDP_SCALE_TX_PORT))
        print(f"    [{i + 1}/10] 已发送 {len(frame)}B -> {scale_ip}:{UDP_SCALE_TX_PORT}")
        time.sleep(0.5)
    inj.close()

    print("[*] 等秤连上手机并回 0x20 (最多120s)... 同时观察手机 logcat/通知")
    if listener.connected.wait(timeout=120):
        print("\n[OK] 秤已回 0x20 —— 已连上手机APP! 站上秤称一次重验证。")
        return 0
    print("\n[!] 未收到0x20 —— 但秤可能已连上手机(其0x20只在配网流程回)。"
          "直接称一次重, 看手机APP/小组件是否出现体重。")
    return 2


if __name__ == "__main__":
    sys.exit(main())
