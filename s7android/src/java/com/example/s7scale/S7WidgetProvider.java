package com.example.s7scale;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件(设计稿三规格): 2×2紧凑 / 4×2趋势 / 4×4完整。
 * 刷新策略(需求): 服务收到数据时直接 updateAppWidget 推送, updatePeriodMillis=0 无轮询。
 * 点击小组件 → 打开历史列表。图表/目标环用 WidgetCharts 预渲染位图(RemoteViews不支持自绘)。
 *
 * 三个 receiver 子类(S7Widget2x2/4x2/4x4)共用本类逻辑, 按 appwidget-provider 的
 * initialLayout 区分规格。设置: 身高(BMI)/目标体重 存 SharedPreferences "s7settings"。
 */
public class S7WidgetProvider extends AppWidgetProvider {

    static final Class<?>[] WIDGET_CLASSES = {
            S7Widget2x2.class, S7Widget4x2.class, S7Widget4x4.class
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        pushUpdate(context, MeasureStore.last(context), MeasureStore.prev(context));
    }

    /** 服务收到称重数据时直接调用推送刷新(三种规格全部更新) */
    public static void pushUpdate(Context ctx, S7Protocol.Measure last, S7Protocol.Measure prev) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        Intent it = new Intent(ctx, HistoryActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (Class<?> cls : WIDGET_CLASSES) {
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, cls));
            for (int id : ids) {
                AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
                int layout = (info != null) ? info.initialLayout : R.layout.widget_s7_compact;
                RemoteViews rv = buildViews(ctx, layout, last, prev);
                rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
                mgr.updateAppWidget(id, rv);
            }
        }
    }

    private static RemoteViews buildViews(Context ctx, int layout,
                                          S7Protocol.Measure last, S7Protocol.Measure prev) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);
        if (layout == R.layout.widget_s7_compact) fillCompact(rv, ctx, last, prev);
        else if (layout == R.layout.widget_s7_trend) fillTrend(rv, ctx, last);
        else fillFull(rv, ctx, last);
        return rv;
    }

    // ------------------------------------------------------------ 2×2 紧凑

    private static void fillCompact(RemoteViews rv, Context ctx,
                                    S7Protocol.Measure last, S7Protocol.Measure prev) {
        if (last == null) {
            rv.setTextViewText(R.id.wWeight, "--.-");
            rv.setTextViewText(R.id.wDelta, "暂无称重 — 等秤连入");
            rv.setViewVisibility(R.id.wSpark, android.view.View.INVISIBLE);
            return;
        }
        rv.setViewVisibility(R.id.wSpark, android.view.View.VISIBLE);
        rv.setTextViewText(R.id.wWeight, String.format(Locale.US, "%.1f", last.weightKg));

        // 较昨日(每日最后一条对比), 昨日无记录则退回"较上次"
        List<float[]> d2 = MeasureStore.dailySeries(ctx, 2);
        String txt;
        int color;
        if (d2.size() >= 2) {
            double d = d2.get(d2.size() - 1)[1] - d2.get(0)[1];
            txt = String.format(Locale.US, "较昨日 %s%.1f kg %s",
                    d > 0 ? "+" : "", d, d > 0 ? "↑" : "↓");
            color = d > 0 ? WidgetCharts.UP_CORAL : WidgetCharts.DOWN_GREEN;
        } else if (prev != null) {
            double d = last.weightKg - prev.weightKg;
            txt = String.format(Locale.US, "较上次 %s%.1f kg %s",
                    d > 0 ? "+" : "", d, d > 0 ? "↑" : "↓");
            color = d > 0 ? WidgetCharts.UP_CORAL : WidgetCharts.DOWN_GREEN;
        } else {
            txt = "首次记录";
            color = WidgetCharts.DOWN_GREEN;
        }
        rv.setTextViewText(R.id.wDelta, txt);
        rv.setTextColor(R.id.wDelta, color);

        float[] w = toWeights(MeasureStore.readRecent(ctx, 24));
        rv.setImageViewBitmap(R.id.wSpark, WidgetCharts.trendChart(w, 220, 84, false, null, false));
    }

    // ------------------------------------------------------------ 4×2 趋势

    private static void fillTrend(RemoteViews rv, Context ctx, S7Protocol.Measure last) {
        float[] w = seriesWeights(MeasureStore.dailySeries(ctx, 7));
        if (w.length < 2) w = toWeights(MeasureStore.readRecent(ctx, 7));
        rv.setImageViewBitmap(R.id.wChart, WidgetCharts.trendChart(w, 720, 260, true,
                last == null ? null : String.format(Locale.US, "%.1f", last.weightKg), true));
        String badge;
        int color;
        if (w.length < 2) {
            badge = "● 暂无数据";
            color = 0xFF8FA09A;
        } else {
            double d = w[w.length - 1] - w[0];
            if (d <= -0.15) { badge = "● 持续下降"; color = WidgetCharts.DOWN_GREEN; }
            else if (d >= 0.15) { badge = "● 有所上升"; color = WidgetCharts.UP_CORAL; }
            else { badge = "● 保持平稳"; color = 0xFF8FA09A; }
        }
        rv.setTextViewText(R.id.wBadge, badge);
        rv.setTextColor(R.id.wBadge, color);
    }

    // ------------------------------------------------------------ 4×4 完整

    private static void fillFull(RemoteViews rv, Context ctx, S7Protocol.Measure last) {
        SharedPreferences sp = ctx.getSharedPreferences("s7settings", Context.MODE_PRIVATE);
        float heightM = sp.getFloat("heightM", 1.70f);
        float targetKg = sp.getFloat("targetKg", 60.0f);
        rv.setTextViewText(R.id.wGoal, String.format(Locale.US, "%.1f kg", targetKg));

        if (last == null) {
            rv.setTextViewText(R.id.wWeight, "--.-");
            rv.setTextViewText(R.id.wWeekMean, "--.-");
            rv.setTextViewText(R.id.wBmi, "--.-");
            rv.setImageViewBitmap(R.id.wRing, WidgetCharts.goalRing(1f, 420));
            rv.setImageViewBitmap(R.id.wChart30,
                    WidgetCharts.trendChart(new float[0], 880, 320, false, null, false));
            return;
        }
        rv.setTextViewText(R.id.wWeight, String.format(Locale.US, "%.1f", last.weightKg));

        // 圆环: 达到目标=满环, 未达标按 目标/当前 比例
        float frac = last.weightKg <= targetKg ? 1f : (float) (targetKg / last.weightKg);
        rv.setImageViewBitmap(R.id.wRing, WidgetCharts.goalRing(frac, 420));

        double mean = MeasureStore.weekMean(ctx);
        rv.setTextViewText(R.id.wWeekMean,
                Double.isNaN(mean) ? "--.-" : String.format(Locale.US, "%.1f", mean));

        double bmi = heightM > 0.5 ? last.weightKg / (heightM * heightM) : Double.NaN;
        rv.setTextViewText(R.id.wBmi,
                Double.isNaN(bmi) ? "--.-" : String.format(Locale.US, "%.1f", bmi));

        float[] w30 = seriesWeights(MeasureStore.dailySeries(ctx, 30));
        if (w30.length < 2) w30 = toWeights(MeasureStore.readRecent(ctx, 30));
        rv.setImageViewBitmap(R.id.wChart30, WidgetCharts.trendChart(w30, 880, 320, true, null, false));
    }

    // ------------------------------------------------------------ 工具

    private static float[] toWeights(List<S7Protocol.Measure> recent) {
        float[] out = new float[recent.size()];
        for (int i = 0; i < out.length; i++) out[i] = (float) recent.get(recent.size() - 1 - i).weightKg;
        return out;
    }

    private static float[] seriesWeights(List<float[]> series) {
        float[] out = new float[series.size()];
        for (int i = 0; i < out.length; i++) out[i] = series.get(i)[1];
        return out;
    }
}
