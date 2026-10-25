package com.example.s7scale;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.Random;

/**
 * 给秤配网: 把"秤要连的服务器"写成 **本机(手机)当前IP:8899**, 并把当前WiFi的
 * SSID+密码通过 ESPTouch 教给秤。流程移植 s7_provision.py, 两处实测增强:
 *   1) 宣告帧双编码解析(XOR0x55帧 + 明文55AA状态帧) —— 重置后的秤广播的是明文帧,
 *      PC版脚本因只认XOR帧而收不到, 这里两种都认;
 *   2) 成功判定以"秤连上手机TCP8899"(S7Service.lastScaleConnAt)为准 —— 明文0x20
 *      只是秤自认为连着旧服务器的环境心跳, 不能当确认。
 * 无需固定手机IP: 手机IP变了, 回到本页重新点一次即可。
 */
public class ProvisionActivity extends Activity {

    private static final int ESPTOUCH_PORT = 7001;              // SmartConfig 目的端口 (hr.a.l)
    private static final int SCALE_TX_PORT = 7550;              // APP -> 秤 注入端口 (k6.j)
    private static final int[] ANNOUNCE_PORTS = {7551, 7552, 7553};  // 秤 -> APP 宣告 (esp.a.a)
    private static final int[] GUIDE_LENGTHS = {515, 514, 513, 512}; // gr.d.b()
    private static final long TOTAL_MS = 180_000;               // 整个配网预算
    private static final long ESP_BUDGET_MS = 55_000;           // ESPTouch 广播预算(hr.e.b)
    private static final long REINJECT_MS = 20_000;             // 一轮注入后无确认的再注入间隔
    private static final int MAX_INJECT_ROUNDS = 3;
    private static final int REQ_LOC = 1;

    private EditText etSsid, etPwd;
    private TextView tvInfo, tvLog;
    private ScrollView svLog;
    private Button btnToggle;

    private volatile boolean running = false;
    private volatile boolean stopReq = false;
    private WifiManager.MulticastLock mlock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provision);
        etSsid = findViewById(R.id.etSsid);
        etPwd = findViewById(R.id.etPwd);
        tvInfo = findViewById(R.id.tvInfo);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);
        btnToggle = findViewById(R.id.btnProvisionToggle);

        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, REQ_LOC);
        }
        refreshInfo();

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (running) {
                    stopReq = true;
                    log("…… 正在停止");
                } else {
                    startProvision();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshInfo();
        // SSID 默认值: 当前WiFi > 上次记住的; 密码默认值: 上次记住的(仅字段为空时填)
        SharedPreferences sp = getSharedPreferences("s7prov", MODE_PRIVATE);
        if (etSsid.getText().length() == 0) {
            String cur = currentSsid();
            etSsid.setText(cur != null && !cur.isEmpty() ? cur : sp.getString("ssid", ""));
        }
        if (etPwd.getText().length() == 0) {
            etPwd.setText(sp.getString("pwd", ""));
        }
    }

    /** 信息行: 本机IP -> 秤将连的目标 (每次进页面/授权后刷新) */
    private void refreshInfo() {
        String ip = wlanIp();
        tvInfo.setText("本机IP: " + (ip == null ? "(未连WiFi)" : ip)
                + " → 秤将连 " + (ip == null ? "?" : ip) + ":" + S7Service.PORT_S7);
    }

    private void startProvision() {
        final String ssid = etSsid.getText().toString().trim();
        final String pwd = etPwd.getText().toString();
        if (ssid.isEmpty()) {
            Toast.makeText(this, "请填 WiFi 名称", Toast.LENGTH_SHORT).show();
            return;
        }
        String ip = wlanIp();
        if (ip == null) {
            Toast.makeText(this, "手机没连上WiFi, 无线网卡找不到IPv4", Toast.LENGTH_LONG).show();
            return;
        }
        final String myIp = ip;
        running = true;
        stopReq = false;
        btnToggle.setText("停止配网");
        tvLog.setText("");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final long startMs = System.currentTimeMillis();
        workerRun(new Runnable() {
            @Override public void run() { runProvision(startMs, myIp, ssid, pwd); }
        });
    }

    /** 单一协调线程: 起ESPTouch发送线程 + 自己跑宣告监听/注入/确认 */
    private void runProvision(long startMs, String myIp, String ssid, String pwd) {
        byte[] ip4 = ipToBytes(myIp);
        DatagramSocket sendSock = null;
        DatagramSocket listenSock = null;
        boolean success = false;
        String scaleIp = null;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            mlock = wm.createMulticastLock("s7prov");
            mlock.setReferenceCounted(false);
            mlock.acquire();

            byte[] ssidB = ssid.getBytes("UTF-8");
            byte[] pwdB = pwd.getBytes("UTF-8");
            final int[][] codes = S7Protocol.buildDatumCodes(ssidB, pwdB, null, ip4);
            log("本机 " + myIp + ":" + S7Service.PORT_S7
                    + " | ESPTouch 数据码 " + codes.length + " 组, 广播55s");
            log("请让秤进配网: 站上秤 → 短按电池盖重置键 → 保持附近");

            sendSock = new DatagramSocket();
            sendSock.setBroadcast(true);
            final DatagramSocket fs = sendSock;
            final String bcast = myIp.substring(0, myIp.lastIndexOf('.') + 1) + "255";
            Thread sender = new Thread(new Runnable() {
                @Override public void run() { esptouchLoop(fs, codes, bcast); }
            }, "s7-esp");
            sender.setDaemon(true);
            sender.start();

            listenSock = openAnnounceSocket();
            if (listenSock == null) {
                log("[X] 7551~7553 都被占用, 无法监听宣告帧");
                return;
            }
            listenSock.setSoTimeout(2000);
            byte[] buf = new byte[2048];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + TOTAL_MS;
            byte[] scaleMac = null, product = null;
            int rounds = 0;
            long lastInjectAt = 0;

            while (!stopReq && System.currentTimeMillis() < deadline) {
                if (scaleIp != null && S7Service.lastScaleConnAt > startMs) {
                    success = true;                          // 秤已连上手机TCP8899
                    log("[OK] 秤(" + scaleIp + ")已连上手机 TCP " + S7Service.PORT_S7);
                    break;
                }
                try {
                    listenSock.receive(pkt);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                S7Protocol.Announce a = S7Protocol.parseAnnounce(buf, pkt.getLength());
                if (a == null) continue;
                String from = pkt.getAddress().getHostAddress();
                log("[<] " + from + " 宣告 cmd=0x" + Integer.toHexString(a.cmd)
                        + " 产品=" + a.productName() + " MAC=" + a.macColon()
                        + (a.plaintext ? " 明文" : " XOR") + (a.crcOk ? " CRCOK" : ""));
                if (scaleIp == null) {
                    scaleIp = from;
                    scaleMac = a.mac;
                    product = a.product;
                    injectRound(scaleIp, scaleMac, product, ip4);
                    rounds = 1;
                    lastInjectAt = System.currentTimeMillis();
                    log("[*] 已下发服务器地址 " + myIp + ":" + S7Service.PORT_S7
                            + " (" + rounds + "/" + MAX_INJECT_ROUNDS + "轮), 等秤连入…");
                } else if (!a.plaintext && a.cmd == 0x20) {
                    success = true;                          // XOR编码0x20 = 配网握手确认
                    log("[OK] 秤回 0x20 已连上服务器(握手确认)");
                    break;
                } else if (scaleIp.equals(from) && System.currentTimeMillis() - lastInjectAt >= REINJECT_MS
                        && rounds < MAX_INJECT_ROUNDS) {
                    injectRound(scaleIp, scaleMac, product, ip4);
                    rounds++;
                    lastInjectAt = System.currentTimeMillis();
                    log("[*] 未确认, 再注入 (" + rounds + "/" + MAX_INJECT_ROUNDS + "轮)");
                }
            }
            if (!success && !stopReq) {
                log("[X] " + (TOTAL_MS / 1000) + "秒未完成。检查: 秤是否进了配网模式"
                        + "(站上秤按重置键)/WiFi是否2.4G/密码是否正确");
            }
        } catch (Exception e) {
            log("[X] 异常: " + e);
        } finally {
            stopReq = true;
            if (sendSock != null) sendSock.close();
            if (listenSock != null) listenSock.close();
            if (mlock != null) {
                try { mlock.release(); } catch (Exception ignored) {}
                mlock = null;
            }
        }
        final boolean ok = success;
        final String sIp = scaleIp;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                running = false;
                btnToggle.setText("开始配网");
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (ok) {
                    getSharedPreferences("s7prov", MODE_PRIVATE).edit()
                            .putString("ssid", etSsid.getText().toString().trim())
                            .putString("pwd", etPwd.getText().toString())
                            .apply();
                    log("[完成] 以后秤会主动连本机:8899。手机IP变化后回到本页再点一次即可。");
                    Toast.makeText(ProvisionActivity.this, "配网成功! 称一次重验证", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 注入一轮: 48B ResponseIpCode xor55 后单播 秤IP:7550 x10 (500ms间隔) */
    private void injectRound(String scaleIp, byte[] mac, byte[] product, byte[] ip4) {
        try {
            final byte[] frame = S7Protocol.xor55(
                    S7Protocol.buildResponseIp(mac, ip4, S7Service.PORT_S7, product));
            DatagramSocket inj = new DatagramSocket();
            try {
                InetAddress to = InetAddress.getByName(scaleIp);
                for (int i = 0; i < 10 && !stopReq; i++) {
                    inj.send(new DatagramPacket(frame, frame.length, to, SCALE_TX_PORT));
                    SystemClock.sleep(500);
                }
            } finally {
                inj.close();
            }
        } catch (Exception e) {
            log("[!] 注入失败: " + e);
        }
    }

    /** hr.e.b 移植: 引导码2s -> 数据4s 循环, 8ms包节奏, 组播+两种广播, 55s预算 */
    private void esptouchLoop(DatagramSocket sock, int[][] codes, String bcast) {
        Random rnd = new Random();
        long deadline = System.currentTimeMillis() + ESP_BUDGET_MS;
        int offset = 0;
        try {
            while (!stopReq && System.currentTimeMillis() < deadline) {
                String mc = randMulticast(rnd);
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 2000 && !stopReq) {   // 引导码阶段
                    for (int L : GUIDE_LENGTHS) {
                        sendLen(sock, L, mc, bcast);
                        SystemClock.sleep(8);
                    }
                }
                while (System.currentTimeMillis() - t0 < 6000 && !stopReq
                        && System.currentTimeMillis() < deadline) {            // 数据阶段
                    String mc2 = randMulticast(rnd);
                    for (int g = offset; g < offset + 3 && g < codes.length; g++) {
                        for (int L : codes[g]) {
                            sendLen(sock, L, mc2, bcast);
                            SystemClock.sleep(8);
                        }
                    }
                    offset = (offset + 3) % codes.length;
                }
            }
        } catch (Exception ignored) {
        } finally {
            sock.close();
        }
    }

    private static String randMulticast(Random rnd) {
        return "234." + (rnd.nextInt(100) + 1) + "." + (rnd.nextInt(100) + 1)
                + "." + (rnd.nextInt(100) + 1);
    }

    /** 0x31 填充数据包发到 组播:7001 + 255.255.255.255 + 子网广播 (jr.a.i) */
    private static void sendLen(DatagramSocket sock, int len, String mc, String bcast) {
        try {
            byte[] payload = new byte[len];
            java.util.Arrays.fill(payload, (byte) 0x31);
            InetAddress m = InetAddress.getByName(mc);
            sock.send(new DatagramPacket(payload, len, m, ESPTOUCH_PORT));
            sock.send(new DatagramPacket(payload, len,
                    InetAddress.getByName("255.255.255.255"), ESPTOUCH_PORT));
            sock.send(new DatagramPacket(payload, len,
                    InetAddress.getByName(bcast), ESPTOUCH_PORT));
        } catch (Exception ignored) {
        }
    }

    /** 绑定 7551(占用则7552/7553) */
    private static DatagramSocket openAnnounceSocket() {
        for (int p : ANNOUNCE_PORTS) {
            try {
                return new DatagramSocket(new InetSocketAddress(p));
            } catch (SocketException ignored) {
            }
        }
        return null;
    }

    /** 当前连的WiFi名(去引号); 需要 ACCESS_FINE_LOCATION 且系统定位开着, 否则返回null */
    private String currentSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s == null || s.equals("<unknown ssid>")) return null;
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    /** wlan0 的 IPv4 地址, 没有则 null */
    static String wlanIp() {
        try {
            Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] ipToBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] out = new byte[4];
        for (int i = 0; i < 4 && i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i]);
        }
        return out;
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                tvLog.append(s + "\n");
                svLog.post(new Runnable() {
                    @Override public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    /** worker 的统一出口: 异常兜底 + running 复位 */
    private void workerRun(final Runnable r) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } finally {
                    if (running) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                running = false;
                                btnToggle.setText("开始配网");
                                getWindow().clearFlags(
                                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            }
                        });
                    }
                }
            }
        }, "s7-provision");
        t.start();
    }
}
