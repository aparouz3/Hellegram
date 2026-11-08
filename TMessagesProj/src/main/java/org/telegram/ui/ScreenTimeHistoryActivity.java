package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.ScreenTimeTracker;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScreenTimeHistoryActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private int mode = 0;
    private List<String> days = new ArrayList<>();
    private LinearLayout root;
    private DayBarChart chart;

    public ScreenTimeHistoryActivity(Bundle args) { super(args); }
    public static ScreenTimeHistoryActivity of() { return new ScreenTimeHistoryActivity(new Bundle()); }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Screen Time History");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout toggle = new LinearLayout(context);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setGravity(Gravity.CENTER);
        TextView weekBtn = makeToggle(context, "Week", 0);
        TextView monthBtn = makeToggle(context, "Month", 1);
        toggle.addView(weekBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        toggle.addView(monthBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        root.addView(toggle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 8));

        chart = new DayBarChart(context);
        root.addView(chart, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(200), 16, 8, 16, 8));

        fragmentView = new RecyclerListView(context);
        listView = (RecyclerListView) fragmentView;
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        setFragmentView(root);
        refreshData();
        return root;
    }

    private TextView makeToggle(Context context, String label, final int m) {
        TextView t = new TextView(context);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        t.setOnClickListener(v -> { mode = m; refreshData(); });
        return t;
    }

    private void refreshData() {
        if (mode == 0) {
            days.clear();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            long now = System.currentTimeMillis();
            for (int d = 6; d >= 0; d--) days.add(sdf.format(new java.util.Date(now - d * 86400000L)));
        } else {
            Map<String, Long> m = ScreenTimeTracker.getInstance().getMonthPerDayTotals();
            days = new ArrayList<>(m.keySet());
            java.util.Collections.sort(days);
        }
        long[] vals = new long[days.size()];
        for (int i = 0; i < days.size(); i++) vals[i] = ScreenTimeTracker.getInstance().getTotalForDay(days.get(i));
        if (chart != null) chart.setData(days, vals);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        public ListAdapter(Context ctx) { context = ctx; }
        @Override
        public int getItemCount() { return days.size(); }
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(new TextCell(context, getResourceProvider())) {};
        }
        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            String day = days.get(position);
            long total = ScreenTimeTracker.getInstance().getTotalForDay(day);
            ((TextCell) holder.itemView).setTextAndValue(formatDay(day), ScreenTimeTracker.formatDuration(total), true);
        }
    }

    private String formatDay(String d) {
        try { return d.substring(6,8) + "/" + d.substring(4,6) + "/" + d.substring(0,4); }
        catch (Exception e) { return d; }
    }

    private static class DayBarChart extends View {
        private String[] labels = new String[0];
        private long[] values = new long[0];
        private long max = 1;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public DayBarChart(Context context) {
            super(context);
            barPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            textPaint.setTextSize(AndroidUtilities.dp(9));
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        }

        public void setData(List<String> days, long[] vals) {
            labels = days.toArray(new String[0]);
            values = vals != null ? vals : new long[0];
            max = 1;
            for (long v : values) if (v > max) max = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || values.length == 0) return;
            int pad = AndroidUtilities.dp(8);
            int chartH = h - AndroidUtilities.dp(24);
            int n = values.length;
            float barW = (w - pad * 2f) / n;
            for (int i = 0; i < n; i++) {
                float ratio = (float) values[i] / max;
                float bh = ratio * chartH;
                float left = pad + i * barW + barW * 0.15f;
                float right = pad + (i + 1) * barW - barW * 0.15f;
                float top = chartH - bh;
                float bottom = chartH;
                RectF r = new RectF(left, top, right, bottom);
                canvas.drawRoundRect(r, AndroidUtilities.dp(2), AndroidUtilities.dp(2), barPaint);
                if (labels.length > i && labels[i] != null && labels[i].length() >= 8) {
                    String lbl = labels[i].substring(6, 8);
                    float tw = textPaint.measureText(lbl);
                    canvas.drawText(lbl, left + (right - left - tw) / 2f, h - AndroidUtilities.dp(4), textPaint);
                }
            }
        }
    }
}
EOF