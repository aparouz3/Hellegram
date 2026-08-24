/*
 /* Forward Sensitivity — per-chat forwarding confirmation.
  * Modes are CUMULATIVE — each mode includes all features of the modes below it:
  *   Normal       (0) — no confirmation, standard behavior.
  *   High         (1) — forwarding FROM this chat back INTO this same chat asks.
  *   Extreme      (2) — High + every forward INTO this chat asks.
  *   HighExtreme  (3) — Extreme + author-aware: confirms when a message is forwarded
  *                      to its author's PM or into any group the author is a member of
  *                      (mode may be on destination, source, or the author's own chat).
  *   UltraExtreme (4) — HighExtreme + every forward OUT of this chat asks,
  *                      regardless of destination.
  * Stored per dialog_id in SharedPreferences.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;

import org.telegram.tgnet.ConnectionsManager;
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
    public static final int MODE_ULTRA_EXTREME = 4; // every forward OUT of this chat

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
            case MODE_ULTRA_EXTREME:
                return "Ultra Extreme";
            default:
                return "Normal";
        }
    }

    /**
     * Checks whether forwarding {@code messages} to {@code peer} needs a confirmation
     * according to that chat's sensitivity mode.
     *
     * @return true if the decision is made (or async check in flight) — the callback has been
     *         or will be invoked, and the caller must stop the normal send flow.
     *         false if no confirmation is needed — the caller continues sending normally.
     *
     * The callback is always invoked (possibly later, from the UI thread) with:
     *   true  — confirmation dialog was shown and the user approved (or will approve); the caller
     *           should re-enter the send flow with sensitivityConfirmed=true.
     *   false — no confirmation needed; the caller should re-enter the send flow normally.
     */
    public static boolean checkForwardSensitivity(int currentAccount, long peer, ArrayList<MessageObject> messages, Utilities.Callback<Boolean> onResult) {
        final long myId = UserConfig.getInstance(currentAccount).getClientUserId();

        boolean needConfirm = false;
        for (int a = 0; a < messages.size() && !needConfirm; a++) {
            MessageObject msg = messages.get(a);
            if (msg == null) {
                continue;
            }
            final long srcDialog = msg.getDialogId();
            int destMode = getMode(peer);
            int srcMode = getMode(srcDialog);

            // Modes are CUMULATIVE — a higher mode includes all features of lower modes.

            // Ultra Extreme (4) on the SOURCE: every forward OUT of this chat asks,
            // regardless of destination. (Also covers Extreme+High for that chat.)
            if (srcMode >= MODE_ULTRA_EXTREME) {
                needConfirm = true;
                break;
            }

            // Extreme (2+) on the DESTINATION: every forward INTO this chat asks.
            // (High Extreme and Ultra Extreme also include this.)
            if (destMode >= MODE_EXTREME) {
                needConfirm = true;
                break;
            }

            // High (1+): forwarding from a chat back INTO that same chat asks.
            if (srcDialog == peer && (destMode >= MODE_HIGH || srcMode >= MODE_HIGH)) {
                needConfirm = true;
                break;
            }

            // High Extreme (3+) — author-aware: mode set on the destination chat,
            // the source chat, OR directly on the author's own chat (PM).
            long authorId = getOriginalAuthorId(srcDialog, msg);
            if (authorId == 0 || authorId == myId) {
                continue;
            }
            int authorMode = getMode(authorId);
            if (destMode < MODE_HIGH_EXTREME && srcMode < MODE_HIGH_EXTREME && authorMode < MODE_HIGH_EXTREME) {
                continue;
            }
            // Forwarding to the author's PM?
            if (peer == authorId) {
                needConfirm = true;
                break;
            }
            // Forwarding into a group where the author is a member?
            if (DialogObject.isChatDialog(peer)) {
                final long chatId = -peer;
                if (isUserInChatCached(currentAccount, authorId, chatId)) {
                    needConfirm = true;
                    break;
                }
                // Cached participants are unreliable/incomplete — ask the server.
                // onResult(true) after user approves the dialog, onResult(false) if author not in chat.
                checkCommonChats(currentAccount, authorId, chatId, peer, messages.size(), onResult);
                return true; // async server check in flight — caller must stop the send flow
            }
        }
        if (needConfirm) {
            showConfirmDialog(peer, messages.size(), () -> onResult.run(true));
            return true;
        }
        return false;
    }

    /** The protected author of a message being forwarded from {@code srcDialog}.
     *  - From a PM (user dialog): the sender is the counterpart we protect,
     *    even if the message itself was forwarded from a channel/post.
     *  - From a group/channel: the original author (fwd_from) when it's a user,
     *    otherwise the sender; 0 if unknown (channel post / anonymous). */
    private static long getOriginalAuthorId(long srcDialog, MessageObject msg) {
        try {
            TLRPC.Message owner = msg.messageOwner;
            if (owner == null) {
                return 0;
            }
            if (srcDialog > 0) {
                // PM: protect the peer we're chatting with (the sender),
                // regardless of fwd_from — X's forwarded channel post is still "from X".
                if (owner.from_id != null && owner.from_id.user_id != 0) {
                    return owner.from_id.user_id;
                }
                if (owner.peer_id != null && owner.peer_id.user_id != 0) {
                    return owner.peer_id.user_id;
                }
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
    private static boolean isUserInChatCached(int currentAccount, long userId, long chatId) {
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

    /** Asks the server for the chats shared between me and the user; if the destination chat is among them, the user is in it. */
    private static void checkCommonChats(int currentAccount, long userId, long chatId, long peer, int count, Utilities.Callback<Boolean> onResult) {
        try {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
            if (user == null) {
                onResult.run(false);
                return;
            }
            TLRPC.InputUser inputUser = MessagesController.getInstance(currentAccount).getInputUser(user);
            if (inputUser == null) {
                onResult.run(false);
                return;
            }
            TLRPC.TL_messages_getCommonChats req = new TLRPC.TL_messages_getCommonChats();
            req.user_id = inputUser;
            req.max_id = 0;
            req.limit = 100;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                boolean inChat = false;
                if (response instanceof TLRPC.TL_messages_chats) {
                    TLRPC.TL_messages_chats chats = (TLRPC.TL_messages_chats) response;
                    for (int i = 0; i < chats.chats.size(); i++) {
                        if (chats.chats.get(i) != null && chats.chats.get(i).id == chatId) {
                            inChat = true;
                            break;
                        }
                    }
                }
                if (inChat) {
                    showConfirmDialog(peer, count, () -> onResult.run(true));
                } else {
                    onResult.run(false);
                }
            }));
        } catch (Exception e) {
            FileLog.e(e);
            onResult.run(false);
        }
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
                getModeName(MODE_EXTREME) + " — High + confirm every forward into this chat",
                getModeName(MODE_HIGH_EXTREME) + " — Extreme + confirm when a message is forwarded to its author's PM or a group the author is in",
                getModeName(MODE_ULTRA_EXTREME) + " — High Extreme + confirm every forward out of this chat"
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
