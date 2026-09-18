"""Source-level regression guards; not an Android runtime test."""
from pathlib import Path
import unittest
root=Path(__file__).resolve().parents[1]/'TMessagesProj/src/main/java/org/telegram'
class Contract(unittest.TestCase):
 def test_notification_text_does_not_require_remote_id(self):
  text=(root/'ui/ChatActivity.java').read_text()
  block=text[text.index('} else if (id == NotificationCenter.voiceTranscriptionUpdate)'):]
  self.assertIn('if (args.length > 2 && args[2] instanceof String)',block[:2200])
 def test_message_list_null_guard(self):
  text=(root/'ui/ChatActivity.java').read_text()
  self.assertIn('messages == null ? -1 : messages.indexOf(messageObject)',text)
 def test_callback_bound_to_original_account_and_cell(self):
  text=(root/'ui/Components/TranscribeButton.java').read_text()
  block=text[text.index('private void handleRouterTap()'):text.index('public void drawGradientBackground')]
  self.assertIn('final int account = messageObject.currentAccount;',block)
  self.assertIn('parent.getMessageObject() == messageObject',block)
  self.assertNotIn('getInstance(parent.currentAccount)',block)
 def test_cancel_marks_completed(self):
  text=(root/'messenger/VoiceToTextViaBot.java').read_text()
  block=text[text.index('public static void cancelPending'):text.index('private static void complete')]
  self.assertIn('request.completed = true;',block)
if __name__=='__main__': unittest.main()
