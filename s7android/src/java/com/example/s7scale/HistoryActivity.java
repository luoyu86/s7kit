package com.example.s7scale;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 历史/认领页: 最近50条称重记录, 最新在前。
 *
 * 认领模型(防误踩称): 秤上传的新数据默认"待认领", 不进趋势图/个人数据;
 * 本页对每条记录可「认领」(计入个人数据) 或「删除」(不是我的, 直接移除);
 * 顶部"全部认领"可批量确认。 点击条目弹出操作对话框。
 */
public class HistoryActivity extends Activity {

    private List<S7Protocol.Measure> rec;
    private List<String> rows;
    private ArrayAdapter<String> adapter;
    private TextView tvPendingHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        tvPendingHint = findViewById(R.id.tvPendingHint);
        Button btnClaimAll = findViewById(R.id.btnClaimAll);
        ListView lv = findViewById(R.id.listHistory);

        rec = new ArrayList<S7Protocol.Measure>();
        rows = new ArrayList<String>();
        adapter = new ArrayAdapter<String>(this, R.layout.item_history, R.id.itemText, rows);
        lv.setAdapter(adapter);

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < rec.size()) showActionDialog(rec.get(position));
            }
        });

        btnClaimAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = MeasureStore.claimAll(HistoryActivity.this);
                Toast.makeText(HistoryActivity.this,
                        n > 0 ? "已认领 " + n + " 条" : "没有待认领记录",
                        Toast.LENGTH_SHORT).show();
                refreshWidgets();
                reload();
            }
        });

        reload();
    }

    private void reload() {
        rec.clear();
        rows.clear();
        rec.addAll(MeasureStore.readRecentAll(this, 50));
        if (rec.isEmpty()) {
            rows.add("暂无记录 —— 等秤第一次连入");
        }
        for (S7Protocol.Measure m : rec) {
            rows.add(formatRow(m));
        }
        adapter.notifyDataSetChanged();

        int pending = MeasureStore.pendingCount(this);
        tvPendingHint.setText(pending > 0
                ? String.format(Locale.US, "待认领 %d 条 —— 点击条目认领或删除(防止误踩称进趋势图)", pending)
                : "全部记录均已认领, 已计入体重趋势图");
    }

    private String formatRow(S7Protocol.Measure m) {
        StringBuilder sb = new StringBuilder(96);
        boolean claimed = MeasureStore.isClaimed(m);
        sb.append(claimed ? "✓已认领  " : "○待认领  ")
          .append(String.format(Locale.US, "%.2f kg", m.weightKg))
          .append("   ").append(MeasureStore.fullTime(m));
        if (m.mac != null && !m.mac.isEmpty()) sb.append("\n设备 ").append(m.mac);
        sb.append("   帧内时间戳 ").append(m.timestampRaw);
        return sb.toString();
    }

    /** 点击条目: 认领 / 删除 / 取消 */
    private void showActionDialog(final S7Protocol.Measure m) {
        String title = String.format(Locale.US, "%.2f kg  ·  %s", m.weightKg, MeasureStore.fullTime(m));
        boolean claimed = MeasureStore.isClaimed(m);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setMessage(claimed
                ? "这条数据已认领, 计入趋势图。"
                : "这条数据待认领。是我的→认领计入趋势图; 误踩/不是我的→删除。");

        if (!claimed) {
            b.setPositiveButton("认领(是我的)", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    if (MeasureStore.claim(HistoryActivity.this, m)) {
                        Toast.makeText(HistoryActivity.this, "已认领", Toast.LENGTH_SHORT).show();
                        refreshWidgets();
                    }
                    reload();
                }
            });
        }
        b.setNegativeButton(claimed ? "删除" : "删除(不是我的)", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (MeasureStore.delete(HistoryActivity.this, m)) {
                    Toast.makeText(HistoryActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    refreshWidgets();
                }
                reload();
            }
        });
        b.setNeutralButton("取消", null);
        b.show();
    }

    /** 认领/删除后小组件立即刷新(与服务收到数据同一推送路径) */
    private void refreshWidgets() {
        S7WidgetProvider.pushUpdate(this, MeasureStore.last(this), MeasureStore.prev(this));
    }
}
