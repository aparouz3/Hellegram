package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Hellegram: replaces the native voice-to-text pipeline.
 * Tapping the transcribe button forwards the voice to a helper bot (@mira)
 * together with a command text; the bot's reply text is captured and shown
 * in the regular transcription slot of the original voice message.
 * Router replies are consumed silently and never reach the UI or notifications.
 *
 * Delivery rule: mira streams the transcription by editing one message while
 * its typing indicator is active. We keep capturing the latest text and only
 * deliver it once the typing indicator stops (plus a short settle window), so
 * the user never sees a half-finished transcription.
 */
public class VoiceToTextViaBot {

    public static final boolean ENABLED = true;
    public static final String BOT_USERNAME = "mira";
    public static final String COMMAND_TEXT = "این ویس را به متن تبدیل کن، بدون هیچ حرف اضافه ای. فقط متن اصلی";
    static final String COMMAND_PREFIX = "این ویس را به متن تبدیل کن";
    private static final long TIMEOUT_MS = 120_000;
    // Hellegram: settle windows used to deliver the last captured text.
    private static final long SETTLE_NO_TYPING_MS = 2500;   // text seen, no typing event at all
    private static final long TYPING_ACTIVE_MS = 6000;      // Telegram typing expires ~5s after last event
    private static final long SETTLE_TYPING_END_MS = 700;   // deliver shortly after typing goes stale

    public interface VoiceToTextCallback {
        void onResult(String text, boolean finalResult, boolean timedOut);
    }

    private static class PendingRequest {
        final int account;
        final MessageObject voiceMessage;
        final VoiceToTextCallback callback;
        Runnable timeoutRunnable;
        Runnable pollRunnable;
        Runnable deliverRunnable;
        long botId; // filled when the bot is resolved at send time
        boolean completed;
        // Hellegram: latest bot reply text seen; delivery waits for typing to end.
        String latestText;
        // last time we saw a typing ping from the bot (elapsedRealtime ms; 0 = never)
        long lastTypingTimeMs;

        PendingRequest(int account, MessageObject voiceMessage, VoiceToTextCallback callback) {
            this.account = account;
            this.voiceMessage = voiceMessage;
            this.callback = callback;
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
        final PendingRequest request = new PendingRequest(account, messageObject, callback);
        request.timeoutRunnable = () -> complete(request, request.latestText, true);
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
                Log.i("HellegramVTTB", "VoiceToTextViaBot: failed to resolve @" + BOT_USERNAME);
                complete(request, null, true);
                return;
            }
            request.botId = botUser.id;
            SendMessagesHelper sendMessagesHelper = SendMessagesHelper.getInstance(account);
            ArrayList<MessageObject> messages = new ArrayList<>();
            messages.add(messageObject);
            sendMessagesHelper.sendMessage(messages, botUser.id, false, false, false, 0, null, -1, 0);
            sendMessagesHelper.sendMessage(SendMessagesHelper.SendMessageParams.of(COMMAND_TEXT, botUser.id));
            Log.i("HellegramVTTB", "VoiceToTextViaBot: voice + command sent to @" + BOT_USERNAME + " (id " + botUser.id + ")");
            // Polling fallback: the MessagesController intercept can miss the reply
            // depending on which update path delivers it; the dialogs cache however
            // ALWAYS ends up holding the newest message of the mira dialog.
            final long botDialogId = botUser.id;
            request.pollRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (pendingRequests) {
                        if (!pendingRequests.contains(request) || request.completed) {
                            return;
                        }
                    }
                    ArrayList<MessageObject> cached = MessagesController.getInstance(account).dialogMessage.get(botDialogId);
                    MessageObject top = cached != null && !cached.isEmpty() ? cached.get(0) : null;
                    if (top != null && !top.isOutOwner() && top.getSenderId() == request.botId) {
                        // Hellegram: only capture the text; delivery still waits
                        // for the typing/settle logic so a partial chunk is never
                        // treated as final.
                        consumeRouterMessage(top);
                    }
                    AndroidUtilities.runOnUIThread(this, 1500);
                }
            };
            AndroidUtilities.runOnUIThread(request.pollRunnable, 2500);
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
        final int account = messageObject.currentAccount;
        long botId = getBotId(account);
        synchronized (pendingRequests) {
            if (pendingRequests.isEmpty()) {
                return false;
            }
            for (int i = 0; i < pendingRequests.size(); i++) {
                PendingRequest r = pendingRequests.get(i);
                if (r.account == account && r.botId != 0) {
                    botId = r.botId;
                    break;
                }
            }
        }
        if (botId == 0 || messageObject.isOutOwner() || messageObject.getSenderId() != botId) {
            return false;
        }
        String text = messageObject.messageOwner.message;
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        // any non-outgoing text message from the bot while a request is pending
        // is treated as the transcription reply
        return true;
    }

    private static String extractRouterText(String raw) {
        if (raw == null) {
            return null;
        }
        String result = raw.trim();
        if (result.startsWith(COMMAND_PREFIX)) {
            result = result.substring(COMMAND_PREFIX.length()).trim();
            while (!result.isEmpty() && (result.charAt(0) == '،' || result.charAt(0) == ',' || result.charAt(0) == ':' || result.charAt(0) == '-' || result.charAt(0) == '–')) {
                result = result.substring(1).trim();
            }
        }
        return result;
    }

    /**
     * Called from MessagesController.updateInterfaceWithMessages when a router
     * reply is intercepted. Captures the latest text and (re)schedules delivery.
     *
     * @return true if the message was consumed and must not reach the UI
     */
    public static boolean consumeRouterMessage(MessageObject messageObject) {
        final PendingRequest request;
        final String text;
        synchronized (pendingRequests) {
            if (!ENABLED || messageObject == null || messageObject.messageOwner == null || pendingRequests.isEmpty()) {
                return false;
            }
            request = findRequestFor(messageObject.currentAccount, messageObject);
            if (request == null || request.completed) {
                return false;
            }
            text = isRouterMessage(messageObject) ? extractRouterText(messageObject.messageOwner.message) : null;
            if (text == null) {
                return false;
            }
            boolean unchanged = text.equals(request.latestText);
            if (!unchanged) {
                request.latestText = text;
            } else if (request.deliverRunnable != null) {
                // polling fallback re-capturing the same text: keep the already
                // scheduled settle, otherwise the timer would reset forever
                return true;
            }
        }
        if (text == null) {
            return false;
        }
        Log.i("HellegramVTTB", "VoiceToTextViaBot: chunk captured (" + text.length() + " chars), waiting for typing to end");
        scheduleSettleDelivery(request);
        return true;
    }

    /**
     * Hellegram: called from the typing-update handling in MessagesController.
     * typing=true for any non-cancel action from the bot, false for
     * TL_sendMessageCancelAction (explicit end of typing).
     * Only acts when an active request targets that bot.
     */
    public static void onRouterTyping(int account, long userId, boolean typing) {
        if (!ENABLED || userId == 0) {
            return;
        }
        PendingRequest request = null;
        synchronized (pendingRequests) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                PendingRequest r = pendingRequests.get(i);
                if (r.account == account && !r.completed && r.botId == userId) {
                    request = r;
                    break;
                }
            }
            if (request == null) {
                return;
            }
            if (typing) {
                request.lastTypingTimeMs = SystemClock.elapsedRealtime();
                return;
            }
            // explicit cancel: the bot is done typing
            request.lastTypingTimeMs = 0;
        }
        scheduleSettleDelivery(request, SETTLE_TYPING_END_MS);
    }

    private static PendingRequest findRequestFor(int account, MessageObject messageObject) {
        // Prefer the request whose voice message this reply quotes; fall back
        // to the oldest pending request of the same account.
        TLRPC.MessageReplyHeader replyHeader = messageObject.messageOwner.reply_to;
        long repliedId = replyHeader != null ? replyHeader.reply_to_msg_id : 0;
        if (repliedId != 0) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                PendingRequest r = pendingRequests.get(i);
                if (r.account == account && r.voiceMessage != null && r.voiceMessage.getId() == repliedId) {
                    return r;
                }
            }
        }
        for (int i = 0; i < pendingRequests.size(); i++) {
            PendingRequest r = pendingRequests.get(i);
            if (r.account == account && !r.completed) {
                return r;
            }
        }
        return null;
    }

    private static void scheduleSettleDelivery(PendingRequest request) {
        scheduleSettleDelivery(request, 0);
    }

    private static void scheduleSettleDelivery(PendingRequest request, long minDelay) {
        long delay;
        Runnable prev;
        synchronized (pendingRequests) {
            if (request.completed) {
                return;
            }
            long typingAge = SystemClock.elapsedRealtime() - request.lastTypingTimeMs;
            boolean typingFresh = request.lastTypingTimeMs != 0 && typingAge < TYPING_ACTIVE_MS;
            if (typingFresh) {
                // still typing: check again after the typing window expires
                delay = TYPING_ACTIVE_MS - typingAge + SETTLE_TYPING_END_MS;
            } else if (request.lastTypingTimeMs != 0 || minDelay > 0) {
                delay = SETTLE_TYPING_END_MS;
            } else {
                delay = SETTLE_NO_TYPING_MS;
            }
            if (minDelay > delay) {
                delay = minDelay;
            }
            prev = request.deliverRunnable;
            request.deliverRunnable = () -> {
                synchronized (pendingRequests) {
                    if (request.completed) {
                        return;
                    }
                    long recheckAge = SystemClock.elapsedRealtime() - request.lastTypingTimeMs;
                    if (request.lastTypingTimeMs != 0 && recheckAge < TYPING_ACTIVE_MS) {
                        // typing resumed since scheduling: wait again
                        scheduleSettleDelivery(request);
                        return;
                    }
                    if (request.latestText == null) {
                        return; // nothing captured yet; hard timeout will fire
                    }
                }
                complete(request, request.latestText, false);
            };
        }
        if (prev != null) {
            AndroidUtilities.cancelRunOnUIThread(prev);
        }
        AndroidUtilities.runOnUIThread(request.deliverRunnable, delay);
    }

    public static void cancelPending(MessageObject voiceMessage) {
        synchronized (pendingRequests) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                PendingRequest request = pendingRequests.get(i);
                if (request.voiceMessage == voiceMessage) {
                    pendingRequests.remove(i);
                    AndroidUtilities.cancelRunOnUIThread(request.timeoutRunnable);
                    AndroidUtilities.cancelRunOnUIThread(request.pollRunnable);
                    AndroidUtilities.cancelRunOnUIThread(request.deliverRunnable);
                    break;
                }
            }
        }
    }

    private static void complete(PendingRequest request, String text, boolean timedOut) {
        synchronized (pendingRequests) {
            if (request.completed) {
                return;
            }
            request.completed = true;
            pendingRequests.remove(request);
        }
        AndroidUtilities.cancelRunOnUIThread(request.timeoutRunnable);
        AndroidUtilities.cancelRunOnUIThread(request.pollRunnable);
        AndroidUtilities.cancelRunOnUIThread(request.deliverRunnable);
        Log.i("HellegramVTTB", "VoiceToTextViaBot: delivering " + (text == null ? 0 : text.length()) + " chars (timedOut=" + timedOut + ")");
        request.callback.onResult(text, !timedOut && text != null, timedOut);
    }
}
