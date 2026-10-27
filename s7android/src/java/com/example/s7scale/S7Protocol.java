package com.example.s7scale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 斐讯S7体脂秤 帧协议 —— 逐函数移植自参照实现 s7_server.py(真机验证通过):
 *   crc16_modbus() / xor55() / unwrap() / frame_crc() / build_reply() / parse_measure() / describe()
 *
 * 帧结构(明文视角, 长度L可变, 实测L=32/48/64):
 *   [0]=0x55 [1]=0xAA | [2]=0x00 [3]=方向 | [4:12)=产品ID(00 00 00 01 00 00 00 01 = S7)
 *   [12:18)=秤WiFi MAC | [18:24)=目标MAC(收到的帧常为FF*6) | [24:26)=00 00
 *   [26]=cmd [27]=sub | [28:L-4)=param | [L-4:L-2)=00 00(填充, 不参与CRC) | [L-2:L)=CRC 大端
 *
 * 本类只用 JDK 标准库(无 android.* 依赖), 因此可直接在 PC 上用 javac/java 做
 * 真机抓包帧的逐字节回归验证(hosttest/TestProtocol.java)。
 */
public final class S7Protocol {

    public static final int XOR_KEY = 0x55;

    private S7Protocol() {}

    // ---------------------------------------------------------------- CRC

    /** crc16_modbus(data, init=0xFFFF): init 0xFFFF, 反射多项式 0xA001, 无 xorout (s7_server.py:90) */
    public static int crc16Modbus(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int k = 0; k < 8; k++) {
                crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    /** frame_crc(plain) = crc16_modbus(plain[2:len(plain)-4]) (s7_server.py:113)
     *  计算区间 [2, L-4): 起始偏移2, 字节数 (L-4)-2 = L-6 —— [L-4:L-2)的填充不参与CRC */
    public static int frameCrc(byte[] plain) {
        return crc16Modbus(plain, 2, plain.length - 4 - 2);
    }

    // ---------------------------------------------------------------- 编码

    /** xor55(b): 整帧每字节 XOR 0x55 (s7_server.py:99) */
    public static byte[] xor55(byte[] b) {
        if (b == null) return null;
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) (b[i] ^ XOR_KEY);
        return out;
    }

    /** unwrap() 的返回: 明文帧(可能为 null=非标准帧) + 是否 XOR 编码过 (s7_server.py:103) */
    public static final class Unwrapped {
        public final byte[] plain;      // null = 识别失败(无55AA头)
        public final boolean wasXored;  // True = 收到的是XOR编码帧, 应答也须XOR上送

        Unwrapped(byte[] plain, boolean wasXored) {
            this.plain = plain;
            this.wasXored = wasXored;
        }
    }

    /**
     * unwrap(wire): 识别传输编码 —— 帧头 55 AA 则明文, 否则 XOR 解一次再看 (s7_server.py:103)
     */
    public static Unwrapped unwrap(byte[] wire) {
        if (wire != null && wire.length >= 4 && wire[0] == 0x55 && wire[1] == (byte) 0xAA) {
            return new Unwrapped(wire, false);
        }
        byte[] dec = xor55(wire);
        if (dec != null && dec.length >= 4 && dec[0] == 0x55 && dec[1] == (byte) 0xAA) {
            return new Unwrapped(dec, true);
        }
        return new Unwrapped(null, false);
    }

    // ---------------------------------------------------------------- 应答

    /**
     * build_reply(plain): 通用应答 = 回显请求帧, [3] |= 0x01, [26] |= 0x40,
     * [18:24) = 秤MAC(即请求帧[12:18)), 重算CRC (s7_server.py:117, 应答规则经真机接受)。
     *
     * 注: 参照Python里 sub==0x52 时另有一个"40字节携带unix时间"的分支, 但该分支引用了
     * 未定义变量 dec(build_reply内为NameError, 从未成功运行过), 且协议规格(真机逐字节核对)
     * 明确 0x52 也走"同规则回显应答", 抓包日志 s7_capture.log 中 0x52 的实际应答就是32B通用
     * 回显且被秤接受 —— 故按规格只移植通用回显, 不移植该未验证分支。
     */
    public static byte[] buildReply(byte[] plain) {
        byte[] f = plain.clone();
        f[3] |= 0x01;
        System.arraycopy(f, 12, f, 18, 6);   // f[18:24] = f[12:18]
        f[26] |= 0x40;
        int crc = frameCrc(f);
        f[f.length - 2] = (byte) ((crc >> 8) & 0xFF);
        f[f.length - 1] = (byte) (crc & 0xFF);
        return f;
    }

    // ---------------------------------------------------------------- 配网协议
    // 移植自 s7_provision.py (ESPTouch + UDP 7550 服务器注入, 逆向自派健康 gr/jr/p6 包)。
    // 实测注记(2026-10-25): 秤重置后广播的宣告/状态帧有两种编码 —— 经典 XOR0x55 帧
    // (cmd=0x10 已连路由器) 和 明文 55AA 状态帧(cmd=0x20, PC版脚本因只按XOR解析而丢弃),
    // parseAnnounce 两种都认, 这是 APP 内配网成功的关键。

    /** ESPTouch CRC-8: 反射多项式 0x8C, init 0 (s7_provision.py crc8 / jr.c) */
    public static int crc8(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF);
            for (int k = 0; k < 8; k++) {
                crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0x8C) : (crc >>> 1);
            }
        }
        return crc & 0xFF;
    }

    public static int crc8(byte[] data) {
        return crc8(data, 0, data.length);
    }

    /** 已知产品ID -> 名称 (p6/d) */
    private static final byte[][] PRODUCT_IDS = {
            {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01},   // S7
            {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02},   // S9
            {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03},   // S7PE
    };
    private static final String[] PRODUCT_NAMES = {"S7", "S9", "S7PE"};

    /**
     * build_datum_codes(ssid, pwd, bssid, phone_ip): ESPTouch 数据码 (s7_provision.py gr.b/gr.a 移植)。
     * seq = [总长, 密码长, crc8(SSID), crc8(BSSID), 校验] + IP + 密码 + SSID, 每字节产出
     * 3个UDP包长度 [x+0x28, 0x100+idx+0x28, y+0x28]。返回 int[n][3], n = 9+密码长+SSID长。
     */
    public static int[][] buildDatumCodes(byte[] ssid, byte[] pwd, byte[] bssid, byte[] ip4) {
        int totalLen = 5 + 4 + pwd.length + ssid.length;
        if (totalLen > 128) {
            throw new IllegalArgumentException(
                    "头部+密码+SSID 共 " + totalLen + " 字节 > 128 上限(密码/SSID太长)");
        }
        int crcSsid = crc8(ssid);
        int crcBssid = (bssid != null && bssid.length > 0) ? crc8(bssid) : 0;
        int[] seq = new int[totalLen];
        seq[0] = totalLen;
        seq[1] = pwd.length;
        seq[2] = crcSsid;
        seq[3] = crcBssid;
        seq[4] = 0;                                   // 校验字节占位
        int p = 5;
        for (int i = 0; i < 4; i++) seq[p++] = ip4[i] & 0xFF;
        for (int i = 0; i < pwd.length; i++) seq[p++] = pwd[i] & 0xFF;
        for (int i = 0; i < ssid.length; i++) seq[p++] = ssid[i] & 0xFF;
        int chk = 0;
        for (int v : seq) chk ^= v;
        seq[4] = chk;
        int[][] codes = new int[seq.length][3];
        for (int idx = 0; idx < seq.length; idx++) {
            int value = seq[idx];
            int c = crc8(new byte[]{(byte) value, (byte) idx});
            int x = ((c >> 4) << 4) | (value >> 4);
            int y = ((c & 0x0F) << 4) | (value & 0x0F);
            codes[idx][0] = x + 0x28;
            codes[idx][1] = 0x100 + idx + 0x28;
            codes[idx][2] = y + 0x28;
        }
        return codes;
    }

    /**
     * build_response_ip(mac, server_ip, port, product): 48字节"写入服务器地址"应答帧
     * (gr.e.a/b/c 移植), 返回明文帧, 发送前需 xor55。端口大端在 [32:34)。
     */
    public static byte[] buildResponseIp(byte[] scaleMac, byte[] serverIp4, int port, byte[] product) {
        byte[] f = new byte[48];
        f[0] = 0x55;
        f[1] = (byte) 0xAA;
        f[3] = 0x01;
        System.arraycopy(product, 0, f, 4, 8);
        System.arraycopy(scaleMac, 0, f, 18, 6);
        f[26] = 0x40;
        f[27] = 0x10;
        System.arraycopy(serverIp4, 0, f, 28, 4);
        f[32] = (byte) ((port >> 8) & 0xFF);
        f[33] = (byte) (port & 0xFF);
        int crc = crc16Modbus(f, 2, 42);              // jr.b.b(buf,2,42) = [2:44)
        f[46] = (byte) ((crc >> 8) & 0xFF);
        f[47] = (byte) (crc & 0xFF);
        return f;
    }

    /** parseAnnounce 的解析结果(32字节宣告/状态帧) */
    public static final class Announce {
        public byte[] mac;        // 秤WiFi MAC [12:18)
        public byte[] product;    // 产品ID [4:12)
        public int type;          // [26]
        public int cmd;           // [27] 0x10=已连路由器 0x20=已连服务器
        public boolean crcOk;     // CRC16([2:28)) vs [30:32) 大端
        public boolean plaintext; // true=明文55AA帧(未XOR编码), false=XOR编码帧

        public String productName() {
            for (int i = 0; i < PRODUCT_IDS.length; i++) {
                if (Arrays.equals(product, PRODUCT_IDS[i])) return PRODUCT_NAMES[i];
            }
            return "未知";
        }

        public String macColon() {
            return S7Protocol.macColon(mac, 0);
        }
    }

    /**
     * parse_announce 的移植 + 双编码扩展: wire 前 len 字节里找32字节宣告帧。
     * XOR 编码帧先解码校验; 明文帧(55AA 开头)按结构直读 —— 两种都要求已知产品ID+非零MAC,
     * CRC 作信息字段不作为门禁(实测明文状态帧是触发注入的关键信号)。
     */
    public static Announce parseAnnounce(byte[] buf, int len) {
        if (buf == null || len < 32) return null;
        byte[] dec;
        boolean plain;
        if (buf[0] == 0x55 && buf[1] == (byte) 0xAA) {
            dec = Arrays.copyOf(buf, 32);
            plain = true;
        } else {
            dec = new byte[32];
            for (int i = 0; i < 32; i++) dec[i] = (byte) (buf[i] ^ XOR_KEY);
            if (dec[0] != 0x55 || dec[1] != (byte) 0xAA) return null;
            plain = false;
        }
        byte[] prod = Arrays.copyOfRange(dec, 4, 12);
        boolean known = false;
        for (byte[] p : PRODUCT_IDS) if (Arrays.equals(prod, p)) known = true;
        if (!known) return null;
        byte[] mac = Arrays.copyOfRange(dec, 12, 18);
        boolean macOk = false;
        for (byte b : mac) if ((b & 0xFF) != 0) macOk = true;
        if (!macOk) return null;
        Announce a = new Announce();
        a.mac = mac;
        a.product = prod;
        a.type = dec[26] & 0xFF;
        a.cmd = dec[27] & 0xFF;
        int crcGot = ((dec[30] & 0xFF) << 8) | (dec[31] & 0xFF);
        a.crcOk = crcGot == crc16Modbus(dec, 2, 26);
        a.plaintext = plain;
        return a;
    }

    // ---------------------------------------------------------------- 称重解析

    /** 0x62 称重记录帧的解析结果(对应Python parse_measure 返回的 dict) */
    public static final class Measure {
        public String rawParam;          // param 原始hex
        public int count;                // param[0] 记录数
        public int unknown7c;            // param[1] 未知(实测0x7c/0x7d)
        public long timestampRaw;        // param[2:6) 帧内原始时间戳大端(实测为0, 时间未同步, 原样保存)
        public double weightKg;          // param[15:17) 大端, 单位0.01kg
        public int[] impedanceChannels;  // param[18:30) 6个uint16大端阻抗通道
        public String mac;               // 帧[12:18) 秽WiFi MAC "aa:bb:cc:dd:ee:ff" (=deviceId)
        public String timeLocalIso;      // 手机本地接收时间 (handle_conn 补充的 time_local)
        public long timeLocalMs;         // 手机本地接收时间 epoch ms (小组件格式化用)
        /** 认领状态(APP层扩展字段, 非协议字段): "pending"=待认领 / "claimed"=已认领(计入个人数据与趋势图) */
        public String status;
    }

    /**
     * parse_measure(dec): 解析 0x80/0x62 称重记录帧 (s7_server.py:141)
     * 实测 63.00kg = 0x189C = 6300(0.01kg) 人证合一; 0x04E4 = 未握把手电极时的占位值
     */
    public static Measure parseMeasure(byte[] dec) {
        int L = dec.length;
        int pStart = 28;
        int pLen = (L - 4) - pStart;      // p = dec[28:L-4)
        if (pLen < 0) return null;
        Measure out = new Measure();
        out.rawParam = hex(dec, pStart, pLen);
        if (pLen >= 17) {
            out.count = dec[pStart] & 0xFF;
            out.unknown7c = dec[pStart + 1] & 0xFF;
            out.timestampRaw = u32be(dec, pStart + 2);
            out.weightKg = u16be(dec, pStart + 15) / 100.0;
            List<Integer> chans = new ArrayList<Integer>();
            for (int i = 18; i < Math.min(30, pLen - 1); i += 2) {
                chans.add(u16be(dec, pStart + i));
            }
            int[] arr = new int[chans.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = chans.get(i);
            out.impedanceChannels = arr;
        }
        return out;
    }

    // ---------------------------------------------------------------- 调试输出

    /** describe(dec): 一行日志摘要, 含CRC校验结果 (s7_server.py:157) */
    public static String describe(byte[] dec) {
        int L = dec.length;
        int crcGot = ((dec[L - 2] & 0xFF) << 8) | (dec[L - 1] & 0xFF);
        boolean crcOk = crcGot == frameCrc(dec);
        return "[" + h2(dec[3]) + "|" + h2(dec[26]) + h2(dec[27]) + "] MAC="
                + macColon(dec, 12) + " param=" + hex(dec, 28, L - 4 - 28)
                + " CRC=" + (crcOk ? "OK" : "FAIL");
    }

    // ---------------------------------------------------------------- 工具

    public static String macColon(byte[] b, int off) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(h2(b[off + i]));
        }
        return sb.toString();
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) sb.append(h2(b[i]));
        return sb.toString();
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    private static String h2(int v) {
        return String.format("%02x", v & 0xFF);
    }

    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32be(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
