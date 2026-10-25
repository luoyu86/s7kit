#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen_test.py — 从 s7_capture.log 机械提取真机帧, 生成 TestProtocol.java 的帧常量。
   避免手工转录抓包字节出错。运行: python gen_test.py"""
import io, os, re

LOG = os.path.join(os.path.dirname(__file__), "..", "..", "s7_capture.log")
OUT = os.path.join(os.path.dirname(__file__), "TestProtocol.java")

def tokens(line):
    return line.split()

def frame_at(ln, prefix=None):
    """返回该行(可选去掉前缀标签)的十六进制字节串; 校验字节数"""
    with io.open(LOG, encoding="utf-8") as f:
        lines = f.read().splitlines()
    s = lines[ln - 1].strip()
    if prefix:
        assert s.startswith(prefix), "line %d prefix mismatch: %r" % (ln, s[:12])
        s = s[len(prefix):].strip()
    toks = s.split()
    for t in toks:
        assert re.fullmatch(r"[0-9a-f]{2}", t), "bad token %r at line %d" % (t, ln)
    return " ".join(toks), len(toks)

frames = {
    "WIRE_HELLO_XOR":  frame_at(4),
    "PLAIN_HELLO":     frame_at(10, "DEC"),
    "REPLY_HELLO":     frame_at(43),
    "REPLY_WIRE_HELLO_XOR": frame_at(11, "SEND"),
    "F52":             frame_at(45),
    "REPLY_52":        frame_at(47),
    "FM":              frame_at(56),
    "REPLY_M":         frame_at(58),
    "FM2":             frame_at(164),
    "REPLY_M2":        frame_at(166),
    "PLAIN_HELLO_ZERO":frame_at(52),
    "REPLY_HELLO_ZERO":frame_at(54),
}
for k, (s, n) in sorted(frames.items()):
    print("%-20s %3dB  %s" % (k, n, s))
    assert n in (32, 48, 64), k

def J(k):
    return frames[k][0]

tmpl = r'''import com.example.s7scale.S7Protocol;

import java.util.Arrays;

/**
 * 宿主机协议回归测试 —— 本文件由 gen_test.py 从 s7_capture.log(真机抓包) 机械生成帧常量,
 * 期望值即参照Python实现(s7_server.py)在真机上实际收到/发出并被秤接受的字节, 逐字节比对。
 * 运行: javac -encoding UTF-8 TestProtocol.java ../src/java/com/example/s7scale/S7Protocol.java && java TestProtocol
 */
public class TestProtocol {

    private static int failures = 0;

    private static void eq(String name, Object got, Object exp) {
        boolean ok = (got == null) ? exp == null : got.equals(exp);
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
            System.out.println("      got: " + got);
            System.out.println("      exp: " + exp);
        }
    }

    public static void main(String[] args) {
        // ---- 1. 48B hello, XOR编码线缆帧 (capture.log L4) ----
        byte[] wire = hx("{WIRE_HELLO_XOR}");
        S7Protocol.Unwrapped u = S7Protocol.unwrap(wire);
        eq("hello.unwrap.wasXored", u.wasXored, true);
        eq("hello.unwrap.plain", hex(u.plain), "{PLAIN_HELLO}");
        eq("hello.frameCrc", String.format("%04x", S7Protocol.frameCrc(u.plain)),
                "{PLAIN_HELLO_CRC}");

        // build_reply 期望 = 日志 SEND 行(L43/L11, 已被真机秤接受), 明文与XOR线缆帧都对
        byte[] reply = S7Protocol.buildReply(u.plain);
        eq("hello.buildReply.plain", hex(reply), "{REPLY_HELLO}");
        eq("hello.buildReply.xorWire", hex(S7Protocol.xor55(reply)), "{REPLY_WIRE_HELLO_XOR}");

        // ---- 2. 32B 0x52 帧, 明文 (capture.log L45/L47) ----
        byte[] f52 = hx("{F52}");
        S7Protocol.Unwrapped u2 = S7Protocol.unwrap(f52);
        eq("52.unwrap.wasXored", u2.wasXored, false);
        eq("52.frameCrc", String.format("%04x", S7Protocol.frameCrc(u2.plain)), "{F52_CRC}");
        // 规格4b): 0x52 同规则回显应答 (Python的0x52特殊分支引用未定义变量dec从未运行成功, 不移植)
        eq("52.buildReply", hex(S7Protocol.buildReply(u2.plain)), "{REPLY_52}");

        // ---- 3. 64B 称重帧 63.00kg (capture.log L56/L58) ----
        byte[] fm = hx("{FM}");
        S7Protocol.Unwrapped u3 = S7Protocol.unwrap(fm);
        eq("measure.frameCrc", String.format("%04x", S7Protocol.frameCrc(u3.plain)), "{FM_CRC}");
        S7Protocol.Measure m = S7Protocol.parseMeasure(u3.plain);
        eq("measure.rawParam", m.rawParam, "{FM_PARAM}");
        eq("measure.count", m.count, 1);
        eq("measure.unknown7c", m.unknown7c, 0x7c);
        eq("measure.timestampRaw", m.timestampRaw, 0L);
        eq("measure.weightKg", m.weightKg, 63.0);   // 0x189C=6300=63.00kg 人证合一
        eq("measure.impedance", Arrays.toString(m.impedanceChannels),
                "[1252, 1252, 1252, 1252, 1252, 1252]"); // 0x04E4=1252 未握把手电极占位值
        eq("measure.buildReply", hex(S7Protocol.buildReply(u3.plain)), "{REPLY_M}");

        // ---- 4. 另一帧称重 63.50kg (capture.log L164/L166; measurements.jsonl 第1行) ----
        byte[] fm2 = hx("{FM2}");
        eq("measure2.frameCrc", String.format("%04x", S7Protocol.frameCrc(fm2)), "{FM2_CRC}");
        S7Protocol.Measure m2 = S7Protocol.parseMeasure(fm2);
        eq("measure2.weightKg", m2.weightKg, 63.5);
        eq("measure2.impedance", Arrays.toString(m2.impedanceChannels),
                "[1254, 1254, 1254, 1254, 1254, 1254]");
        eq("measure2.buildReply", hex(S7Protocol.buildReply(fm2)), "{REPLY_M2}");

        // ---- 5. 48B hello 明文变体, 版本param全0 (capture.log L52/L54) ----
        byte[] fp = hx("{PLAIN_HELLO_ZERO}");
        S7Protocol.Unwrapped u4 = S7Protocol.unwrap(fp);
        eq("helloZero.unwrap.wasXored", u4.wasXored, false);
        eq("helloZero.frameCrc", String.format("%04x", S7Protocol.frameCrc(u4.plain)),
                "{PLAIN_HELLO_ZERO_CRC}");
        eq("helloZero.buildReply", hex(S7Protocol.buildReply(u4.plain)), "{REPLY_HELLO_ZERO}");

        // ---- 6. 工具函数 ----
        eq("macColon", S7Protocol.macColon(fp, 12), "a4:7b:9d:03:aa:df");
        byte[] d = fp.clone();
        eq("describe", S7Protocol.describe(d),
                "[30|8000] MAC=a4:7b:9d:03:aa:df param=" + S7Protocol.hex(d, 28, 16) + " CRC=OK");

        if (failures > 0) {
            System.out.println("\n" + failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("\nALL PASS —— Java移植与真机抓包逐字节一致");
    }

    private static byte[] hx(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
'''

def crc_java(frame_str):
    """Python侧独立重算CRC16-MODBUS, 生成期望的4位hex(与Java实现互为交叉验证)"""
    data = bytes(int(t, 16) for t in frame_str.split())
    crc = 0xFFFF
    for b in data[2:len(data) - 4]:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return "%04x" % crc

def param(frame_str):
    data = frame_str.split()
    return "".join(data[28:len(data) - 4])

repl = {
    "WIRE_HELLO_XOR": J("WIRE_HELLO_XOR"),
    "PLAIN_HELLO": J("PLAIN_HELLO"),
    "PLAIN_HELLO_CRC": crc_java(J("PLAIN_HELLO")),
    "REPLY_HELLO": J("REPLY_HELLO"),
    "REPLY_WIRE_HELLO_XOR": J("REPLY_WIRE_HELLO_XOR"),
    "F52": J("F52"),
    "F52_CRC": crc_java(J("F52")),
    "REPLY_52": J("REPLY_52"),
    "FM": J("FM"),
    "FM_CRC": crc_java(J("FM")),
    "FM_PARAM": param(J("FM")),
    "REPLY_M": J("REPLY_M"),
    "FM2": J("FM2"),
    "FM2_CRC": crc_java(J("FM2")),
    "REPLY_M2": J("REPLY_M2"),
    "PLAIN_HELLO_ZERO": J("PLAIN_HELLO_ZERO"),
    "PLAIN_HELLO_ZERO_CRC": crc_java(J("PLAIN_HELLO_ZERO")),
    "REPLY_HELLO_ZERO": J("REPLY_HELLO_ZERO"),
}
# 交叉验证: CRC期望值应与日志帧自带的CRC尾字节一致
for k, f in frames.items():
    if k.startswith(("REPLY",)) or k in ("WIRE_HELLO_XOR",):
        continue
    s = f[0]
    toks = s.split()
    tail = "".join(toks[-2:])
    assert crc_java(s) == tail, "%s: computed %s != frame tail %s" % (k, crc_java(s), tail)
print("\nPython独立CRC重算 与 抓包帧自带CRC尾字节 全部一致\n")

out = tmpl
for k, v in repl.items():
    out = out.replace("{" + k + "}", v)
with io.open(OUT, "w", encoding="utf-8", newline="\n") as g:
    g.write(out)
print("written:", OUT)
