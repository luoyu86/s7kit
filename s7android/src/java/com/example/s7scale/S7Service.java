package com.example.s7scale;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * S7 数据服务(前台服务) —— 移植自 s7_server.py:
 *   handle_conn(): TCP 8899 accept循环 + 每连接一线程, recv→unwrap→解析→存档→同编码回显应答
 *   start_http():  HTTP 8898 状态服务(/widget /latest /all /status)
 *
 * 省电核心: 阻塞accept监听休眠时功耗≈0, 无轮询无wakelock;
 * 秤每次称重/巡检都会主动TCP连入并约5秒后主动断开, 服务器永远不需要主动发起任何帧。
 * 称重帧(cmd 0x80/0x62)收到后: 存 measurements.jsonl → 推送桌面小组件刷新 → 更新通知。
 */
public class S7Service extends Service {

    public static final int PORT_S7 = 8899;    // 秤TCP推流端口(与 s7_provision.py 下发的一致)
    public static final int PORT_HTTP = 8898;  // HTTP状态服务(对应Python start_http 默认8898)

    private static final String TAG = "S7Server";
    private static final String CH_ID = "s7_server";
    private static final int NOTIF_ID = 1;
    private static final int RECV_TIMEOUT_MS = 120000; // 对应Python conn.settimeout(120)

/** 供Activity查询服务是否在跑 */
public static volatile boolean running = false;

/** 最近一次连入TCP8899的秤IP(配网页面用它判定"秤已连上手机") */
public static volatile String lastScaleIp = null;

/** 最近一次秤连入的时间戳(epoch ms), 配网时用于识别"新"连接 */
public static volatile long lastScaleConnAt = 0L;

    private volatile ServerSocket srvTcp;
    private volatile ServerSocket srvHttp;
    private Thread acceptThread;
    private Thread httpThread;

    // 状态(对应Python status dict)
    private volatile int conns = 0;
    private volatile String scaleIp = null;
    private volatile String lastSeen = null;
    private volatile S7Protocol.Measure last = null;
    private final String started = MeasureStore.nowIso();

    private File logFile;
    private Writer logWriter;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startFg();
        logFile = new File(getFilesDir(), "s7_capture.log");
        last = MeasureStore.last(this);   // 预载最近一条(重启后小组件仍有数据)
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override public void run() { tcpLoop(); }
        }, "s7-accept");
        acceptThread.start();

        httpThread = new Thread(new Runnable() {
            @Override public void run() { httpLoop(); }
        }, "s7-http");
        httpThread.start();

        S7WidgetProvider.pushUpdate(this, last, MeasureStore.prev(this));
        Log.i(TAG, "S7 数据服务已启动 TCP 0.0.0.0:" + PORT_S7 + " 模式: 抓包+自动应答  日志: " + logFile);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg(); // START_STICKY拉活时重申前台
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly(srvTcp); srvTcp = null;
        closeQuietly(srvHttp); srvHttp = null;
        synchronized (this) {
            if (logWriter != null) {
                try { logWriter.close(); } catch (IOException ignored) {}
                logWriter = null;
            }
        }
        Log.i(TAG, "S7 数据服务已停止");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 前台通知

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // targetSdk 34 要求 FGS 声明类型; specialUse=常驻局域网监听服务, 无运行时长上限
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        S7Protocol.Measure m = last;
        int pending = MeasureStore.pendingCount(this);
        String text;
        if (m != null) {
            text = String.format(Locale.US, "最新 %.2f kg · %s%s · 监听 :%d",
                    m.weightKg, MeasureStore.dispTime(m),
                    pending > 0 ? " · 待认领" + pending + "条" : "", PORT_S7);
        } else {
            text = "等待秤连接 TCP 0.0.0.0:" + PORT_S7 + "(休眠功耗≈0)";
        }
        // 点通知进历史/认领页处理待认领数据
        Intent it = new Intent(this, HistoryActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void refreshNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "通知刷新失败: " + e);
        }
    }

    // ---------------------------------------------------------------- TCP 8899

    private void tcpLoop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT_S7), 4); // backlog=4, 同Python listen(4)
            srvTcp = ss;
            while (running) {
                Socket conn;
                try {
                    conn = ss.accept();
                } catch (SocketException e) {
                    break; // 服务关闭
                }
                final Socket c = conn;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handleConn(c); }
                }, "s7-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            Log.e(TAG, "监听 " + PORT_S7 + " 失败: " + e);
            stopSelf();
        }
    }

    /**
     * handle_conn(conn, addr) 的移植 (s7_server.py:165)。
     * 与Python一致的模型: 一次recv当作一帧处理(秤帧≤64B, 真机验证一个TCP段=一帧);
     * 非标准帧记日志后continue; 所有合法帧都按同编码回显应答(即使CRC FAIL, 与Python一致)。
     */
    private void handleConn(Socket conn) {
        InetSocketAddress peer = (InetSocketAddress) conn.getRemoteSocketAddress();
        String ip = peer.getAddress() != null ? peer.getAddress().getHostAddress() : "?";
        int port = peer.getPort();
        conns++;
        scaleIp = ip;
        lastScaleIp = ip;
        lastScaleConnAt = System.currentTimeMillis();
        lastSeen = MeasureStore.nowIso();
        logf("==== CONNECT " + ip + ":" + port + " @ " + lastSeen + " ====");
        Log.i(TAG, "[+] 秤已连接: " + ip + ":" + port);
        try { conn.setSoTimeout(RECV_TIMEOUT_MS); } catch (SocketException ignored) {}
        long total = 0;
        try {
            InputStream in = conn.getInputStream();
            OutputStream out = conn.getOutputStream();
            byte[] buf = new byte[4096];
            while (true) {
                int n = in.read(buf);
                if (n < 0) {
                    Log.i(TAG, "[-] " + ip + " 断开(EOF) 共" + total + "B");
                    break;
                }
                total += n;
                byte[] wire = Arrays.copyOf(buf, n);
                S7Protocol.Unwrapped u = S7Protocol.unwrap(wire);
                if (u.plain == null) {
                    Log.i(TAG, "?非标准帧(无55AA头) " + n + "B");
                    logf("RECV RAW(非标准帧) " + S7Protocol.hex(wire) + "\n");
                    continue;
                }
                logf("RECV " + (u.wasXored ? "XOR" : "RAW") + " " + S7Protocol.hex(u.plain) + "\n");
                Log.i(TAG, "解码 " + S7Protocol.describe(u.plain));

                if (u.plain[26] == (byte) 0x80 && u.plain[27] == 0x62) {   // 称重记录
                    S7Protocol.Measure m = S7Protocol.parseMeasure(u.plain);
                    if (m != null) {
                        m.mac = S7Protocol.macColon(u.plain, 12);  // deviceId=帧内MAC, 秤IP不固定以源地址记录
                        m.timeLocalIso = MeasureStore.nowIso();
                        m.timeLocalMs = System.currentTimeMillis();
                        Log.i(TAG, "★ 称重: " + String.format(Locale.US, "%.2f", m.weightKg)
                                + " kg raw_ts=" + m.timestampRaw + " ip=" + ip
                                + " 阻抗=" + Arrays.toString(m.impedanceChannels));
                        onMeasure(m);
                    }
                }
                // 应答: 回显 + [3]|=0x01 + [26]|=0x40 + [18:24)=秤MAC, 用与收到帧相同的编码上送
                byte[] replyPlain = S7Protocol.buildReply(u.plain);
                byte[] replyWire = u.wasXored ? S7Protocol.xor55(replyPlain) : replyPlain;
                try {
                    out.write(replyWire);
                    out.flush();
                    logf("SEND " + (u.wasXored ? "XOR" : "RAW") + " " + S7Protocol.hex(replyPlain) + "\n");
                } catch (IOException e) {
                    Log.w(TAG, "[!] 应答失败: " + e);
                }
            }
        } catch (SocketTimeoutException e) {
            Log.i(TAG, "[-] " + ip + " 120s无数据");
        } catch (IOException e) {
            Log.i(TAG, "[-] " + ip + " 异常: " + e);
        } finally {
            logf("==== DISCONNECT " + ip + " @ " + MeasureStore.nowIso() + " total=" + total + "B ====\n");
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    private void onMeasure(S7Protocol.Measure m) {
        MeasureStore.append(this, m);   // 以"待认领"落盘, 不直接进个人数据
        last = m;
        lastSeen = m.timeLocalIso;
        // 小组件/趋势图只显示已认领数据 —— 待认领数据不推送, 误踩称不影响趋势
        S7WidgetProvider.pushUpdate(this, MeasureStore.last(this), MeasureStore.prev(this));
        refreshNotification();
    }

    // ---------------------------------------------------------------- HTTP 8898

    /**
     * start_http(port_http, status) 的移植 (s7_server.py:228):
     *   /widget 纯文本一行(适合小部件)  /latest 最新一条JSON
     *   /all 全部记录JSONL              /status 服务器状态
     */
    private void httpLoop() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT_HTTP), 8);
            srvHttp = ss;
            Log.i(TAG, "HTTP状态服务 http://0.0.0.0:" + PORT_HTTP + "/widget|/latest|/all|/status");
            while (running) {
                final Socket c;
                try {
                    c = ss.accept();
                } catch (SocketException e) {
                    break;
                }
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { serveHttp(c); }
                }, "s7-httpc");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            Log.w(TAG, "HTTP状态服务启动失败: " + e);
        }
    }

    private void serveHttp(Socket c) {
        try {
            c.setSoTimeout(10000);
            InputStream in = c.getInputStream();
            // 读完整个请求头块(请求行+头部直到空行)再应答: 提前停下时接收缓冲
            // 还有未读字节, close()会触发RST并与200响应竞争, 客户端随机"connection reset"
            StringBuilder reqLine = new StringBuilder();
            StringBuilder line = new StringBuilder();
            boolean first = true;
            int ch;
            while ((ch = in.read()) >= 0) {
                if (ch == '\n') {
                    if (first) { reqLine.append(line); first = false; }
                    else if (line.length() == 0) break;   // 空行=头部结束
                    line.setLength(0);
                } else if (ch != '\r') {
                    line.append((char) ch);
                }
            }
            String path = reqLine.length() > 0 ? reqLine.toString().split(" ")[1] : "";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            String body;
            String ctype;
            if ("/widget".equals(path)) {
                body = widgetText();
                ctype = "text/plain; charset=utf-8";
            } else if ("/latest".equals(path)) {
                S7Protocol.Measure m = last;
                body = m == null ? "{}" : MeasureStore.jsonLine(m);
                ctype = "application/json; charset=utf-8";
            } else if ("/all".equals(path)) {
                body = MeasureStore.readAll(this);
                ctype = "application/json; charset=utf-8";
            } else if ("/status".equals(path)) {
                body = statusJson();
                ctype = "application/json; charset=utf-8";
            } else {
                writeHttp(c, "404 Not Found", "text/plain; charset=utf-8", "not found\n");
                return;
            }
            writeHttp(c, "200 OK", ctype, body);
        } catch (Exception e) {
            Log.w(TAG, "HTTP处理异常: " + e);
        } finally {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    private void writeHttp(Socket c, String status, String ctype, String body) throws IOException {
        OutputStream out = c.getOutputStream();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + status + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    /** /widget 一行文本: 已认领口径 "63.5 kg · 10-25 15:04"; 有待认领追加提示 */
    private String widgetText() {
        S7Protocol.Measure m = MeasureStore.last(this);   // 已认领的最新一条
        int pending = MeasureStore.pendingCount(this);
        if (m == null) return pending > 0 ? "待认领 " + pending + " 条" : "暂无称重";
        String s = String.format(Locale.US, "%.1f kg · %s", m.weightKg, MeasureStore.dispTime(m));
        return pending > 0 ? s + " · 待认领" + pending + "条" : s;
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"scale_ip\": ").append(q(scaleIp)).append(", ");
        sb.append("\"connections\": ").append(conns).append(", ");
        sb.append("\"last_seen\": ").append(q(lastSeen)).append(", ");
        sb.append("\"started\": ").append(q(started)).append("}");
        return sb.toString();
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ---------------------------------------------------------------- 抓包日志

    private void logf(String s) {
        synchronized (this) {
            if (logWriter == null) {
                try {
                    logWriter = new OutputStreamWriter(new FileOutputStream(logFile, true),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return;
                }
            }
            try {
                logWriter.write(s);
                logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
