/*
 * Forward Sensitivity — per-chat forwarding confirmation.
 * Modes:
 *   Normal  (0) — no confirmation, standard behavior.
 *   High    (1) — when forwarding a message FROM this chat BACK INTO this same chat,
 *                 ask one extra confirmation before sending.
 *   Extreme (2) — every time any message is forwarded TO this chat, ask confirmation.
 * Stored per dialog_id in SharedPreferences.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class ForwardSensitivity {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_HIGH = 1;
    public static final int MODE_EXTREME = 2;

    private static final String PREFS = "forward_sensitivity";

    public static int getMode(long dialogId) {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("fs_" + dialogId, MODE_NORMAL);
    }

    public static void setMode(long dialogId, int mode) {
        ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt("fs_" + dialogId, mode).apply();
    }

    public static String getModeName(long dialogId) {
        return getModeName(getMode(dialogId));
    }

    public static String getModeName(int mode) {
        switch (mode) {
            case MODE_HIGH:
                return "High";
            case MODE_EXTREME:
                return "Extreme";
            default:
                return "Normal";
        }
    }

    /**
     * Returns true when a confirmation dialog must be shown before forwarding
     * {@code messages} to {@code peer} according to that chat's sensitivity mode.
     */
    public static boolean shouldConfirm(long peer, ArrayList<MessageObject> messages) {
        int mode = getMode(peer);
        if (mode == MODE_EXTREME) {
            return true;
        }
        if (mode == MODE_HIGH) {
            for (int a = 0; a < messages.size(); a++) {
                MessageObject msg = messages.get(a);
                if (msg != null && msg.getDialogId() == peer) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Shows the confirmation dialog right before a forward is sent.
     * {@code onConfirm} is invoked (on the UI thread) when the user approves.
     */
    public static void showConfirmDialog(long peer, int count, Runnable onConfirm) {
        final Activity activity = AndroidUtilities.getActivity();
        if (activity == null) {
            onConfirm.run();
            return;
        }
        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        final Theme.ResourcesProvider resourcesProvider = lastFragment != null ? lastFragment.getResourceProvider() : null;
        final String chatName = DialogObject.getShortName(peer);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle("Forwarding");
        if (count == 1) {
            builder.setMessage("Forward 1 message to " + chatName + "?");
        } else {
            builder.setMessage("Forward " + count + " messages to " + chatName + "?");
        }
        builder.setPositiveButton("Forward", (dialogInterface, i) -> onConfirm.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /**
     * Shows the per-chat mode picker (Normal / High / Extreme).
     * Works from any fragment with a parent activity. Tapping a mode applies it immediately.
     */
    public static void showModePickerDialog(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final String chatName = DialogObject.getShortName(dialogId);
        final int currentMode = getMode(dialogId);

        String[] options = {
                getModeName(MODE_NORMAL) + " — no confirmation, standard behavior",
                getModeName(MODE_HIGH) + " — confirm when forwarding from this chat back into itself",
                getModeName(MODE_EXTREME) + " — confirm every forward into this chat"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle("Forwarding sensitivity");
        builder.setSubtitle(chatName + " — current: " + getModeName(currentMode));
        builder.setItems(options, (dialogInterface, which) -> setMode(dialogId, which));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }
}
