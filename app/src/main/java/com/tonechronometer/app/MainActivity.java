package com.tonechronometer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int[] PRESET_SECONDS = {15, 30, 45, 60};
    private final Button[] presets = new Button[PRESET_SECONDS.length];
    private WorkoutSettings settings;
    private int precision;
    private boolean preferredStopwatch;
    private WorkoutTimer timer;
    private TimerDial dial;
    private Button primary;
    private ImageButton secondary;
    private View custom;
    private View preview;
    private WorkoutTimer.State displayedState;
    private long displayedDuration = -1;
    private boolean displayedStopwatch;
    private boolean displayedPreparing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_ALARM);
        setContentView(R.layout.activity_main);
        ScreenInsets.apply(this, findViewById(R.id.screen));
        settings = new WorkoutSettings(this);
        timer = new WorkoutTimer(this);
        dial = findViewById(R.id.dial);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.reset);
        custom = findViewById(R.id.custom);
        preview = findViewById(R.id.tone_preview);

        findViewById(R.id.settings).setOnClickListener(v -> {
            boolean active = displayedState != WorkoutTimer.State.IDLE && displayedState != WorkoutTimer.State.FINISHED;
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(SettingsActivity.EXTRA_ACTIVE, active);
            startActivity(intent);
        });

        LinearLayout presetRow = findViewById(R.id.presets);
        for (int i = 0; i < PRESET_SECONDS.length; i++) {
            int seconds = PRESET_SECONDS[i];
            Button button = new Button(this, null, 0, R.style.TimerButton);
            button.setText(getString(R.string.preset_seconds, seconds));
            button.setContentDescription(getResources().getQuantityString(R.plurals.seconds_count, seconds, seconds));
            button.setOnClickListener(v -> chooseDuration(seconds * 1000L));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) params.setMarginStart(dp(8));
            presetRow.addView(button, params);
            presets[i] = button;
        }

        primary.setOnClickListener(v -> primaryAction());
        secondary.setOnClickListener(v -> {
            if (canStop(displayedState, displayedStopwatch)) {
                timer.pause();
            } else {
                timer.reset();
            }
            refresh();
        });
        custom.setOnClickListener(v -> DurationDialog.forDuration(timer.read().durationMs)
                .show(getFragmentManager(), "duration"));
        preview.setOnClickListener(v -> {
            FinishTone.preview(this);
            adviseIfMuted();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        findViewById(R.id.screen).setKeepScreenOn(settings.keepScreenOn());
        precision = settings.precision();
        preferredStopwatch = settings.stopwatch();
        refresh();
    }

    @Override protected void onPause() {
        dial.removeCallbacks(tick);
        super.onPause();
    }

    void chooseDuration(long durationMs) {
        timer.setDuration(durationMs);
        refresh();
    }

    private void primaryAction() {
        if (canStop(displayedState, displayedStopwatch)) {
            timer.stop();
        } else if (displayedState == WorkoutTimer.State.RUNNING || displayedState == WorkoutTimer.State.PREPARING) {
            timer.pause();
        } else {
            if (!timer.canSchedule()) {
                showAlarmSettings();
                return;
            }
            try {
                timer.start(settings.prepSeconds(), preferredStopwatch);
                adviseIfMuted();
            } catch (SecurityException permissionChanged) {
                showAlarmSettings();
            }
        }
        refresh();
    }

    private void showAlarmSettings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.alarm_access_title)
                .setMessage(R.string.alarm_access_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    try {
                        startActivity(timer.exactAlarmSettings());
                    } catch (ActivityNotFoundException unavailable) {
                        Toast.makeText(this, R.string.no_alarm_settings, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void adviseIfMuted() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            Toast.makeText(this, R.string.preview_at_volume_zero, Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable tick = this::refresh;

    private void refresh() {
        dial.removeCallbacks(tick);
        WorkoutTimer.Snapshot snapshot = timer.read();
        boolean stopwatch = snapshot.state == WorkoutTimer.State.IDLE ? preferredStopwatch : snapshot.stopwatch;
        dial.show(snapshot, stopwatch, precision);
        if (snapshot.state != displayedState || snapshot.durationMs != displayedDuration
                || stopwatch != displayedStopwatch || snapshot.preparing != displayedPreparing) {
            renderControls(snapshot, stopwatch);
            displayedState = snapshot.state;
            displayedDuration = snapshot.durationMs;
            displayedStopwatch = stopwatch;
            displayedPreparing = snapshot.preparing;
        }
        if (isTicking(snapshot.state)) {
            if (precision >= 2) {
                dial.postOnAnimation(tick);
            } else {
                long delay = precision == 0 ? 125 : 50;
                if (snapshot.remainingMs > 0) delay = Math.min(delay, snapshot.remainingMs);
                dial.postDelayed(tick, delay);
            }
        }
    }

    private boolean isTicking(WorkoutTimer.State state) {
        return state == WorkoutTimer.State.PREPARING || state == WorkoutTimer.State.RUNNING || state == WorkoutTimer.State.OVERTIME;
    }

    private boolean canStop(WorkoutTimer.State state, boolean stopwatch) {
        return stopwatch && (state == WorkoutTimer.State.RUNNING || state == WorkoutTimer.State.OVERTIME);
    }

    private void renderControls(WorkoutTimer.Snapshot snapshot, boolean stopwatch) {
        boolean editable = snapshot.state == WorkoutTimer.State.IDLE || snapshot.state == WorkoutTimer.State.FINISHED;
        boolean stopAction = canStop(snapshot.state, stopwatch);
        ((TextView) findViewById(R.id.duration_label)).setText(stopwatch ? R.string.target_duration : R.string.duration);
        ((TextView) findViewById(R.id.tone_label)).setText(stopwatch ? R.string.target_tone : R.string.tone);
        for (int i = 0; i < presets.length; i++) {
            Button preset = presets[i];
            boolean selected = snapshot.durationMs == PRESET_SECONDS[i] * 1000L;
            preset.setSelected(selected);
            preset.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            preset.setTextColor(getColor(selected ? R.color.accent : R.color.ink));
            setAvailable(preset, editable);
        }
        setAvailable(custom, editable);
        setAvailable(preview, !isTicking(snapshot.state));
        boolean canResetResult = snapshot.state == WorkoutTimer.State.FINISHED && stopwatch;
        secondary.setVisibility(!editable || canResetResult ? View.VISIBLE : View.GONE);
        secondary.setImageResource(stopAction ? R.drawable.ic_pause : R.drawable.ic_reset);
        secondary.setColorFilter(getColor(R.color.ink));
        secondary.setContentDescription(getString(stopAction ? R.string.pause : R.string.reset));

        int iconId = R.drawable.ic_play;
        int label;
        switch (snapshot.state) {
            case PREPARING:
                label = R.string.pause_prep;
                iconId = R.drawable.ic_pause;
                break;
            case RUNNING:
            case OVERTIME:
                label = stopAction ? R.string.stop : R.string.pause;
                iconId = stopAction ? R.drawable.ic_stop : R.drawable.ic_pause;
                break;
            case PAUSED: label = snapshot.preparing ? R.string.resume_prep : R.string.resume; break;
            case FINISHED: label = R.string.repeat; break;
            default: label = R.string.start;
        }
        String action = getString(label);
        SpannableString text = new SpannableString("\uFFFC  " + action);
        Drawable icon = getDrawable(iconId);
        icon.setBounds(0, 0, dp(18), dp(18));
        text.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        primary.setText(text);
        primary.setContentDescription(action);
    }

    private void setAvailable(View view, boolean available) {
        view.setEnabled(available);
        view.setAlpha(available ? 1f : 0.48f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
