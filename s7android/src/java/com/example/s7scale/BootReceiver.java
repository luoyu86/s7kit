package com.example.s7scale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启: BOOT_COMPLETED → 拉起前台服务(Android允许在开机广播中启动FGS) */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "S7Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            context.startForegroundService(new Intent(context, S7Service.class));
            Log.i(TAG, "开机自启: 已请求启动前台服务");
        } catch (Exception e) {
            // 厂商ROM可能拦截, 需在MIUI里开"自启动"(见README_DEPLOY.md)
            Log.w(TAG, "开机自启失败: " + e);
        }
    }
}
