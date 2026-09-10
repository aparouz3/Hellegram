package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Hellegram: replaces the native voice-to-text pipeline.
 * Tapping the transcribe button forwards the voice to a helper bot (@mira)
 * together with a command text; the bot's reply text is captured and shown
 * in the regular transcription slot of the original voice message.
 * Router replies are consumed silently and never reach the UI or notifications.
 */
public class VoiceToTextViaBot {

    public static final boolean ENABLED = true;
    public static final String BOT_USERNAME = "mira";
    public static final String COMMAND_TEXT = "این ویس را به متن تبدیل کن، بدون هیچ حرف اضافه ای. فقط متن اصلی";
    static final String COMMAND_PREFIX = "این ویس را به متن تبدیل کن";
    private static final long TIMEOUT_MS = 30_000;

    public interface VoiceToTextCallback {
        void onResult(String text, boolean finalResult, boolean timedOut);
    }

    private static class PendingRequest {
        final int account;
        final MessageObject voiceMessage;
        final VoiceToTextCallback callback;
        Runnable timeoutRunnable;

        PendingRequest(int account, MessageObject voiceMessage, VoiceToTextCallback callback, Runnable timeoutRunnable) {
            this.account = account;
            this.voiceMessage = voiceMessage;
            this.callback = callback;
            this.timeoutRunnable = timeoutRunnable;
        }
    }

    private static final ArrayList<PendingRequest> pendingRequests = new ArrayList<>();

    public static boolean hasPending() {
        synchronized (pendingRequests) {
            return !pendingRequests.isEmpty();
        }
    }

    public static boolean hasPendingFor(MessageObject voiceMessage) {
        if (voiceMessage == null) {
            return false;
        }
        synchronized (pendingRequests) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                if (pendingRequests.get(i).voiceMessage == voiceMessage) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void sendVoiceForTranscription(MessageObject messageObject, VoiceToTextCallback callback) {
        if (!ENABLED || messageObject == null || messageObject.messageOwner == null || !messageObject.isSent() || callback == null) {
            return;
        }
        final int account = messageObject.currentAccount;
        final PendingRequest request = new PendingRequest(account, messageObject, callback, null);
        request.timeoutRunnable = () -> complete(request, null, true);
        synchronized (pendingRequests) {
            pendingRequests.add(request);
        }
        AndroidUtilities.runOnUIThread(request.timeoutRunnable, TIMEOUT_MS);
        resolveBot(account, botUser -> {
            synchronized (pendingRequests) {
                if (!pendingRequests.contains(request)) {
                    return; // timed out or cancelled while resolving
                }
            }
            if (botUser == null) {
                FileLog.d("VoiceToTextViaBot: failed to resolve @" + BOT_USERNAME);
                complete(request, null, true);
                return;
            }
            SendMessagesHelper sendMessagesHelper = SendMessagesHelper.getInstance(account);
            ArrayList<MessageObject> messages = new ArrayList<>();
            messages.add(messageObject);
            sendMessagesHelper.sendMessage(messages, botUser.id, false, false, false, 0, null, -1, 0);
            sendMessagesHelper.sendMessage(SendMessagesHelper.SendMessageParams.of(COMMAND_TEXT, botUser.id));
            FileLog.d("VoiceToTextViaBot: voice + command sent to @" + BOT_USERNAME);
        });
    }

    private static void resolveBot(int account, Utilities.Callback<TLRPC.User> onResolved) {
        TLRPC.User cached = MessagesController.getInstance(account).getUser(BOT_USERNAME);
        if (cached != null) {
            onResolved.run(cached);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = BOT_USERNAME;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User resolved = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                MessagesStorage.getInstance(account).putUsersAndChats(res.users, res.chats, true, true);
                resolved = MessagesController.getInstance(account).getUser(BOT_USERNAME);
            }
            onResolved.run(resolved);
        }));
    }

    public static long getBotId(int account) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(BOT_USERNAME);
        return user != null ? user.id : 0;
    }

    /**
     * Whether this incoming message is a router reply that must be swallowed.
     * Accepted when sent by the resolved bot and either it echoes the command
     * prefix or it is a direct reply. Runs on any thread (also used to filter
     * the notifications path), so it must not mutate anything.
     */
    public static boolean isRouterMessage(MessageObject messageObject) {
        if (!ENABLED || messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        synchronized (pendingRequests) {
            if (pendingRequests.isEmpty()) {
                return false;
            }
        }
        if (messageObject.isOutOwner() || !messageObject.isFromUser()) {
            return false;
        }
        String text = messageObject.messageOwner.message;
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        long botId = getBotId(messageObject.currentAccount);
        long senderId = messageObject.getSenderId();
        String trimmed = text.trim();
        if (trimmed.startsWith(COMMAND_PREFIX)) {
            return botId == 0 || senderId == botId;
        }
        if (botId != 0 && senderId == botId) {
            // While a transcription is pending, ANY text from the bot is a reply:
            // mira may answer without quoting (no reply header), which previously
            // left the request hanging until timeout. Catch it regardless.
            return true;
        }
        return false;
    }

    /**
     * Deliver a router reply to the oldest pending request on this account.
     * Called from MessagesController.updateInterfaceWithMessages (UI thread).
     *
     * @return true if the message was consumed and must not reach the UI
     */
    public static boolean consumeRouterMessage(MessageObject messageObject) {
        PendingRequest request = null;
        synchronized (pendingRequests) {
            // Prefer the request whose voice message this reply quotes (reply_to_msg_id
            // equals the forwarded voice id in the bot chat); fall back to oldest.
            TLRPC.MessageReplyHeader replyHeader = messageObject.messageOwner.reply_to;
            long repliedId = replyHeader != null ? replyHeader.reply_to_msg_id : 0;
            if (repliedId != 0) {
                for (int i = 0; i < pendingRequests.size(); i++) {
                    PendingRequest r = pendingRequests.get(i);
                    if (r.account == messageObject.currentAccount && r.voiceMessage != null && r.voiceMessage.getId() == repliedId) {
                        request = pendingRequests.remove(i);
                        break;
                    }
                }
            }
            if (request == null) {
                for (int i = 0; i < pendingRequests.size(); i++) {
                    if (pendingRequests.get(i).account == messageObject.currentAccount) {
                        request = pendingRequests.remove(i);
                        break;
                    }
                }
            }
        }
        if (request == null) {
            return false;
        }
        AndroidUtilities.cancelRunOnUIThread(request.timeoutRunnable);
        String text = messageObject.messageOwner.message;
        if (TextUtils.isEmpty(text)) {
            request.callback.onResult(null, false, true);
            return true;
        }
        String result = text.trim();
        if (result.startsWith(COMMAND_PREFIX)) {
            result = result.substring(COMMAND_PREFIX.length()).trim();
            while (!result.isEmpty() && (result.charAt(0) == '،' || result.charAt(0) == ',' || result.charAt(0) == ':' || result.charAt(0) == '-' || result.charAt(0) == '–')) {
                result = result.substring(1).trim();
            }
        }
        FileLog.d("VoiceToTextViaBot: transcription received (" + result.length() + " chars)");
        request.callback.onResult(result, true, false);
        return true;
    }

    public static void cancelPending(MessageObject voiceMessage) {
        synchronized (pendingRequests) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                PendingRequest request = pendingRequests.get(i);
                if (request.voiceMessage == voiceMessage) {
                    pendingRequests.remove(i);
                    AndroidUtilities.cancelRunOnUIThread(request.timeoutRunnable);
                    break;
                }
            }
        }
    }

    private static void complete(PendingRequest request, String text, boolean timedOut) {
        synchronized (pendingRequests) {
            pendingRequests.remove(request);
        }
        AndroidUtilities.cancelRunOnUIThread(request.timeoutRunnable);
        request.callback.onResult(text, !timedOut && text != null, timedOut);
    }
}
