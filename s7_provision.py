#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
s7_provision.py — 斐讯S7体脂秤 配网/服务器注入脚本（不依赖派健康APP）

复现 派健康(v3.0.1401) 对 S7 的完整配网流程，但把"秤要连接的云服务器"
换成你自己的服务器：

  1) ESPTouch v1 (乐鑫 SmartConfig v0.3.4.7) 广播 WiFi SSID+密码
     —— 编码参数逐字节复刻自脱壳后的 hr/gr/jr/ir 包
  2) 监听 UDP 7551~7553，等秤的"已连上路由器"(cmd 0x10) 宣告帧
  3) 构造 48 字节 ResponseIpCode 帧（crc16 校验，整帧 XOR 0x55），
     单播到 秤IP:7550 共10次 —— 把 *你的服务器* IP:端口 写进秤
  4) 等秤回 "已连上服务器"(cmd 0x20) 帧 —— 完成

帧格式/校验均逆向自:
  gr/e.java(ResponseIpCode) p6/b.java(XOR 0x55) jr/b.java(CRC16-CCITT-FALSE)
  p6/d.java(产品ID) esp/a.java(UDP监听) k6/j.java(注入线程)

用法:
  python s7_provision.py --ssid MyWiFi --pass 12345678 \
      --server-ip 192.168.31.50 --server-port 8899

  必须运行在连接到目标 2.4GHz WiFi 的机器上（笔记本/树莓派均可）。
  --server-ip 是运行 s7_server.py 的机器（可以是同一台）。
"""
import argparse
import socket
import sys
import threading
import time
import random

try:                                   # 重定向到文件时也保持实时输出
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

try:                                   # Windows默认定时器15.6ms，提到1ms保证8ms发包节奏
    import ctypes
    ctypes.windll.winmm.timeBeginPeriod(1)
except Exception:
    pass

# ---------------------------------------------------------------- 常量
XOR_KEY = 0x55                       # 整帧混淆 (p6.b.a / la.e.f102989a)
UDP_SCALE_TX_PORT = 7550             # APP -> 秒 (k6.j: DatagramPacket(..., 7550))
UDP_ANNOUNCE_PORTS = (7551, 7552, 7553)  # 秤 -> APP 宣告 (esp.a.a)
ESPTOUCH_PORT = 7001                 # SmartConfig 目的端口 (hr.a.l)
ESPTOUCH_RESULT_PORT = 18266         # 乐鑫标准回连端口 (hr.a.b)
PRODUCT_IDS = {                      # (p6.d) 宣告帧/应答帧 [4:12)
    b"\x00\x00\x00\x01\x00\x00\x00\x01": "S7",
    b"\x00\x00\x00\x01\x00\x00\x00\x02": "S9",
    b"\x00\x00\x00\x01\x00\x00\x00\x03": "S7PE",
}

# ---------------------------------------------------------------- 校验
def crc8(data: bytes, crc: int = 0) -> int:
    """CRC-8 poly 0x8C 反射 (jr.c, esptouch标准)"""
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc >> 1) ^ 0x8C) if (crc & 1) else (crc >> 1)
    return crc & 0xFF


def crc16(data: bytes, init: int = 0xFFFF) -> int:
    """CRC-16/MODBUS (poly 0xA001 反射, init 0xFFFF)
    逆向自 jr.b 两张查找表(表头 0000,C0C1,C181,0140 即MODBUS表)，
    并用S7实机宣告帧验证通过；注意 jr.b.b(buf, off, len) 是(偏移,长度)。"""
    crc = init
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def xor55(frame: bytes) -> bytes:
    return bytes(b ^ XOR_KEY for b in frame)


def hx(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)


# ---------------------------------------------------------------- ESPTouch 编码
def build_datum_codes(ssid: bytes, pwd: bytes, bssid: bytes, phone_ip: bytes):
    """复刻 gr.b(DatumCode)+gr.a(DataCode)：返回 [ [len1,len2,len3], ... ]"""
    ip = [int(x) for x in phone_ip]
    total_len = 5 + 4 + len(pwd) + len(ssid)
    if total_len > 128:
        raise SystemExit(f"[!] 头部+密码+SSID 共 {total_len} 字节 > 128 上限，密码太长")
    crc_ssid = crc8(ssid)
    crc_bssid = crc8(bssid) if bssid else 0
    seq = [total_len, len(pwd), crc_ssid, crc_bssid, 0] + ip + list(pwd) + list(ssid)
    seq[4] = 0
    chk = 0
    for v in seq:
        chk ^= v
    seq[4] = chk
    codes = []
    for idx, value in enumerate(seq):
        c = crc8(bytes([value, idx]))
        x = ((c >> 4) << 4) | (value >> 4)
        y = ((c & 0x0F) << 4) | (value & 0x0F)
        # gr.a.a(): [0, X, 1, idx, 0, Y] -> gr.b.b(): 3个UDP包长度
        codes.append((x + 0x28, 0x100 + idx + 0x28, y + 0x28))
    return codes

GUIDE_LENGTHS = (515, 514, 513, 512)  # gr.d.b()


class EsptouchSender(threading.Thread):
    """复刻 hr.e.b(): 引导码2s -> 数据4s 循环，55s总时长，包间隔8ms"""
    def __init__(self, sock, codes, use_broadcast=True, subnet_bcast="192.168.31.255"):
        super().__init__(daemon=True)
        self.sock, self.codes, self.use_broadcast = sock, codes, use_broadcast
        self.subnet_bcast = subnet_bcast
        self.stop_evt = threading.Event()

    def _send(self, length: int, mc_addr: str):
        payload = b"\x31" * length                      # jr.a.i(): 0x31填充
        self.sock.sendto(payload, (mc_addr, ESPTOUCH_PORT))
        if self.use_broadcast:
            self.sock.sendto(payload, ("255.255.255.255", ESPTOUCH_PORT))
            self.sock.sendto(payload, (self.subnet_bcast, ESPTOUCH_PORT))

    def run(self):
        offset, deadline = 0, time.time() + 170.0   # 覆盖整个宣告等待窗口
        print(f"[*] ESPTouch 广播启动(55s预算, {len(self.codes)}组数据码)")
        while not self.stop_evt.is_set() and time.time() < deadline:
            mc = "234.{}.{}.{}".format(*(random.randint(1, 100) for _ in range(3)))
            t0 = time.time()
            while time.time() - t0 < 2.0 and not self.stop_evt.is_set():   # 引导码阶段
                for L in GUIDE_LENGTHS:
                    self._send(L, mc)
                    time.sleep(0.008)
            while time.time() - t0 < 6.0 and not self.stop_evt.is_set() \
                    and time.time() < deadline:                            # 数据阶段
                mc2 = "234.{}.{}.{}".format(*(random.randint(1, 100) for _ in range(3)))
                for group in self.codes[offset:offset + 3]:
                    for L in group:
                        self._send(L, mc2)
                        time.sleep(0.008)
                offset = (offset + 3) % len(self.codes)
        print("[*] ESPTouch 发送线程退出")


# ---------------------------------------------------------------- 帧解析/构造
def parse_announce(raw: bytes):
    """解析秤的32字节宣告帧；返回 dict 或 None
    CRC: MODBUS over dec[2:28)（偏移2长26），字段在 [30:32) 大端"""
    if len(raw) < 32:
        return None
    dec = xor55(raw[:32])
    crc_got = (dec[30] << 8) | dec[31]
    crc_calc = crc16(dec[2:28])
    return {
        "raw": raw[:32], "dec": dec, "crc_ok": crc_got == crc_calc,
        "product": PRODUCT_IDS.get(dec[4:12], f"未知({hx(dec[4:12])})"),
        "mac": dec[12:18], "type": dec[26], "cmd": dec[27],
    }


def build_response_ip(mac: bytes, server_ip: bytes, port: int,
                      product: bytes = b"\x00\x00\x00\x01\x00\x00\x00\x01") -> bytes:
    """复刻 gr.e.a()+e.b()+e.c()：48字节应答帧(明文)，发送前需 xor55"""
    f = bytearray(48)
    f[0], f[1] = 0x55, 0xAA
    f[3] = 0x01
    f[4:12] = product                                # S7 产品ID
    f[18:24] = mac                                   # 回显秤MAC
    f[26], f[27] = 0x40, 0x10                        # cmd: 写入服务器地址
    f[28:32] = server_ip                             # 服务器IP
    f[28 + 4] = (port >> 8) & 0xFF                   # 端口 大端
    f[28 + 5] = port & 0xFF
    crc = crc16(bytes(f[2:44]))          # jr.b.b(buf,2,42) = 偏移2长42 -> [2:44)
    f[46], f[47] = (crc >> 8) & 0xFF, crc & 0xFF
    return bytes(f)


# ---------------------------------------------------------------- 监听器
class AnnounceListener(threading.Thread):
    """监听 7551~7553，识别 0x10/0x20 帧"""
    def __init__(self):
        super().__init__(daemon=True)
        self.joined = threading.Event()      # 收到 0x10
        self.connected = threading.Event()   # 收到 0x20
        self.scale_ip = None
        self.mac = None
        self.product_bytes = None
        self.sock = None

    def run(self):
        port = None
        for p in UDP_ANNOUNCE_PORTS:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(("", p))
                port, self.sock = p, s
                break
            except OSError:
                continue
        if port is None:
            print("[!] 7551~7553 端口都被占用，无法监听宣告帧")
            return
        print(f"[*] 宣告帧监听已启动 UDP :{port} ( multicast 需放行防火墙 )")
        self.sock.settimeout(2.0)
        deadline = time.time() + 300
        while time.time() < deadline and not self.connected.is_set():
            try:
                raw, addr = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            info = parse_announce(raw)
            if info:
                tag = {0x10: "已连上路由器", 0x20: "已连上云服务器"}.get(info["cmd"], "?")
                print(f"[<] {addr[0]}:{addr[1]} 宣告帧 cmd=0x{info['cmd']:02x}({tag}) "
                      f"产品={info['product']} MAC={hx(info['mac'])} CRC{'OK' if info['crc_ok'] else 'FAIL'}")
                if not info["crc_ok"]:
                    print(f"    raw: {hx(info['raw'])}")
                if info["cmd"] == 0x10 and not self.joined.is_set():
                    self.scale_ip, self.mac = addr[0], info["mac"]
                    self.product_bytes = info["dec"][4:12]
                    self.joined.set()
                elif info["cmd"] == 0x20:
                    self.connected.set()
            else:
                print(f"[<] {addr[0]}:{addr[1]} 未识别UDP({len(raw)}B): {hx(raw[:48])}")


class EspResultListener(threading.Thread):
    """乐鑫标准回连 18266：[len][mac6][ip4] —— 早于7551的辅助信号"""
    def __init__(self):
        super().__init__(daemon=True)
        self.got = threading.Event()

    def run(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", ESPTOUCH_RESULT_PORT))
            s.settimeout(2.0)
            print(f"[*] ESPTouch 回连监听 UDP :{ESPTOUCH_RESULT_PORT}")
        except OSError:
            return
        deadline = time.time() + 300
        while time.time() < deadline:
            try:
                raw, addr = s.recvfrom(64)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(raw) >= 11:
                print(f"[<] {addr[0]} ESPTouch成功帧: len={raw[0]} MAC={hx(raw[1:7])} IP={'.'.join(map(str, raw[7:11]))}")
                self.got.set()


# ---------------------------------------------------------------- 主流程
def local_ip_of_hint() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.31.1", 1))       # 小米路由网关；不通也会选出接口
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description="S7 配网 + 自建服务器注入")
    ap.add_argument("--ssid", required=True, help="目标WiFi名称(2.4G)")
    ap.add_argument("--pass", dest="pwd", required=True, help="WiFi密码")
    ap.add_argument("--server-ip", default=None, help="你的服务器IP(默认=本机IP)")
    ap.add_argument("--server-port", type=int, default=8899, help="你的服务器TCP端口(默认8899)")
    ap.add_argument("--if-ip", default=None, help="本机WiFi网卡IP(默认自动)")
    ap.add_argument("--bssid", default="", help="AP的BSSID(可选，一般留空)")
    ap.add_argument("--no-broadcast", action="store_true", help="只发组播不发广播(默认两者都发)")
    ap.add_argument("--monitor-only", action="store_true",
                    help="只旁听UDP宣告帧(可配合官方APP配网观察验证)，不广播不注入")
    args = ap.parse_args()

    # --- 旁听模式 ---
    if args.monitor_only:
        print("[*] 旁听模式：只监听 7551~7553 / 18266，Ctrl+C 退出")
        AnnounceListener().start()
        EspResultListener().start()
        while True:
            time.sleep(1)

    if_ip = args.if_ip or local_ip_of_hint()
    srv_ip = args.server_ip or if_ip
    print(f"[*] 本机/发包网卡IP: {if_ip}    注入给秤的服务器: {srv_ip}:{args.server_port}")
    print(f"[*] WiFi: SSID={args.ssid!r} 密码长度={len(args.pwd)}")

    ssid_b = args.ssid.encode("latin-1", "replace")
    pwd_b = args.pwd.encode("latin-1", "replace")
    bssid_b = bytes(int(x, 16) for x in args.bssid.split(":")) if args.bssid else b""
    codes = build_datum_codes(ssid_b, pwd_b, bssid_b, socket.inet_aton(if_ip))
    print(f"[*] 数据码 {len(codes)} 组(每组3个UDP包) 示例组0: {codes[0]}")

    # --- 启动监听 ---
    listener = AnnounceListener()
    listener.start()
    EspResultListener().start()

    # --- 启动 ESPTouch ---
    send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    send_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    subnet = ".".join(if_ip.split(".")[:3]) + ".255"
    sender = EsptouchSender(send_sock, codes, use_broadcast=not args.no_broadcast,
                            subnet_bcast=subnet)
    sender.start()

    print("\n== 请让秤进入配网模式(重置WiFi后上秤/按说明书)，站到秤附近等待... ==\n")

    # --- 等 0x10 ---
    if not listener.joined.wait(timeout=180):
        print("[X] 3分钟没等到秤的'已连路由器'帧。检查: 秤是否在配网模式/是否2.4G/防火墙UDP。")
        sender.stop_evt.set()
        return 1
    sender.stop_evt.set()
    time.sleep(0.3)

    scale_ip, mac = listener.scale_ip, listener.mac
    print(f"\n[+] 秤已连上WiFi！IP={scale_ip} MAC={hx(mac)}")
    print("[*] 开始注入你的服务器地址 (UDP -> 秤:7550 x10)...")

    frame = xor55(build_response_ip(mac, socket.inet_aton(srv_ip),
                                    args.server_port, listener.product_bytes))
    inject = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for i in range(10):
        inject.sendto(frame, (scale_ip, UDP_SCALE_TX_PORT))
        print(f"    [{i + 1}/10] 已发送 {len(frame)}B -> {scale_ip}:{UDP_SCALE_TX_PORT}")
        time.sleep(0.5)
    inject.close()

    print("[*] 等待秤连接你的服务器并回 0x20 确认帧(最多120s)...")
    if listener.connected.wait(timeout=120):
        print("\n[OK] 秤已连上你的服务器！去看 s7_server.py 的控制台/日志，"
              "站上秤称重即可抓到上传数据。")
        return 0
    print("\n[!] 没收到0x20确认，但秤可能仍已连接服务器 —— 先看 s7_server 有没有新连接。")
    return 2


if __name__ == "__main__":
    sys.exit(main())
