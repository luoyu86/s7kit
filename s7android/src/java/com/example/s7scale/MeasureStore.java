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
 * 行格式与参照实现 s7_server.py 输出逐字段一致(键顺序一致), 末尾追加 APP 层 status 字段:
 *   raw_param / count / unknown7c / timestamp_raw / weight_kg / impedance_channels
 *   / time_local(手机本地接收时间) / mac(秤WiFi MAC=deviceId) / status(认领状态)
 * 帧内原始时间戳保存在 timestamp_raw 字段 —— 手机本地时间与帧内时间都存(需求要求)。
 *
 * 认领模型(防误踩称): 新称重一律落盘为 status="pending"(待认领), 不进个人数据;
 * 用户在历史页认领后 status="claimed" 才计入趋势图/周均值/小组件/最新体重;
 * "删除"(不是我的)直接从文件移除该行。 旧数据(无 status 字段)视为已认领, 保持历史连续。
 */
public final class MeasureStore {

    private static final String TAG = "S7Store";
    private static final Object LOCK = new Object();

    /** 待认领: 秤刚上传, 尚未确认是本人 */
    public static final String STATUS_PENDING = "pending";
    /** 已认领: 确认是本人数据, 计入个人数据与趋势图 */
    public static final String STATUS_CLAIMED = "claimed";

    private static File cacheFile;
    private static S7Protocol.Measure lastCached;
    private static S7Protocol.Measure prevCached;

    private MeasureStore() {}

    public static File file(Context c) {
        if (cacheFile == null) cacheFile = new File(c.getFilesDir(), "measurements.jsonl");
        return cacheFile;
    }

    /** 记录是否已认领(计入个人数据)。 null/空 status 视为已认领(旧数据兼容) */
    public static boolean isClaimed(S7Protocol.Measure m) {
        return m == null || m.status == null || m.status.isEmpty() || STATUS_CLAIMED.equals(m.status);
    }

    /**
     * 追加一条记录(线程安全)。 新记录一律以 pending(待认领)落盘,
     * 不刷新 last/prev 缓存 —— 待认领数据不进入个人数据。
     */
    public static void append(Context c, S7Protocol.Measure m) {
        synchronized (LOCK) {
            if (m.status == null || m.status.isEmpty()) m.status = STATUS_PENDING;
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
        }
    }

    /** 最近一条【已认领】(进程内优先用缓存, 冷启动时从文件回填) */
    public static S7Protocol.Measure last(Context c) {
        synchronized (LOCK) {
            if (lastCached == null) warmup(c);
            return lastCached;
        }
    }

    /** 最近一条【已认领】的前一条(小组件"较上次增减"用) */
    public static S7Protocol.Measure prev(Context c) {
        synchronized (LOCK) {
            if (lastCached == null) warmup(c);
            return prevCached;
        }
    }

    private static void warmup(Context c) {
        List<S7Protocol.Measure> all = readClaimed(c);
        if (!all.isEmpty()) {
            lastCached = all.get(all.size() - 1);
            prevCached = all.size() >= 2 ? all.get(all.size() - 2) : null;
        }
    }

    /** 使 last/prev 缓存失效(认领/删除后调用), 下次访问从文件重算 */
    private static void invalidate() {
        lastCached = null;
        prevCached = null;
    }

    /** 全部记录(最旧在前, 含待认领) */
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

    /** 只含【已认领】的记录(最旧在前) —— 个人数据/趋势图口径 */
    public static List<S7Protocol.Measure> readClaimed(Context c) {
        List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
        for (S7Protocol.Measure m : readAllParsed(c)) {
            if (isClaimed(m)) out.add(m);
        }
        return out;
    }

    /** 待认领记录, 最新在前(认领页用) */
    public static List<S7Protocol.Measure> readPending(Context c) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!isClaimed(all.get(i))) out.add(all.get(i));
        }
        return out;
    }

    /** 待认领条数(主页提示/通知角标) */
    public static int pendingCount(Context c) {
        int n = 0;
        for (S7Protocol.Measure m : readAllParsed(c)) {
            if (!isClaimed(m)) n++;
        }
        return n;
    }

    /**
     * 认领一条记录: 按 time_local(+weight 二次校验)定位, status 改为 claimed。
     * 返回是否找到并修改。 全文件重写(JSONL 行数少, 重写成本可忽略)。
     */
    public static boolean claim(Context c, S7Protocol.Measure target) {
        return rewrite(c, target, STATUS_CLAIMED);
    }

    /**
     * 删除一条记录(不是我的): 从文件移除该行。 返回是否找到并删除。
     */
    public static boolean delete(Context c, S7Protocol.Measure target) {
        return rewrite(c, target, null);   // newStatus=null 表示删除
    }

    /**
     * 全部认领: 所有 pending -> claimed。 返回认领条数。
     */
    public static int claimAll(Context c) {
        synchronized (LOCK) {
            List<S7Protocol.Measure> all = readAllParsed(c);
            int n = 0;
            for (S7Protocol.Measure m : all) {
                if (!isClaimed(m)) {
                    m.status = STATUS_CLAIMED;
                    n++;
                }
            }
            if (n > 0) {
                writeAll(c, all);
                invalidate();
            }
            return n;
        }
    }

    /** 重写文件: 匹配 target 的行改为 newStatus, newStatus=null 则删除该行 */
    private static boolean rewrite(Context c, S7Protocol.Measure target, String newStatus) {
        if (target == null) return false;
        synchronized (LOCK) {
            List<S7Protocol.Measure> all = readAllParsed(c);
            List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
            boolean hit = false;
            for (S7Protocol.Measure m : all) {
                boolean match = sameRecord(m, target);
                if (match && !hit) {
                    hit = true;
                    if (newStatus != null) {
                        m.status = newStatus;
                        out.add(m);
                    }
                    // newStatus == null -> 删除, 不加入 out
                } else {
                    out.add(m);
                }
            }
            if (hit) {
                writeAll(c, out);
                invalidate();
            }
            return hit;
        }
    }

    /** 同一条记录判定: time_local 唯一(微秒精度), weight 二次校验防极端同刻并发 */
    private static boolean sameRecord(S7Protocol.Measure a, S7Protocol.Measure b) {
        if (a.timeLocalIso == null || b.timeLocalIso == null) return false;
        return a.timeLocalIso.equals(b.timeLocalIso)
                && Math.abs(a.weightKg - b.weightKg) < 1e-9;
    }

    /** 整文件重写(保留原顺序)。 先写 .tmp 再改名, 防写一半把原数据弄丢 */
    private static void writeAll(Context c, List<S7Protocol.Measure> list) {
        StringBuilder sb = new StringBuilder(list.size() * 256);
        for (S7Protocol.Measure m : list) {
            sb.append(jsonLine(m)).append('\n');
        }
        File target = file(c);
        File tmp = new File(target.getPath() + ".tmp");
        FileOutputStream fo = null;
        try {
            fo = new FileOutputStream(tmp, false);
            fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fo.flush();
            fo.getFD().sync();
        } catch (IOException e) {
            Log.w(TAG, "writeAll失败(已保留原文件): " + e);
            tmp.delete();
            return;
        } finally {
            if (fo != null) try { fo.close(); } catch (IOException ignored) {}
        }
        if (!tmp.renameTo(target)) {
            // 跨设备/占用时的兜底: 直接覆盖
            try {
                FileOutputStream fo2 = new FileOutputStream(target, false);
                fo2.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fo2.flush();
                fo2.close();
                tmp.delete();
            } catch (IOException e) {
                Log.w(TAG, "writeAll改名/覆盖失败: " + e);
            }
        }
    }

    /** 最近 n 条【已认领】, 最新在前(小组件序列用) */
    public static List<S7Protocol.Measure> readRecent(Context c, int n) {
        List<S7Protocol.Measure> all = readClaimed(c);
        List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
        return out;
    }

    /** 最近 n 条(含待认领), 最新在前 —— 认领页列表用 */
    public static List<S7Protocol.Measure> readRecentAll(Context c, int n) {
        List<S7Protocol.Measure> all = readAllParsed(c);
        List<S7Protocol.Measure> out = new ArrayList<S7Protocol.Measure>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) out.add(all.get(i));
        return out;
    }

    /**
     * 最近 days 天的"每日最后一条"序列(小组件趋势图用, 只统计已认领):
     * float[]{epochDay, weightKg} 升序; 当天没记录的天跳过(曲线按现有天数连线)。
     */
    public static List<float[]> dailySeries(Context c, int days) {
        List<S7Protocol.Measure> all = readClaimed(c);
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

    /** 本周(周一起)有记录日的均值 kg; 无记录返回 NaN (4x4"本周均值", 只统计已认领) */
    public static double weekMean(Context c) {
        List<S7Protocol.Measure> all = readClaimed(c);
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

    /**
     * 单行JSON —— 键顺序与Python json.dumps(dict)输出一致, 末尾追加APP层 status 键。
     * 旧数据(无 status)解析时按已认领处理, 写回时会补上 status 字段。
     */
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
        sb.append("\"mac\": \"").append(m.mac == null ? "" : m.mac).append("\", ");
        sb.append("\"status\": \"").append(isClaimed(m) ? STATUS_CLAIMED : m.status).append("\"}");
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
        m.status = o.optString("status", STATUS_CLAIMED);   // 旧数据无 status = 已认领
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
