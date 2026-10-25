#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 s7_capture.log 提取真实 hello 帧对, 重放到手机APP, 逐字节比对应答."""
import socket
import re
import sys

LOG = "s7_capture.log"
PHONE_IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.31.19"

# 找到 14:36:19 那次会话的 hello RECV/SEND 对
recv_re = re.compile(r"RECV RAW\n((?:[0-9a-f]{2} )+[0-9a-f]{2})", re.I)
send_re = re.compile(r"SEND RAW\n((?:[0-9a-f]{2} )+[0-9a-f]{2})", re.I)

text = open(LOG, encoding="utf-8", errors="replace").read()
pairs = list(zip(recv_re.findall(text), send_re.findall(text)))
hello_pairs = [(r, s) for r, s in pairs if len(r.split()) == 48 and " 00 30 " in r]
assert hello_pairs, "没找到48B hello帧"
recv_hex, send_hex = hello_pairs[0]
frame = bytes.fromhex(recv_hex.replace(" ", ""))
expect = bytes.fromhex(send_hex.replace(" ", ""))
print(f"[*] 输入帧({len(frame)}B): {frame.hex(' ')}")
print(f"[*] 日志中PC版应答({len(expect)}B): {expect.hex(' ')}")

s = socket.create_connection((PHONE_IP, 8899), timeout=5)
s.sendall(frame)
s.settimeout(3)
try:
    data = s.recv(512)
except socket.timeout:
    data = b""
s.close()

print(f"[*] 手机APP应答({len(data)}B): {data.hex(' ')}")
print("[*] 与PC版应答逐字节一致:", data == expect)
if data != expect and len(data) == len(expect):
    diff = [i for i in range(len(data)) if data[i] != expect[i]]
    print(f"[!] 差异字节下标: {diff}")
