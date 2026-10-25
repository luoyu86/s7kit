import com.example.s7scale.S7Protocol;

/**
 * PC端回归: S7Protocol 新增的配网函数 vs Python原版(s7_provision.py) 逐字节对比。
 * 运行: javac -encoding UTF-8 -d . TestProvision.java ..\src\java\com\example\s7scale\S7Protocol.java
 *       java -cp . TestProvision
 * 期望输出与 s7_provision.py 的 build_datum_codes/build_response_ip/parse_announce 一致
 * (对比方法: python -c 同参数跑一遍, 人工比对各前缀行)。
 */
public class TestProvision {
    static byte[] hexBytes(String s) {
        s = s.replace(" ", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        // 测试向量用占位数据(非真实WiFi信息), 只验证 Java 实现与 Python 原版输出一致
        byte[] ssid = "MyWiFi-2.4G".getBytes("UTF-8");
        byte[] pwd = "12345678".getBytes("UTF-8");
        byte[] ip = {(byte) 192, (byte) 168, 31, (byte) 168};
        byte[] mac = hexBytes("a47b9d03aadf");
        byte[] prod = {0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};

        int[][] codes = S7Protocol.buildDatumCodes(ssid, pwd, null, ip);
        StringBuilder sb = new StringBuilder();
        for (int[] c : codes) {
            sb.append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(';');
        }
        System.out.println("CODES " + sb);

        byte[] resp = S7Protocol.buildResponseIp(mac, ip, 8899, prod);
        System.out.println("RESP  " + S7Protocol.hex(resp));
        System.out.println("RESPX " + S7Protocol.hex(S7Protocol.xor55(resp)));

        // 双编码宣告解析: ① XOR编码的 0x10 宣告(用明文构造后编码, 模拟秤端)
        byte[] plain10 = new byte[32];
        plain10[0] = 0x55; plain10[1] = (byte) 0xAA;
        plain10[2] = 0x00; plain10[3] = 0x20;
        System.arraycopy(prod, 0, plain10, 4, 8);
        System.arraycopy(mac, 0, plain10, 12, 6);
        java.util.Arrays.fill(plain10, 18, 24, (byte) 0xFF);
        plain10[26] = 0x00; plain10[27] = 0x10;
        int crc = S7Protocol.crc16Modbus(plain10, 2, 26);
        plain10[30] = (byte) ((crc >> 8) & 0xFF);
        plain10[31] = (byte) (crc & 0xFF);
        byte[] xored = S7Protocol.xor55(plain10);
        S7Protocol.Announce a1 = S7Protocol.parseAnnounce(xored, xored.length);
        check("XOR宣告", a1, false, 0x10, true);

        // ② 明文 0x20 状态帧(2026-10-25 真机抓包原样)
        byte[] plain20 = hexBytes(
                "55 aa 00 20 00 00 00 01 00 00 00 01 a4 7b 9d 03 aa df"
                        .replace(" ", "")
                        + "0000000000000000"
                        + "0020"
                        + "0000"
                        + "1d52");
        S7Protocol.Announce a2 = S7Protocol.parseAnnounce(plain20, plain20.length);
        check("明文0x20", a2, true, 0x20, null);

        System.out.println("PARSE-OK");
    }

    static void check(String tag, S7Protocol.Announce a, boolean wantPlain, int wantCmd, Boolean wantCrc) {
        if (a == null) throw new AssertionError(tag + ": parseAnnounce 返回 null");
        if (a.plaintext != wantPlain) throw new AssertionError(tag + ": plaintext=" + a.plaintext);
        if (a.cmd != wantCmd) throw new AssertionError(tag + ": cmd=0x" + Integer.toHexString(a.cmd));
        if (!"S7".equals(a.productName())) throw new AssertionError(tag + ": 产品=" + a.productName());
        if (!"a4:7b:9d:03:aa:df".equals(a.macColon())) throw new AssertionError(tag + ": MAC=" + a.macColon());
        if (wantCrc != null && a.crcOk != wantCrc) throw new AssertionError(tag + ": crcOk=" + a.crcOk);
        System.out.println("[" + tag + "] cmd=0x" + Integer.toHexString(a.cmd)
                + " MAC=" + a.macColon() + " CRC=" + a.crcOk + " ✓");
    }
}
