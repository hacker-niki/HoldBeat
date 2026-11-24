package com.tonechronometer.app;

import android.content.Context;
import android.content.SharedPreferences;

final class WorkoutSettings {
    enum Mode { COUNTDOWN, STOPWATCH, SETS }

    private final SharedPreferences prefs;

    WorkoutSettings(Context context) {
        prefs = context.getSharedPreferences("workout_settings", Context.MODE_PRIVATE);
    }

    int precision() {
        return prefs.getInt("fraction_digits", 0);
    }

    int prepSeconds() {
        return prefs.getInt("prep_seconds", 0);
    }

    Mode mode() {
        String stored = prefs.getString("timer_mode", null);
        if (stored != null) return Mode.valueOf(stored);
        return prefs.getBoolean("stopwatch", false) ? Mode.STOPWATCH : Mode.COUNTDOWN;
    }

    long restMs() {
        return prefs.getLong("rest_ms", 15_000);
    }

    int setCount() {
        return prefs.getInt("sets_count", 3);
    }

    boolean keepScreenOn() {
        return prefs.getBoolean("keep_screen_on", true);
    }

    void setPrecision(int digits) {
        prefs.edit().putInt("fraction_digits", digits).apply();
    }

    void setPrepSeconds(int seconds) {
        prefs.edit().putInt("prep_seconds", seconds).apply();
    }

    void setMode(Mode mode) {
        prefs.edit().putString("timer_mode", mode.name()).apply();
    }

    void setPlan(long restMs, int count) {
        prefs.edit().putLong("rest_ms", restMs).putInt("sets_count", count).apply();
    }

    void setKeepScreenOn(boolean enabled) {
        prefs.edit().putBoolean("keep_screen_on", enabled).apply();
    }
}
