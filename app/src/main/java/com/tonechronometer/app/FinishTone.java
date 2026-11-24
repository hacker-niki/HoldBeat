package com.tonechronometer.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/** Plays brief start and target cues through the alarm stream. */
public final class FinishTone {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static FinishTone active;
    private final Runnable onDone;
    private final Runnable timeout = this::finish;
    private MediaPlayer player;

    private FinishTone(Runnable onDone) {
        this.onDone = onDone;
    }

    public static void preview(Context context) {
        play(context, R.raw.finish_tone, null);
    }

    static void play(Context context, int resource, Runnable onDone) {
        if (active != null) {
            active.finish();
        }
        active = new FinishTone(onDone);
        active.start(context.getApplicationContext(), resource);
    }

    private void start(Context context, int resource) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        try {
            player = MediaPlayer.create(context, resource, attributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (player == null) {
                Log.e("FinishTone", "Unable to open timer cue");
                finish();
                return;
            }
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnCompletionListener(ignored -> finish());
            player.setOnErrorListener((ignored, what, extra) -> {
                Log.e("FinishTone", "Playback failed: " + what + ", " + extra);
                finish();
                return true;
            });
            // Release the receiver even if a device's audio backend loses its callback.
            handler.postDelayed(timeout, 4_000);
            player.start();
        } catch (RuntimeException unavailable) {
            Log.e("FinishTone", "Unable to play timer cue", unavailable);
            finish();
        }
    }

    private void finish() {
        if (active != this) {
            return;
        }
        active = null;
        handler.removeCallbacks(timeout);
        if (player != null) {
            player.release();
            player = null;
        }
        if (onDone != null) {
            onDone.run();
        }
    }
}
