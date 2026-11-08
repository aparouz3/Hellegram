package org.telegram.ui;

import android.content.Context;
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
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

public class ScreenTimeHistoryActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private List<String> days = new ArrayList<>();
    private String selectedDay;

    public ScreenTimeHistoryActivity(Bundle args) { super(args); }

    public static ScreenTimeHistoryActivity of() {
        return new ScreenTimeHistoryActivity(new Bundle());
    }

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

        fragmentView = new RecyclerListView(context);
        listView = (RecyclerListView) fragmentView;
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        refreshData();
        return fragmentView;
    }

    private void refreshData() {
        days = ScreenTimeTracker.getInstance().getDaysWithData();
        if (selectedDay == null && !days.isEmpty()) selectedDay = days.get(0);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        return list;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        public ListAdapter(Context ctx) { context = ctx; }

        @Override
        public int getItemCount() { return Math.max(1, days.size()); }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = new TextCell(context, getResourceProvider());
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (days.isEmpty()) {
                ((TextCell) holder.itemView).setText("No history yet", false);
                return;
            }
            String day = days.get(position);
            long total = ScreenTimeTracker.getInstance().getTotalForDay(day);
            ((TextCell) holder.itemView).setTextAndValue(formatDay(day), ScreenTimeTracker.formatDuration(total), true);
        }
    }

    private String formatDay(String d) {
        // d = yyyyMMdd
        try {
            return d.substring(6,8) + "/" + d.substring(4,6) + "/" + d.substring(0,4);
        } catch (Exception e) { return d; }
    }
}
