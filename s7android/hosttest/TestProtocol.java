import com.example.s7scale.S7Protocol;

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
        byte[] wire = hx("00 ff 55 65 55 55 55 54 55 55 55 54 f1 2e c8 56 ff 8a aa aa aa aa aa aa 55 55 d5 55 55 55 55 54 00 55 55 55 55 55 55 55 55 55 55 55 55 55 e4 e3");
        S7Protocol.Unwrapped u = S7Protocol.unwrap(wire);
        eq("hello.unwrap.wasXored", u.wasXored, true);
        eq("hello.unwrap.plain", hex(u.plain), "55 aa 00 30 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df ff ff ff ff ff ff 00 00 80 00 00 00 00 01 55 00 00 00 00 00 00 00 00 00 00 00 00 00 b1 b6");
        eq("hello.frameCrc", String.format("%04x", S7Protocol.frameCrc(u.plain)),
                "b1b6");

        // build_reply 期望 = 日志 SEND 行(L43/L11, 已被真机秤接受), 明文与XOR线缆帧都对
        byte[] reply = S7Protocol.buildReply(u.plain);
        eq("hello.buildReply.plain", hex(reply), "55 aa 00 31 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df a4 7b 9d 03 aa df 00 00 c0 00 00 00 00 01 55 00 00 00 00 00 00 00 00 00 00 00 00 00 4e f5");
        eq("hello.buildReply.xorWire", hex(S7Protocol.xor55(reply)), "00 ff 55 64 55 55 55 54 55 55 55 54 f1 2e c8 56 ff 8a f1 2e c8 56 ff 8a 55 55 95 55 55 55 55 54 00 55 55 55 55 55 55 55 55 55 55 55 55 55 1b a0");

        // ---- 2. 32B 0x52 帧, 明文 (capture.log L45/L47) ----
        byte[] f52 = hx("55 aa 00 20 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df ff ff ff ff ff ff 00 00 80 52 00 00 ef 83");
        S7Protocol.Unwrapped u2 = S7Protocol.unwrap(f52);
        eq("52.unwrap.wasXored", u2.wasXored, false);
        eq("52.frameCrc", String.format("%04x", S7Protocol.frameCrc(u2.plain)), "ef83");
        // 规格4b): 0x52 同规则回显应答 (Python的0x52特殊分支引用未定义变量dec从未运行成功, 不移植)
        eq("52.buildReply", hex(S7Protocol.buildReply(u2.plain)), "55 aa 00 21 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df a4 7b 9d 03 aa df 00 00 c0 52 00 00 48 f1");

        // ---- 3. 64B 称重帧 63.00kg (capture.log L56/L58) ----
        byte[] fm = hx("55 aa 00 40 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df ff ff ff ff ff ff 00 00 80 62 01 7c 00 00 00 00 04 00 33 00 04 00 03 01 00 18 9c 00 04 e4 04 e4 04 e4 04 e4 04 e4 04 e4 00 00 00 00 33 da");
        S7Protocol.Unwrapped u3 = S7Protocol.unwrap(fm);
        eq("measure.frameCrc", String.format("%04x", S7Protocol.frameCrc(u3.plain)), "33da");
        S7Protocol.Measure m = S7Protocol.parseMeasure(u3.plain);
        eq("measure.rawParam", m.rawParam, "017c00000000040033000400030100189c0004e404e404e404e404e404e40000");
        eq("measure.count", m.count, 1);
        eq("measure.unknown7c", m.unknown7c, 0x7c);
        eq("measure.timestampRaw", m.timestampRaw, 0L);
        eq("measure.weightKg", m.weightKg, 63.0);   // 0x189C=6300=63.00kg 人证合一
        eq("measure.impedance", Arrays.toString(m.impedanceChannels),
                "[1252, 1252, 1252, 1252, 1252, 1252]"); // 0x04E4=1252 未握把手电极占位值
        eq("measure.buildReply", hex(S7Protocol.buildReply(u3.plain)), "55 aa 00 41 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df a4 7b 9d 03 aa df 00 00 c0 62 01 7c 00 00 00 00 04 00 33 00 04 00 03 01 00 18 9c 00 04 e4 04 e4 04 e4 04 e4 04 e4 04 e4 00 00 00 00 72 14");

        // ---- 4. 另一帧称重 63.50kg (capture.log L164/L166; measurements.jsonl 第1行) ----
        byte[] fm2 = hx("55 aa 00 40 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df ff ff ff ff ff ff 00 00 80 62 01 7d 00 00 00 00 04 00 33 00 04 00 03 01 00 18 ce 00 04 e6 04 e6 04 e6 04 e6 04 e6 04 e6 00 00 00 00 f4 18");
        eq("measure2.frameCrc", String.format("%04x", S7Protocol.frameCrc(fm2)), "f418");
        S7Protocol.Measure m2 = S7Protocol.parseMeasure(fm2);
        eq("measure2.weightKg", m2.weightKg, 63.5);
        eq("measure2.impedance", Arrays.toString(m2.impedanceChannels),
                "[1254, 1254, 1254, 1254, 1254, 1254]");
        eq("measure2.buildReply", hex(S7Protocol.buildReply(fm2)), "55 aa 00 41 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df a4 7b 9d 03 aa df 00 00 c0 62 01 7d 00 00 00 00 04 00 33 00 04 00 03 01 00 18 ce 00 04 e6 04 e6 04 e6 04 e6 04 e6 04 e6 00 00 00 00 b5 d6");

        // ---- 5. 48B hello 明文变体, 版本param全0 (capture.log L52/L54) ----
        byte[] fp = hx("55 aa 00 30 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df ff ff ff ff ff ff 00 00 80 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 fc 78");
        S7Protocol.Unwrapped u4 = S7Protocol.unwrap(fp);
        eq("helloZero.unwrap.wasXored", u4.wasXored, false);
        eq("helloZero.frameCrc", String.format("%04x", S7Protocol.frameCrc(u4.plain)),
                "fc78");
        eq("helloZero.buildReply", hex(S7Protocol.buildReply(u4.plain)), "55 aa 00 31 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df a4 7b 9d 03 aa df 00 00 c0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03 3b");

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
