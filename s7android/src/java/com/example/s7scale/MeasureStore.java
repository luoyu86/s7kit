package com.example.s7scale;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * measurements.jsonl 存取 —— 内部存储 filesDir/measurements.jsonl,
 * 行格式与参照实现 s7_server.py 输出逐字段一致(键顺序一致):
 *   raw_param / count / unknown7c / timestamp_raw / weight_kg / impedance_channels
 *   / time_local(手机本地接收时间) / mac(秤WiFi MAC=deviceId)
 * 帧内原始时间戳保存在 timestamp_raw 字段 —— 手机本地时间与帧内时间都存(需求要求)。
 */
public final class MeasureStore {

    private static final String TAG = "S7Store";
    private static final Object LOCK = new Object();

    private static File cacheFile;
    private static S7Protocol.Measure lastCached;
    private static S7Protocol.Measure prevCached;

    private MeasureStore() {}

    public static File file(Context c) {
        if (cacheFile == null) cacheFile = new File(c.getFilesDir(), "measurements.jsonl");
        return cacheFile;
    }

    /** 追加一条记录(线程安全), 并刷新内存中的 last/prev */
    public static void append(Context c, S7Protocol.Measure m) {
        synchronized (LOCK) {
            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(file(c), true);
                fo.write((jsonLine(m) + "\n").getBytes(StandardCharsets.UTF_8));
                fo.flush();
            } catch (IOException e) {
                Log.w(TAG, "append失败: " + e);
            } finally {
                if (fo != null) try { fo.close(); } catch (IOException ignored) {}
            }
            prevCached = lastCached;
            lastCached = m;
        }
    }

    /** 最近一条(进程内优先用缓存, 冷启动时从文件回填 —— 对应Python重启后预载逻辑) */
    public static S7Protocol.Measure last(Context c) {
        synchronized (LOCK) {
            if (lastCached == null) warmup(c);
            return lastCached;
        }
    }

    /** 最近一条的前一条(小组件"较上次增减"用) */
    public static S7Protocol.Measure prev(Context c) {
        synchronized (LOCK) {
            if (lastCached == null) warmup(c);
            return prevCached;
        }
    }

    private static void warmup(Context c) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        if (!all.isEmpty()) {
            lastCached = all.get(all.size() - 1);
            prevCached = all.size() >= 2 ? all.get(all.size() - 2) : null;
        }
    }

    /** 全部记录(最旧在前) */
    public static List<S7Protocol.Measure> readAllParsed(Context c) {
        synchronized (LOCK) {
            List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
            String text = readAll(c);
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    out.add(parseLine(line));
                } catch (Exception e) {
                    Log.w(TAG, "跳过坏行: " + e);
                }
            }
            return out;
        }
    }

    /** 最近 n 条, 最新在前(历史列表Activity用) */
    public static List<S7Protocol.Measure> readRecent(Context c, int n) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
        return out;
    }

    /**
     * 最近 days 天的"每日最后一条"序列(小组件趋势图用):
     * float[]{epochDay, weightKg} 升序; 当天没记录的天跳过(曲线按现有天数连线)。
     */
    public static List<float[]> dailySeries(Context c, int days) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        java.time.LocalDate today = java.time.LocalDate.now();
        long firstDay = today.minusDays(days - 1L).toEpochDay();
        java.util.LinkedHashMap<Long, Float> byDay = new java.util.LinkedHashMap<Long, Float>();
        for (S7Protocol.Measure m : all) {
            Long d = dayKey(m);
            if (d == null || d < firstDay) continue;
            byDay.put(d, (float) m.weightKg);      // 同日多条 -> 保留最后一条
        }
        List<float[]> out = new ArrayList<float[]>();
        for (java.util.Map.Entry<Long, Float> e : byDay.entrySet()) {
            out.add(new float[]{e.getKey(), e.getValue()});
        }
        return out;
    }

    /** 本周(周一起)有记录日的均值 kg; 无记录返回 NaN (4x4"本周均值") */
    public static double weekMean(Context c) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        long first = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toEpochDay();
        double sum = 0;
        int n = 0;
        for (S7Protocol.Measure m : all) {
            Long d = dayKey(m);
            if (d == null || d < first) continue;
            sum += m.weightKg;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** time_local 的本地日期(epochDay), 解析失败返回 null */
    private static Long dayKey(S7Protocol.Measure m) {
        try {
            return java.time.LocalDate.parse(m.timeLocalIso.substring(0, 10)).toEpochDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** 整个文件原文(/all 接口用, 与Python读 measurements.jsonl 一致) */
    public static String readAll(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return "";
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.w(TAG, "readAll失败: " + e);
                return "";
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ------------------------------------------------------------- JSON

    /** 单行JSON —— 键顺序与Python json.dumps(dict)输出一致 */
    public static String jsonLine(S7Protocol.Measure m) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"raw_param\": \"").append(m.rawParam).append("\", ");
        sb.append("\"count\": ").append(m.count).append(", ");
        sb.append("\"unknown7c\": ").append(m.unknown7c).append(", ");
        sb.append("\"timestamp_raw\": ").append(m.timestampRaw).append(", ");
        sb.append("\"weight_kg\": ").append(doubleRepr(m.weightKg)).append(", ");
        sb.append("\"impedance_channels\": [");
        if (m.impedanceChannels != null) {
            for (int i = 0; i < m.impedanceChannels.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(m.impedanceChannels[i]);
            }
        }
        sb.append("], ");
        sb.append("\"time_local\": \"").append(m.timeLocalIso == null ? "" : m.timeLocalIso).append("\", ");
        sb.append("\"mac\": \"").append(m.mac == null ? "" : m.mac).append("\"}");
        return sb.toString();
    }

    /** Python float 的 json 表示(63.0 -> "63.0", 63.5 -> "63.5") */
    private static String doubleRepr(double v) {
        return Double.toString(v);
    }

    private static S7Protocol.Measure parseLine(String line) throws Exception {
        JSONObject o = new JSONObject(line);
        S7Protocol.Measure m = new S7Protocol.Measure();
        m.rawParam = o.optString("raw_param", "");
        m.count = o.optInt("count", 0);
        m.unknown7c = o.optInt("unknown7c", 0);
        m.timestampRaw = o.optLong("timestamp_raw", 0);
        m.weightKg = o.optDouble("weight_kg", 0);
        JSONArray ja = o.optJSONArray("impedance_channels");
        if (ja != null) {
            int[] arr = new int[ja.length()];
            for (int i = 0; i < ja.length(); i++) arr[i] = ja.optInt(i);
            m.impedanceChannels = arr;
        }
        m.timeLocalIso = o.optString("time_local", "");
        m.mac = o.optString("mac", "");
        return m;
    }

    // ------------------------------------------------------------- 展示

    private static final DateTimeFormatter ISO_US =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US);

    /** 手机本地接收时间 isoformat(与Python datetime.now().isoformat() 同形, 6位微秒) */
    public static String nowIso() {
        return LocalDateTime.now().format(ISO_US);
    }

    /** "2026-10-25T15:04:30.010290" -> "10-25 15:04" (与Python /widget 的截取规则一致) */
    public static String dispTime(S7Protocol.Measure m) {
        if (m == null || m.timeLocalIso == null || m.timeLocalIso.length() < 16) return "--";
        return m.timeLocalIso.substring(5, 16).replace('T', ' ');
    }

    /** 完整时间 "2026-10-25 15:04:30" (历史列表用) */
    public static String fullTime(S7Protocol.Measure m) {
        if (m == null || m.timeLocalIso == null || m.timeLocalIso.length() < 19) return "--";
        return m.timeLocalIso.substring(0, 19).replace('T', ' ');
    }
}
