/*
 * Screen Time Breakdown — per-chat screen time for a single period ("today" or "week"),
 * opened by tapping "Today's Total" / "This Week's Total" on the ScreenTime screen.
 * Mirrors the PillTracker "بیشترین تراکنش‌ها" preview pattern: shows the top N chats by
 * default with a "نمایش بیشتر / نمایش کمتر" toggle to reveal the full list.
 * Each chat row is tappable to set a per-chat time limit.
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.ScreenTimeTracker;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

public class ScreenTimeBreakdownActivity extends BaseFragment {

    public static final String ARG_PERIOD = "period"; // "today" | "week"
    private static final long OTHER_DIALOG_ID = Long.MIN_VALUE + 1;
    private static final int PREVIEW_LIMIT = 3;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private String period = "today";
    private boolean weekly;
    private List<long[]> chatList = new ArrayList<>(); // [dialogId, ms]
    private long maxTime = 1;
    private boolean expanded = false;

    private final Runnable updateTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (listView != null && !weekly) {
                int count = listView.getChildCount();
                for (int i = 0; i < count; i++) {
                    View child = listView.getChildAt(i);
                    if (child instanceof ChatRow) {
                        ((ChatRow) child).updateLiveTime();
                    }
                }
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public ScreenTimeBreakdownActivity(Bundle args) {
        super(args);
    }

    public static ScreenTimeBreakdownActivity of(String period) {
        Bundle b = new Bundle();
        b.putString(ARG_PERIOD, period);
        return new ScreenTimeBreakdownActivity(b);
    }

    @Override
    public View createView(Context context) {
        if (getArguments() != null) {
            period = getArguments().getString(ARG_PERIOD, "today");
        }
        weekly = "week".equals(period);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(weekly ? "This Week by Chat" : "Today by Chat");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        fragmentView = new RecyclerListView(context);
        listView = (RecyclerListView) fragmentView;
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        listView.setOnItemClickListener((view, position) -> {
            int type = adapter.getItemViewType(position);
            if (type == 2) { // chat row
                int idx = adapter.getChatIndex(position);
                if (idx >= 0 && idx < chatList.size()) {
                    showLimitDialog(chatList.get(idx)[0]);
                }
            } else if (type == 4) { // show more / less
                expanded = !expanded;
                adapter.notifyDataSetChanged();
            }
        });

        refreshData();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshData();
        AndroidUtilities.runOnUIThread(updateTimerRunnable, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(updateTimerRunnable);
    }

    private void refreshData() {
        ScreenTimeTracker tracker = ScreenTimeTracker.getInstance();
        List<long[]> src = weekly ? tracker.getWeekPerChat() : tracker.getTodayPerChat();
        chatList = src == null ? new ArrayList<>() : new ArrayList<>(src);
        if (!weekly) {
            long otherMs = tracker.getOtherTimeToday();
            if (otherMs > 1000) {
                chatList.add(new long[]{OTHER_DIALOG_ID, otherMs});
                chatList.sort((a, b) -> Long.compare(b[1], a[1]));
            }
        } else {
            chatList.sort((a, b) -> Long.compare(b[1], a[1]));
        }
        maxTime = 1;
        for (long[] e : chatList) {
            if (e[1] > maxTime) maxTime = e[1];
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private TLRPC.User getUser(long dialogId) {
        try {
            return getMessagesController().getUser(dialogId);
        } catch (Exception e) {
            return null;
        }
    }

    private TLRPC.Chat getChat(long dialogId) {
        try {
            return getMessagesController().getChat(-dialogId);
        } catch (Exception e) {
            return null;
        }
    }

    private String getChatName(long dialogId) {
        if (dialogId == OTHER_DIALOG_ID) return "Other";
        TLRPC.User user = getUser(dialogId);
        if (user != null) return UserObject.getUserName(user);
        TLRPC.Chat chat = getChat(dialogId);
        if (chat != null) return chat.title;
        return "Unknown";
    }

    private void showLimitDialog(long dialogId) {
        String chatName = getChatName(dialogId);
        ScreenTimeTracker tracker = ScreenTimeTracker.getInstance();
        long currentLimit = tracker.getLimit(dialogId);

        String[] options = {
                "Remove limit", "5 minutes", "15 minutes", "30 minutes", "1 hour", "2 hours", "Custom..."
        };
        long[] optionValues = {0, 5 * 60 * 1000L, 15 * 60 * 1000L, 30 * 60 * 1000L, 60 * 60 * 1000L, 2 * 60 * 60 * 1000L, -1};

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Time limit for " + chatName);
        builder.setItems(options, (dialog, which) -> {
            if (which == 6) {
                showCustomLimitDialog(dialogId, chatName);
            } else {
                applyLimit(dialogId, chatName, optionValues[which], options[which]);
            }
        });
        builder.setNegativeButton("Cancel", null);

        boolean timerVisible = tracker.isTimerVisible(dialogId);
        builder.setPositiveButton(timerVisible ? "Hide timer" : "Show timer", (d, w) -> {
            tracker.toggleTimerVisible(dialogId);
            refreshData();
        });
        builder.setNeutralButton("Forwarding sensitivity: " + org.telegram.messenger.ForwardSensitivity.getModeName(dialogId), (d, w) -> {
            d.dismiss();
            org.telegram.messenger.ForwardSensitivity.showModePickerDialog(ScreenTimeBreakdownActivity.this, dialogId);
            refreshData();
        });

        builder.create().show();
    }

    private void showCustomLimitDialog(long dialogId, String chatName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle("Custom limit for " + chatName);

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        TextView label = new TextView(getParentActivity());
        label.setText("Enter minutes:");
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        container.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        EditText input = new EditText(getParentActivity());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("e.g. 45");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setBackgroundDrawable(Theme.createEditTextDrawable(getParentActivity(), true));
        container.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 8, 0, 0));

        builder.setView(container);
        builder.setPositiveButton("Set", (d, w) -> {
            String text = input.getText().toString().trim();
            try {
                int minutes = Integer.parseInt(text);
                if (minutes > 0) {
                    applyLimit(dialogId, chatName, minutes * 60 * 1000L, minutes + " minutes");
                }
            } catch (NumberFormatException ignored) {
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.create().show();
        input.requestFocus();
    }

    private void applyLimit(long dialogId, String chatName, long limitMs, String label) {
        ScreenTimeTracker tracker = ScreenTimeTracker.getInstance();
        tracker.setLimit(dialogId, limitMs);
        tracker.resetAlert(dialogId);
        refreshData();
        Bulletin.SimpleLayout layout = new Bulletin.SimpleLayout(getContext(), getResourceProvider());
        layout.imageView.setImageResource(R.drawable.msg_check_s);
        if (limitMs == 0) {
            layout.textView.setText("Time limit removed for " + chatName);
        } else {
            layout.textView.setText("Limit set: " + label + " for " + chatName);
        }
        Bulletin.make(this, layout, 3000).show();
    }

    // =================== Adapter ===================

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public ListAdapter(Context ctx) {
            context = ctx;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = getItemViewType(holder.getAdapterPosition());
            return type == 2 || type == 4;
        }

        private int shownCount() {
            int n = chatList.size();
            return expanded ? n : Math.min(n, PREVIEW_LIMIT);
        }

        @Override
        public int getItemViewType(int position) {
            if (chatList.isEmpty()) {
                // header + empty
                return position == 0 ? 0 : 3;
            }
            int shown = shownCount();
            if (position == 0) return 0;                 // header
            if (position <= shown) return 2;             // chat row
            if (position == shown + 1) return 4;         // show more / less
            return 1;                                    // footer info
        }

        @Override
        public int getItemCount() {
            if (chatList.isEmpty()) {
                return 2; // header + empty
            }
            int shown = shownCount();
            boolean hasMore = chatList.size() > PREVIEW_LIMIT;
            return 1 + shown + (hasMore ? 1 : 0); // header + rows + toggle
        }

        public int getChatIndex(int position) {
            return position - 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new HeaderCell(context, getResourceProvider());
                    break;
                case 2:
                    view = new ChatRow(context);
                    break;
                case 4:
                    view = new TextCell(context, getResourceProvider());
                    break;
                case 3:
                    view = new TextCell(context, getResourceProvider());
                    break;
                case 1:
                default:
                    view = new TextInfoPrivacyCell(context);
            }
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == 0) {
                ((HeaderCell) holder.itemView).setText(weekly ? "This Week by Chat" : "Today by Chat");
            } else if (type == 2) {
                int idx = position - 1;
                if (idx >= 0 && idx < chatList.size()) {
                    long[] e = chatList.get(idx);
                    ((ChatRow) holder.itemView).setData(weekly, idx + 1, e[0], getChatName(e[0]), e[1], maxTime);
                }
            } else if (type == 4) {
                ((TextCell) holder.itemView).setText(expanded ? "\u0627\u0646\u0645\u0627\u06cc\u0634 \u06a9\u0645\u062a\u0631" : "\u0627\u0646\u0645\u0627\u06cc\u0634 \u0628\u06cc\u0634\u062a\u0631", false);
            } else if (type == 3) {
                ((TextCell) holder.itemView).setText("No chats tracked yet", false);
            } else if (type == 1) {
                ((TextInfoPrivacyCell) holder.itemView).setText(weekly
                        ? "Weekly screen time per chat for the last 7 days. Tap a chat to set a limit."
                        : "Today's screen time per chat. Tap a chat to set a limit.");
            }
        }
    }

    // =================== Chat row ===================

    private class ChatRow extends FrameLayout {
        private final BackupImageView avatarImageView;
        private final AvatarDrawable avatarDrawable;
        private final TextView rankText;
        private final TextView nameText;
        private final TextView valueText;
        private final View progressView;
        private final TextView limitText;
        private long dialogId;
        private long ms;
        private long maxMs;
        private boolean weekly;

        public ChatRow(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            setBackground(Theme.getSelectorDrawable(Theme.getColor(Theme.key_listSelector), false));

            avatarDrawable = new AvatarDrawable();
            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(AndroidUtilities.dp(20));
            addView(avatarImageView, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

            rankText = new TextView(context);
            rankText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rankText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            rankText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            addView(rankText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 48, 24, 0, 0));

            nameText = new TextView(context);
            nameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameText.setMaxLines(1);
            nameText.setEllipsize(TextUtils.TruncateAt.END);
            nameText.setTextDirection(View.TEXT_DIRECTION_LTR);
            nameText.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            addView(nameText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 70, 2, 0, 0));

            valueText = new TextView(context);
            valueText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            valueText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            valueText.setGravity(Gravity.RIGHT);
            addView(valueText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 4, 0, 0));

            progressView = new View(context) {
                @Override
                public void setScaleX(float scale) {
                    setPivotX(0);
                    super.setScaleX(scale);
                }
            };
            progressView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.TOP | Gravity.LEFT, 70, 24, 0, 0));

            limitText = new TextView(context);
            limitText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            limitText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            limitText.setVisibility(View.GONE);
            addView(limitText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 70, 30, 0, 0));
        }

        public void setData(boolean weekly, int rank, long dialogId, String name, long ms, long maxMs) {
            this.weekly = weekly;
            this.dialogId = dialogId;
            this.ms = ms;
            this.maxMs = maxMs;

            rankText.setText(rank + ".");
            nameText.setText(name);
            valueText.setText(ScreenTimeTracker.formatDuration(ms));

            if (dialogId == OTHER_DIALOG_ID) {
                avatarImageView.setVisibility(GONE);
                limitText.setVisibility(GONE);
                float ratio = maxMs > 0 ? (float) ms / maxMs : 0;
                progressView.setScaleX(Math.max(0.02f, ratio));
            } else {
                avatarImageView.setVisibility(VISIBLE);
                TLRPC.User user = getUser(dialogId);
                TLRPC.Chat chat = getChat(dialogId);
                if (user != null) {
                    avatarDrawable.setInfo(getCurrentAccount(), user);
                    avatarImageView.setForUserOrChat(user, avatarDrawable);
                } else if (chat != null) {
                    avatarDrawable.setInfo(getCurrentAccount(), chat);
                    avatarImageView.setForUserOrChat(chat, avatarDrawable);
                }
                float ratio = maxMs > 0 ? (float) ms / maxMs : 0;
                progressView.setScaleX(Math.max(0.02f, ratio));

                long limit = ScreenTimeTracker.getInstance().getLimit(dialogId);
                if (limit > 0 && !weekly) {
                    limitText.setVisibility(VISIBLE);
                    String limitStr = "Limit: " + ScreenTimeTracker.formatDuration(limit);
                    if (ms >= limit) {
                        limitText.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                        limitStr += " — Reached!";
                    } else {
                        limitText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                        long remaining = limit - ms;
                        limitStr += " (" + ScreenTimeTracker.formatDuration(remaining) + " left)";
                    }
                    limitText.setText(limitStr);
                } else {
                    limitText.setVisibility(GONE);
                }
            }
        }

        public void updateLiveTime() {
            if (weekly) return;
            if (dialogId == 0) return;
            if (dialogId == OTHER_DIALOG_ID) {
                valueText.setText(ScreenTimeTracker.formatDuration(ScreenTimeTracker.getInstance().getOtherTimeToday()));
                return;
            }
            if (ScreenTimeTracker.getInstance().isTimerVisible(dialogId)) {
                valueText.setText(ScreenTimeTracker.formatDuration(ScreenTimeTracker.getInstance().getLiveChatTime(dialogId)));
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        return list;
    }
}
