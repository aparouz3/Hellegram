/*
 * Screen Time Tracker for Telegram
 * Tracks per-chat daily screen time (like Digital Wellbeing).
 * Stores elapsed milliseconds per dialog_id per day in SharedPreferences.
 * Supports per-chat time limits with in-app notification when reached.
 * Also tracks hourly distribution (which hour of the day time is spent).
 * Supports per-chat live timer visibility toggle.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ScreenTimeTracker {

    private static ScreenTimeTracker instance;
    private final SharedPreferences prefs;
    private final SharedPreferences limitPrefs;
    private final SharedPreferences timerPrefs;

    private long currentDialogId = 0;
    private long currentStartTime = 0;
    private boolean tracking = false;
    private boolean otherTracking = false;
    private long otherStartTime = 0;

    private final Set<Long> alreadyAlerted = new HashSet<>();

    public interface LimitReachedListener {
        void onLimitReached(long dialogId, long limitMs);
    }
    private LimitReachedListener limitListener;

    private ScreenTimeTracker() {
        prefs = ApplicationLoader.applicationContext.getSharedPreferences("screentime", Context.MODE_PRIVATE);
        limitPrefs = ApplicationLoader.applicationContext.getSharedPreferences("screentime_limits", Context.MODE_PRIVATE);
        timerPrefs = ApplicationLoader.applicationContext.getSharedPreferences("screentime_timer", Context.MODE_PRIVATE);
    }

    public static synchronized ScreenTimeTracker getInstance() {
        if (instance == null) {
            instance = new ScreenTimeTracker();
        }
        return instance;
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private String chatDayKey(long dialogId) {
        return todayKey() + "_" + dialogId;
    }

    private String hourKey(long dialogId, int hour) {
        return todayKey() + "_h" + hour + "_" + dialogId;
    }

    private String hourTotalKey(int hour) {
        return todayKey() + "_hour" + hour;
    }

    public void onChatResumed(long dialogId) {
        // Pause "other" tracking if active (user entered a chat)
        if (otherTracking) {
            flushOther();
            otherTracking = false;
        }
        flushCurrent();
        currentDialogId = dialogId;
        currentStartTime = System.currentTimeMillis();
        tracking = true;
    }

    public void onChatPaused() {
        flushCurrent();
        tracking = false;
        currentDialogId = 0;
        // When chat is paused, user returns to non-chat UI → start "other" tracking
        onOtherResumed();
    }

    // ===== Other (non-chat) screen time tracking =====
    // Called when the app is in foreground but user is NOT in a chat
    // (e.g. dialog list, settings, profile, etc.)

    public void onOtherResumed() {
        // If chat tracking was active, flush it first
        if (tracking) {
            flushCurrent();
            tracking = false;
            currentDialogId = 0;
        }
        if (!otherTracking) {
            otherTracking = true;
            otherStartTime = System.currentTimeMillis();
        }
    }

    public void onOtherPaused() {
        flushOther();
        otherTracking = false;
    }

    private void flushOther() {
        if (otherTracking && otherStartTime > 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - otherStartTime;
            if (elapsed > 0 && elapsed < 86400000L) {
                String key = todayKey() + "_other";
                long existing = prefs.getLong(key, 0);
                prefs.edit().putLong(key, existing + elapsed).apply();
                // Distribute across hours too
                recordHourlyOther(otherStartTime, now, elapsed);
            }
        }
        otherStartTime = System.currentTimeMillis();
    }

    private void recordHourlyOther(long start, long end, long elapsed) {
        SharedPreferences.Editor editor = prefs.edit();
        int startHour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(start)));
        int endHour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(end)));
        if (startHour == endHour) {
            editor.putLong(hourTotalKey(startHour), prefs.getLong(hourTotalKey(startHour), 0) + elapsed);
        } else {
            long remaining = elapsed;
            long cur = start;
            while (remaining > 0 && cur < end) {
                int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(cur)));
                long hourEnd = (cur / 3600000L + 1) * 3600000L;
                long chunk = Math.min(remaining, hourEnd - cur);
                if (chunk > 0) {
                    editor.putLong(hourTotalKey(hour), prefs.getLong(hourTotalKey(hour), 0) + chunk);
                    remaining -= chunk;
                    cur += chunk;
                } else {
                    break;
                }
            }
        }
        editor.apply();
    }

    public long getOtherTimeToday() {
        flushOther();
        if (otherTracking && otherStartTime > 0) {
            return prefs.getLong(todayKey() + "_other", 0) + (System.currentTimeMillis() - otherStartTime);
        }
        return prefs.getLong(todayKey() + "_other", 0);
    }

    private void flushCurrent() {
        if (tracking && currentDialogId != 0 && currentStartTime > 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - currentStartTime;
            if (elapsed > 0 && elapsed < 86400000L) {
                String key = chatDayKey(currentDialogId);
                long existing = prefs.getLong(key, 0);
                // BUG FIX: do NOT cap at limit — keep counting! The limit is just for notification
                long newTotal = existing + elapsed;
                prefs.edit().putLong(key, newTotal).apply();

                // Distribute elapsed time across hours
                recordHourly(currentDialogId, currentStartTime, now, elapsed);

                checkLimit(currentDialogId, newTotal);
            }
        }
        currentStartTime = System.currentTimeMillis();
    }

    /**
     * Get the live elapsed time for the current chat (including un-flushed time).
     */
    public long getLiveChatTime(long dialogId) {
        long base = prefs.getLong(chatDayKey(dialogId), 0);
        if (tracking && currentDialogId == dialogId && currentStartTime > 0) {
            base += (System.currentTimeMillis() - currentStartTime);
        }
        return base;
    }

    /**
     * Record time across hourly buckets. Handles sessions spanning multiple hours.
     */
    private void recordHourly(long dialogId, long start, long end, long elapsed) {
        int startHour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(start)));
        int endHour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(end)));

        SharedPreferences.Editor editor = prefs.edit();
        if (startHour == endHour) {
            editor.putLong(hourKey(dialogId, startHour), prefs.getLong(hourKey(dialogId, startHour), 0) + elapsed);
            editor.putLong(hourTotalKey(startHour), prefs.getLong(hourTotalKey(startHour), 0) + elapsed);
        } else {
            long remaining = elapsed;
            long cur = start;
            while (remaining > 0 && cur < end) {
                int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.US).format(new Date(cur)));
                long hourEnd = (cur / 3600000L + 1) * 3600000L;
                long chunk = Math.min(remaining, hourEnd - cur);
                if (chunk > 0) {
                    editor.putLong(hourKey(dialogId, hour), prefs.getLong(hourKey(dialogId, hour), 0) + chunk);
                    editor.putLong(hourTotalKey(hour), prefs.getLong(hourTotalKey(hour), 0) + chunk);
                    remaining -= chunk;
                    cur += chunk;
                } else {
                    break;
                }
            }
        }
        editor.apply();
    }

    private void checkLimit(long dialogId, long totalMs) {
        long limit = getLimit(dialogId);
        if (limit > 0 && totalMs >= limit && !alreadyAlerted.contains(dialogId)) {
            alreadyAlerted.add(dialogId);
            if (limitListener != null) {
                limitListener.onLimitReached(dialogId, limit);
            }
        }
    }

    public boolean checkLimitLive(long dialogId) {
        long limit = getLimit(dialogId);
        if (limit <= 0) return false;
        long total = getLiveChatTime(dialogId);
        if (total >= limit && !alreadyAlerted.contains(dialogId)) {
            alreadyAlerted.add(dialogId);
            if (limitListener != null) {
                limitListener.onLimitReached(dialogId, limit);
            }
            return true;
        }
        return false;
    }

    // ===== Limit management =====

    public void setLimit(long dialogId, long limitMs) {
        if (limitMs <= 0) {
            limitPrefs.edit().remove(String.valueOf(dialogId)).apply();
        } else {
            limitPrefs.edit().putLong(String.valueOf(dialogId), limitMs).apply();
        }
    }

    public long getLimit(long dialogId) {
        return limitPrefs.getLong(String.valueOf(dialogId), 0);
    }

    public boolean hasLimit(long dialogId) {
        return limitPrefs.getLong(String.valueOf(dialogId), 0) > 0;
    }

    public void removeLimit(long dialogId) {
        limitPrefs.edit().remove(String.valueOf(dialogId)).apply();
    }

    // ===== Timer visibility =====

    public void setTimerVisible(long dialogId, boolean visible) {
        timerPrefs.edit().putBoolean(String.valueOf(dialogId), visible).apply();
    }

    public boolean isTimerVisible(long dialogId) {
        if (!isGlobalTimerEnabled()) return false;
        return timerPrefs.getBoolean(String.valueOf(dialogId), true);
    }

    public boolean toggleTimerVisible(long dialogId) {
        boolean newVal = !timerPrefs.getBoolean(String.valueOf(dialogId), true);
        setTimerVisible(dialogId, newVal);
        return newVal;
    }

    // ===== Global timer toggle =====

    private static final String GLOBAL_TIMER_KEY = "global_timer_enabled";

    public boolean isGlobalTimerEnabled() {
        return timerPrefs.getBoolean(GLOBAL_TIMER_KEY, true);
    }

    public void setGlobalTimerEnabled(boolean enabled) {
        timerPrefs.edit().putBoolean(GLOBAL_TIMER_KEY, enabled).apply();
    }

    public boolean toggleGlobalTimer() {
        boolean newVal = !isGlobalTimerEnabled();
        setGlobalTimerEnabled(newVal);
        return newVal;
    }

    // ===== Listener =====

    public void setLimitReachedListener(LimitReachedListener listener) {
        this.limitListener = listener;
    }

    public void resetAlert(long dialogId) {
        alreadyAlerted.remove(dialogId);
    }

    // ===== Data queries =====

    public long getChatTimeToday(long dialogId) {
        flushCurrent();
        return prefs.getLong(chatDayKey(dialogId), 0);
    }

    public long getTodayTotal() {
        flushCurrent();
        flushOther();
        long total = 0;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(todayKey() + "_") && !key.contains("_hour") && !key.contains("_h")) {
                total += prefs.getLong(key, 0);
            }
        }
        return total;
    }

    public List<long[]> getTodayPerChat() {
        flushCurrent();
        flushOther();
        String prefix = todayKey() + "_";
        List<long[]> list = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix) && !key.contains("_hour") && !key.contains("_h")) {
                try {
                    long did = Long.parseLong(key.substring(prefix.length()));
                    long ms = prefs.getLong(key, 0);
                    if (ms > 1000) {
                        list.add(new long[]{did, ms});
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        list.sort((a, b) -> Long.compare(b[1], a[1]));
        return list;
    }

    /**
     * Returns 24-element array of total screen time per hour (index 0=midnight, 23=11pm).
     */
    public long[] getHourlyDistribution() {
        flushCurrent();
        flushOther();
        long[] hours = new long[24];
        for (int h = 0; h < 24; h++) {
            hours[h] = prefs.getLong(hourTotalKey(h), 0);
        }
        return hours;
    }

    /**
     * For box plot: returns [min, Q1, median, Q3, max] in ms across active hours.
     */
    public long[] getHourlyBoxPlot() {
        long[] hours = getHourlyDistribution();
        List<Long> active = new ArrayList<>();
        for (long h : hours) {
            if (h > 0) active.add(h);
        }
        if (active.isEmpty()) {
            return new long[]{0, 0, 0, 0, 0};
        }
        Long[] arr = active.toArray(new Long[0]);
        Arrays.sort(arr);
        int n = arr.length;
        long min = arr[0];
        long max = arr[n - 1];
        long q1 = arr[n / 4];
        long median = n % 2 == 0 ? (arr[n/2 - 1] + arr[n/2]) / 2 : arr[n / 2];
        long q3 = arr[3 * n / 4];
        return new long[]{min, q1, median, q3, max};
    }

    // LRI/RLI/PFI Unicode marks to isolate Latin/number runs in RTL context,
    // preventing bidi reordering from scrambling "1h 5m" into "h1 5m" etc.
    private static final String LRI = "\u2066";  // LEFT-TO-RIGHT ISOLATE
    private static final String PDI = "\u2069";   // POP DIRECTIONAL ISOLATE

    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        String result;
        if (hours > 0) {
            result = hours + "h " + minutes + "m";
        } else if (minutes > 0) {
            result = minutes + "m " + seconds + "s";
        } else {
            result = seconds + "s";
        }
        // Wrap in LRI/PDI so the entire "1h 5m" stays LTR inside RTL text
        return LRI + result + PDI;
    }

    /**
     * Short format for inline display: "HH:MM:SS" or "MM:SS"
     */
    public static String formatDurationShort(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        String result;
        if (hours > 0) {
            result = String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            result = String.format(Locale.US, "%d:%02d", minutes, seconds);
        }
        return LRI + result + PDI;
    }

    public void resetToday() {
        String prefix = todayKey() + "_";
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new ArrayList<>(prefs.getAll().keySet())) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
        alreadyAlerted.clear();
        otherTracking = false;
        otherStartTime = 0;
    }
}
