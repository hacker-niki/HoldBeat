package com.tonechronometer.app;

import android.content.Context;
import android.content.SharedPreferences;

final class WorkoutSettings {
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

    boolean stopwatch() {
        return prefs.getBoolean("stopwatch", false);
    }

    void setPrecision(int digits) {
        prefs.edit().putInt("fraction_digits", digits).apply();
    }

    void setPrepSeconds(int seconds) {
        prefs.edit().putInt("prep_seconds", seconds).apply();
    }

    void setStopwatch(boolean enabled) {
        prefs.edit().putBoolean("stopwatch", enabled).apply();
    }
}
