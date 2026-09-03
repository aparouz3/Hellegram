package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * === ANTI_DELETE === (persistent store)
 * Keeps the set of messages that were deleted by their sender but are held visible
 * by the anti-delete feature. Persisted to SharedPreferences so the "This message was
 * deleted by the sender" label survives closing/reopening the chat (and app restarts),
 * because MessageObject.antiDeleteHeld is a transient runtime flag that resets whenever
 * message objects are rebuilt from the database.
 */
public class AntiDeleteStore {

    private static final String PREF_NAME = "antidelete_store";
    private static final String KEY_HELD = "held";

    private static volatile SharedPreferences prefs;
    private static volatile HashSet<String> held;

    private static synchronized void ensureInit() {
        if (prefs == null) {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            }
        }
        if (held == null) {
            held = new HashSet<>();
            if (prefs != null) {
                Set<String> stored = prefs.getStringSet(KEY_HELD, null);
                if (stored != null) {
                    held.addAll(stored);
                }
            }
        }
    }

    /** Warm up the store early (app start) so reads on the render/UI thread are cheap. */
    public static synchronized void init() {
        ensureInit();
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + ":" + messageId;
    }

    /** Mark a message as held by anti-delete so the label persists across reloads. */
    public static synchronized void markHeld(long dialogId, int messageId) {
        ensureInit();
        if (held == null || prefs == null) {
            return;
        }
        if (held.add(key(dialogId, messageId))) {
            prefs.edit().putStringSet(KEY_HELD, held).apply();
        }
    }

    /**
     * Whether this message is held by anti-delete (persisted).
     * Fast, lock-free read for the render hotspot; assumes init() ran at app start
     * (held is volatile and populated in ApplicationLoader.onCreate before any render).
     */
    public static boolean isHeld(long dialogId, int messageId) {
        HashSet<String> h = held;
        if (h == null) {
            return false;
        }
        return h.contains(key(dialogId, messageId));
    }

    /** Drop all held messages for a dialog (e.g. when its history is cleared). */
    public static synchronized void clearDialog(long dialogId) {
        ensureInit();
        if (held == null || prefs == null) {
            return;
        }
        String prefix = dialogId + ":";
        ArrayList<String> toRemove = null;
        for (String k : held) {
            if (k.startsWith(prefix)) {
                if (toRemove == null) {
                    toRemove = new ArrayList<>();
                }
                toRemove.add(k);
            }
        }
        if (toRemove != null) {
            held.removeAll(toRemove);
            prefs.edit().putStringSet(KEY_HELD, held).apply();
        }
    }
}
