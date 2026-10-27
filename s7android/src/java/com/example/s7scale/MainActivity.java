package com.example.s7scale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 启动页: 启动/停止数据服务 + "允许后台运行"(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 引导)
 */
public class MainActivity extends Activity {

    private TextView tvStatus;
    private Button btnStart, btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        Button btnBattery = findViewById(R.id.btnBattery);
        Button btnHistory = findViewById(R.id.btnHistory);
        Button btnProvision = findViewById(R.id.btnProvision);
        Button btnSettings = findViewById(R.id.btnSettings);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startForegroundService(new Intent(MainActivity.this, S7Service.class));
                Toast.makeText(MainActivity.this, "服务启动中…", Toast.LENGTH_SHORT).show();
                tvStatus.postDelayed(new Runnable() {
                    @Override public void run() { refreshStatus(); }
                }, 800);
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, S7Service.class));
                tvStatus.postDelayed(new Runnable() {
                    @Override public void run() { refreshStatus(); }
                }, 500);
            }
        });
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        });
        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            }
        });
        btnProvision.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProvisionActivity.class));
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsDialog(); }
        });
    }

    /** 身高/目标设置: 4×4小组件的 BMI 与目标环用, 保存后立即刷新小组件 */
    private void showSettingsDialog() {
        final SharedPreferences sp = getSharedPreferences("s7settings", Context.MODE_PRIVATE);
        float h = sp.getFloat("heightM", 1.70f);
        float t = sp.getFloat("targetKg", 60.0f);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etH = new EditText(this);
        etH.setHint("身高(cm)");
        etH.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etH.setText(String.format(Locale.US, "%.0f", h * 100));
        final EditText etT = new EditText(this);
        etT.setHint("目标体重(kg)");
        etT.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etT.setText(String.format(Locale.US, "%.1f", t));
        box.addView(etH);
        box.addView(etT);

        new AlertDialog.Builder(this)
                .setTitle("身高 / 目标设置")
                .setMessage("用于 4×4 小组件的 BMI 与目标环")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            float hm = Float.parseFloat(etH.getText().toString().trim()) / 100f;
                            float tk = Float.parseFloat(etT.getText().toString().trim());
                            if (hm < 0.5f || hm > 2.5f || tk < 20f || tk > 300f) {
                                Toast.makeText(MainActivity.this, "数值范围不对", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            sp.edit().putFloat("heightM", hm).putFloat("targetKg", tk).apply();
                            S7WidgetProvider.pushUpdate(MainActivity.this,
                                    MeasureStore.last(MainActivity.this),
                                    MeasureStore.prev(MainActivity.this));
                            Toast.makeText(MainActivity.this, "已保存并刷新小组件", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "格式不对", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean run = S7Service.running;
        btnStart.setEnabled(!run);
        btnStop.setEnabled(run);
        StringBuilder sb = new StringBuilder();
        sb.append(run ? "服务运行中 · 监听 0.0.0.0:" + S7Service.PORT_S7
                      : "服务未运行");
        S7Protocol.Measure m = MeasureStore.last(this);   // 已认领的最新一条
        if (m != null) {
            sb.append("\n最新: ").append(String.format(Locale.US, "%.2f kg", m.weightKg))
              .append(" · ").append(MeasureStore.dispTime(m))
              .append("\n设备: ").append(m.mac);
        }
        int pending = MeasureStore.pendingCount(this);
        if (pending > 0) {
            sb.append("\n⚠ 有 ").append(pending).append(" 条待认领 —— 点「历史/认领记录」确认");
        }
        tvStatus.setText(sb.toString());
    }

    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "请到 系统设置→省电策略 手动允许后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }
}
