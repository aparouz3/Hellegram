package org.telegram.messenger.partisan.voicechange;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.Switch;

/**
 * Simple voice changer settings UI (per-chat + global), opened from the chat menu.
 */
public class VoiceChangerUI {

    public static void showSettingsDialog(BaseFragment fragment, long dialogId) {
        showSettingsDialog(fragment, dialogId, null);
    }

    public static void showSettingsDialog(BaseFragment fragment, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final String chatName = org.telegram.messenger.DialogObject.getShortName(dialogId);

        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int grayColor = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        // ---- Per-chat switch ----
        Switch perChatSwitch = new Switch(activity, resourcesProvider);
        perChatSwitch.setChecked(VoiceChangerUtils.isVoiceChangeEnabledForDialog(dialogId), false);
        container.addView(makeSwitchRow(activity, resourcesProvider, "Change voice in this chat", "Applies to voice messages sent in " + chatName, perChatSwitch, textColor, grayColor));

        // ---- Global switch ----
        Switch globalSwitch = new Switch(activity, resourcesProvider);
        globalSwitch.setChecked(VoiceChangeSettings.voiceChangeEnabled.get().orElse(false), false);
        globalSwitch.setOnCheckedChangeListener((view, isChecked) -> VoiceChangeSettings.voiceChangeEnabled.set(isChecked));
        container.addView(makeSwitchRow(activity, resourcesProvider, "Voice changer enabled", "Global master switch — auto-enabled when you turn on a chat", globalSwitch, textColor, grayColor));

        // ---- Aggressive level ----
        Switch aggressiveSwitch = new Switch(activity, resourcesProvider);
        aggressiveSwitch.setChecked(VoiceChangeSettings.aggressiveChangeLevel.get().orElse(true), false);
        aggressiveSwitch.setOnCheckedChangeListener((view, isChecked) -> VoiceChangeSettings.aggressiveChangeLevel.set(isChecked));
        container.addView(makeSwitchRow(activity, resourcesProvider, "Aggressive change level", "Stronger voice alteration", aggressiveSwitch, textColor, grayColor));

        // ---- Pitch slider ----
        TextView pitchLabel = new TextView(activity);
        pitchLabel.setTextColor(textColor);
        pitchLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        pitchLabel.setText("Voice pitch");
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = AndroidUtilities.dp(12);
        container.addView(pitchLabel, labelLp);

        final TextView pitchValue = new TextView(activity);
        pitchValue.setTextColor(grayColor);
        pitchValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);

        final SeekBarView pitchSeekBar = new SeekBarView(activity, resourcesProvider);
        pitchSeekBar.setReportChanges(true);
        float currentShift = VoiceChangeSettings.f0Shift.get().orElse(1.0f);
        float initialProgress = Math.max(0f, Math.min(1f, (currentShift - 0.5f) / 1.5f));
        pitchSeekBar.setProgress(initialProgress, false);
        pitchSeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                float shift = 0.5f + progress * 1.5f;
                VoiceChangeSettings.f0Shift.set(shift);
                VoiceChangeSettings.lowRatio.set(shift);
                VoiceChangeSettings.midRatio.set(shift);
                VoiceChangeSettings.highRatio.set(shift);
                VoiceChangeSettings.spectrumDistortionParams.set("");
                pitchValue.setText(String.format(java.util.Locale.US, "%.2fx — higher = deeper voice", shift));
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(40));
        seekLp.topMargin = AndroidUtilities.dp(4);
        container.addView(pitchSeekBar, seekLp);

        pitchValue.setText(String.format(java.util.Locale.US, "%.2fx — higher = deeper voice", currentShift));
        LinearLayout.LayoutParams pitchValueLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        container.addView(pitchValue, pitchValueLp);

        // ---- Randomize button ----
        TextView randomizeButton = new TextView(activity);
        randomizeButton.setText("🎲 Randomize voice settings");
        randomizeButton.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider));
        randomizeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        randomizeButton.setGravity(Gravity.CENTER);
        randomizeButton.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(8));
        randomizeButton.setOnClickListener(v -> {
            new VoiceChangeSettingsGenerator().generateParameters(true);
            float shift = VoiceChangeSettings.f0Shift.get().orElse(1.0f);
            pitchSeekBar.setProgress(Math.max(0f, Math.min(1f, (shift - 0.5f) / 1.5f)), true);
            pitchValue.setText(String.format(java.util.Locale.US, "%.2fx — higher = deeper voice", shift));
            aggressiveSwitch.setChecked(VoiceChangeSettings.aggressiveChangeLevel.get().orElse(true), true);
        });
        container.addView(randomizeButton);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle("Voice Changer");
        builder.setSubtitle(chatName);
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (dialogInterface, i) -> {
            if (perChatSwitch.isChecked()) {
                // Ensure the feature actually works: global master ON + params set
                VoiceChangeSettings.voiceChangeEnabled.set(true);
                VoiceChangerUtils.ensureParametersSet();
            }
            VoiceChangerUtils.setVoiceChangeEnabledForDialog(dialogId, perChatSwitch.isChecked());
            if (onChanged != null) {
                onChanged.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    private static View makeSwitchRow(Activity activity, Theme.ResourcesProvider resourcesProvider, String title, String subtitle, Switch switchView, int textColor, int grayColor) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        // Switch is a passive drawing view (no touch handling) — the row must toggle it
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked(), true));

        LinearLayout textContainer = new LinearLayout(activity);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(activity);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setText(title);
        titleView.setTypeface(Typeface.DEFAULT);
        textContainer.addView(titleView);

        if (subtitle != null) {
            TextView subtitleView = new TextView(activity);
            subtitleView.setTextColor(grayColor);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setText(subtitle);
            subtitleView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
            textContainer.addView(subtitleView);
        }

        row.addView(textContainer);

        // Explicit size for the switch (it has no onMeasure) + click handling on the switch itself
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(28));
        switchLp.leftMargin = AndroidUtilities.dp(12);
        switchView.setLayoutParams(switchLp);
        switchView.setClickable(true);
        switchView.setFocusable(true);
        switchView.setDrawRipple(true);
        switchView.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked(), true));
        row.addView(switchView);

        return row;
    }
}
