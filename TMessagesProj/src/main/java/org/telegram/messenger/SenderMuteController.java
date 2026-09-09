package org.telegram.messenger;

import android.content.SharedPreferences;

import java.util.HashMap;

public class SenderMuteController {

    private static final String KEY_PREFIX = "sender_muted_";

    private static final HashMap<String, Boolean> cache = new HashMap<>();
    private static boolean cacheLoaded;

    private static SharedPreferences preferences() {
        return MessagesController.getNotificationsSettings(UserConfig.selectedAccount);
    }

    public static String key(long dialogId, long senderId) {
        return KEY_PREFIX + dialogId + "_" + senderId;
    }

    public static void setMuted(long dialogId, long senderId, boolean muted) {
        if (dialogId >= 0 || senderId >= 0) {
            return;
        }
        String key = key(dialogId, senderId);
        SharedPreferences.Editor editor = preferences().edit();
        if (muted) {
            editor.putBoolean(key, true);
        } else {
            editor.remove(key);
        }
        editor.apply();
        synchronized (cache) {
            if (muted) {
                cache.put(key, true);
            } else {
                cache.remove(key);
            }
        }
        if (!muted) {
            // unread counts for this dialog may have changed – refresh UI state
            AccountInstance.getInstance(UserConfig.selectedAccount).getNotificationCenter()
                    .postNotificationName(NotificationCenter.notificationsSettingsUpdated);
        }
    }

    public static boolean isMuted(long dialogId, long senderId) {
        if (dialogId >= 0 || senderId >= 0) {
            return false;
        }
        String key = key(dialogId, senderId);
        synchronized (cache) {
            if (!cacheLoaded) {
                loadCacheInternal();
            }
            return cache.containsKey(key);
        }
    }

    private static void loadCacheInternal() {
        SharedPreferences preferences = preferences();
        for (String k : preferences.getAll().keySet()) {
            if (k != null && k.startsWith(KEY_PREFIX) && preferences.getBoolean(k, false)) {
                cache.put(k, true);
            }
        }
        cacheLoaded = true;
    }
}
