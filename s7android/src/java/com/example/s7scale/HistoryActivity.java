package com.example.s7scale;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 历史列表: 最近50条称重记录, 最新在前(小组件点击进入) */
public class HistoryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        ListView lv = findViewById(R.id.listHistory);

        List<S7Protocol.Measure> rec = MeasureStore.readRecent(this, 50);
        List<String> rows = new ArrayList<String>();
        if (rec.isEmpty()) {
            rows.add("暂无记录 —— 等秤第一次连入");
        }
        for (S7Protocol.Measure m : rec) {
            StringBuilder sb = new StringBuilder(96);
            sb.append(String.format(Locale.US, "%.2f kg", m.weightKg))
              .append("   ").append(MeasureStore.fullTime(m));
            if (m.mac != null && !m.mac.isEmpty()) sb.append("\n设备 ").append(m.mac);
            sb.append("   帧内时间戳 ").append(m.timestampRaw);
            rows.add(sb.toString());
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                R.layout.item_history, R.id.itemText, rows);
        lv.setAdapter(ad);
    }
}
