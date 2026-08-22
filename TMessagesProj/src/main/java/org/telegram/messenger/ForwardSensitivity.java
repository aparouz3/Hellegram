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

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class ForwardSensitivity {

    public static final int MODE_NORMAL = 0;
    public static final int MODE_HIGH = 1;
    public static final int MODE_EXTREME = 2;
    public static final int MODE_HIGH_EXTREME = 3; // author-aware confirmation

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
            case MODE_HIGH_EXTREME:
                return "High Extreme";
            default:
                return "Normal";
        }
    }

    /**
     * Returns true when a confirmation dialog must be shown before forwarding
     * {@code messages} to {@code peer} according to that chat's sensitivity mode.
     */
    public static boolean shouldConfirm(long peer, ArrayList<MessageObject> messages) {
        return shouldConfirm(UserConfig.selectedAccount, peer, messages);
    }

    public static boolean shouldConfirm(int currentAccount, long peer, ArrayList<MessageObject> messages) {
        int mode = getMode(peer);
        if (mode == MODE_EXTREME) {
            return true;
        }
        for (int a = 0; a < messages.size(); a++) {
            MessageObject msg = messages.get(a);
            if (msg == null) {
                continue;
            }
            if (mode == MODE_HIGH) {
                if (msg.getDialogId() == peer) {
                    return true;
                }
            } else if (mode == MODE_HIGH_EXTREME) {
                long authorId = getOriginalAuthorId(msg);
                if (authorId == 0) {
                    continue;
                }
                long myId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (authorId == myId) {
                    continue; // own messages need no confirmation
                }
                if (peer == authorId) {
                    return true; // forwarded to the author's PM
                }
                if (DialogObject.isChatDialog(peer) && isUserInChat(currentAccount, authorId, -peer)) {
                    return true; // forwarded to a group where the author is present
                }
            }
        }
        return false;
    }

    /** The original author (user) of the message, or 0 if it's a channel post / anonymous / unknown. */
    private static long getOriginalAuthorId(MessageObject msg) {
        try {
            TLRPC.Message owner = msg.messageOwner;
            if (owner == null) {
                return 0;
            }
            if (owner.fwd_from != null) {
                // Forwarded message: the original author is the user who created it
                if (owner.fwd_from.from_id != null) {
                    if (owner.fwd_from.from_id.user_id != 0) {
                        return owner.fwd_from.from_id.user_id;
                    }
                    return 0; // forwarded from a channel — belongs to the channel, not a user
                }
                if (owner.fwd_from.from_name != null) {
                    return 0; // anonymous forward — no user to protect
                }
            }
            if (owner.from_id != null && owner.from_id.user_id != 0) {
                return owner.from_id.user_id;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    /** Checks cached chat participants (basic groups + megagroups) for the user. */
    private static boolean isUserInChat(int currentAccount, long userId, long chatId) {
        try {
            TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(chatId);
            if (chatFull != null && chatFull.participants != null && chatFull.participants.participants != null) {
                for (int i = 0; i < chatFull.participants.participants.size(); i++) {
                    TLRPC.ChatParticipant participant = chatFull.participants.participants.get(i);
                    if (participant != null && participant.user_id == userId) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
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
        showModePickerDialog(fragment, dialogId, null);
    }

    public static void showModePickerDialog(BaseFragment fragment, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final String chatName = DialogObject.getShortName(dialogId);
        final int currentMode = getMode(dialogId);

        String[] options = {
                getModeName(MODE_NORMAL) + " — no confirmation, standard behavior",
                getModeName(MODE_HIGH) + " — confirm when forwarding from this chat back into itself",
                getModeName(MODE_EXTREME) + " — confirm every forward into this chat",
                getModeName(MODE_HIGH_EXTREME) + " — confirm when a message is forwarded to its author's PM or a group the author is in"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle("Forwarding sensitivity");
        builder.setSubtitle(chatName + " — current: " + getModeName(currentMode));
        builder.setItems(options, (dialogInterface, which) -> {
            setMode(dialogId, which);
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }
}
