package com.example.s7scale;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.List;

/**
 * 小组件图表位图渲染 —— RemoteViews 不支持自绘View, 趋势图/迷你曲线/目标环
 * 全部用 Canvas 预渲染成 Bitmap 再 setImageViewBitmap。
 * 配色取自设计稿采样: 主mint #68D0B8 / 深字 #0F1A1A / 卡片 #FBFDFC / 面积渐变 #C8E8DC→透明。
 * 位图建议按布局 dp 的 ~2 倍像素渲染保证清晰。
 */
public final class WidgetCharts {

    public static final int MINT = 0xFF68CDB2;        // 线/环/徽章主色
    public static final int MINT_SOFT = 0xFF9ADFCC;   // 渐变端/浅环
    public static final int TEXT_DARK = 0xFF16211F;   // 大数字/末值
    public static final int GRID = 0x14000000;        // 极淡网格
    public static final int DOWN_GREEN = 0xFF35B895;  // 下降绿
    public static final int UP_CORAL = 0xFFE58A6F;    // 上升暖橙

    private WidgetCharts() {}

    /**
     * 趋势面积图: pts = float[]{weightKg...} 升序(时间序)。
     * dots=数据圆点, endLabel=末值文字(画在末点右侧, 可null), grid=淡网格。
     */
    public static Bitmap trendChart(float[] weights, int w, int h,
                                    boolean dots, String endLabel, boolean grid) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.setDensity(0);                      // 我们自己按像素画, 不受密度缩放
        if (weights == null || weights.length == 0) return bmp;

        float padL = h * 0.06f, padR = endLabel != null ? h * 0.30f : h * 0.06f;
        float padT = h * 0.16f, padB = h * 0.12f;
        RectF plot = new RectF(padL, padT, w - padR, h - padB);

        if (grid) {
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setColor(GRID);
            gp.setStrokeWidth(Math.max(1, h / 160f));
            for (int i = 0; i <= 4; i++) {
                float x = plot.left + plot.width() * i / 4f;
                cv.drawLine(x, plot.top, x, plot.bottom, gp);
            }
            for (int i = 0; i <= 2; i++) {
                float y = plot.top + plot.height() * i / 2f;
                cv.drawLine(plot.left, y, plot.right, y, gp);
            }
        }

        int n = weights.length;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : weights) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 0.8f) { float mid = (max + min) / 2f; min = mid - 0.4f; max = mid + 0.4f; }
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? plot.centerX() : plot.left + plot.width() * i / (n - 1);
            ys[i] = plot.bottom - plot.height() * (weights[i] - min) / (max - min);
        }

        // 平滑曲线(中点二次贝塞尔: 控制点=数据点, 终点=相邻中点)
        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n - 1; i++) {
            float mx = (xs[i] + xs[i + 1]) / 2f, my = (ys[i] + ys[i + 1]) / 2f;
            line.quadTo(xs[i], ys[i], mx, my);
        }
        if (n > 1) line.lineTo(xs[n - 1], ys[n - 1]);

        // 面积渐变填充
        Path area = new Path(line);
        area.lineTo(xs[n - 1], plot.bottom);
        area.lineTo(xs[0], plot.bottom);
        area.close();
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setShader(new LinearGradient(0, plot.top, 0, plot.bottom,
                0xB3BFE7D6, 0x0CBFE7D6, Shader.TileMode.CLAMP));
        cv.drawPath(area, ap);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(MINT);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(Math.max(2.5f, h / 55f));
        lp.setStrokeCap(Paint.Cap.ROUND);
        cv.drawPath(line, lp);

        if (dots) {
            Paint dp2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp2.setColor(0xFFFFFFFF);
            dp2.setStyle(Paint.Style.FILL);
            float r = Math.max(3f, h / 34f);
            for (int i = 0; i < n; i++) {
                cv.drawCircle(xs[i], ys[i], r, dp2);
                dp2.setColor(MINT);
                dp2.setStyle(Paint.Style.STROKE);
                dp2.setStrokeWidth(Math.max(2f, h / 42f));
                cv.drawCircle(xs[i], ys[i], r, dp2);
                dp2.setStyle(Paint.Style.FILL);
            }
        }

        if (endLabel != null && endLabel.length() > 0) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(TEXT_DARK);
            tp.setTextSize(h * 0.30f);
            tp.setFakeBoldText(true);
            float tw = tp.measureText(endLabel);
            float tx = Math.min(xs[n - 1] + h * 0.12f, w - tw - 2);
            float ty = ys[n - 1] + tp.getTextSize() * 0.35f;
            // 末点在高位时标签会被上边缘裁掉, 夹到画布内
            ty = Math.max(ty, tp.getTextSize() * 1.05f);
            ty = Math.min(ty, h - tp.getTextSize() * 0.12f);
            cv.drawText(endLabel, tx, ty, tp);
        }
        return bmp;
    }

    /**
     * 2×2 迷你锯齿曲线(总览板): 折线不平滑 + 极淡填充 + 贯穿淡基准线。
     */
    public static Bitmap sparkline(float[] weights, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.setDensity(0);
        if (weights == null || weights.length == 0) return bmp;

        float padL = w * 0.02f, padR = w * 0.02f;
        float plotT = h * 0.20f, baseY = h * 0.80f;

        // 贯穿淡基准线
        Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        base.setColor(0x2235B895);
        base.setStrokeWidth(Math.max(1.2f, h / 70f));
        cv.drawLine(padL, baseY, w - padR, baseY, base);

        int n = weights.length;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : weights) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 0.8f) { float mid = (max + min) / 2f; min = mid - 0.4f; max = mid + 0.4f; }
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? w / 2f : padL + (w - padL - padR) * i / (n - 1);
            ys[i] = baseY - (baseY - plotT) * (weights[i] - min) / (max - min);
        }

        // 锯齿折线(不平滑)
        Path line = new Path();
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) line.lineTo(xs[i], ys[i]);

        Path area = new Path(line);
        area.lineTo(xs[n - 1], baseY);
        area.lineTo(xs[0], baseY);
        area.close();
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setShader(new LinearGradient(0, plotT, 0, baseY,
                0x66BFE7D6, 0x08BFE7D6, Shader.TileMode.CLAMP));
        cv.drawPath(area, ap);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(MINT);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(Math.max(2f, h / 38f));
        lp.setStrokeCap(Paint.Cap.ROUND);
        lp.setStrokeJoin(Paint.Join.ROUND);
        cv.drawPath(line, lp);
        return bmp;
    }

    /** 目标环: frac 0..1 进度, size 像素(正方形) */
    public static Bitmap goalRing(float frac, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.setDensity(0);
        float stroke = size * 0.068f;
        RectF rect = new RectF(stroke, stroke, size - stroke, size - stroke);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        // 轨道
        p.setColor(0xFFEAF2EF);
        p.setStrokeWidth(stroke * 0.92f);
        cv.drawArc(rect, 0, 360, false, p);
        // 进度 mint 渐变
        float sweep = 360f * Math.max(0.02f, Math.min(1f, frac));
        p.setShader(new LinearGradient(size * 0.2f, size * 0.15f, size * 0.85f, size * 0.9f,
                MINT_SOFT, MINT, Shader.TileMode.CLAMP));
        p.setStrokeWidth(stroke);
        cv.drawArc(rect, -90, sweep, false, p);
        return bmp;
    }
}
