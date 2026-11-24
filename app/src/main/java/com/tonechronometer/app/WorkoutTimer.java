package com.tonechronometer.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

/** Main-thread timer controller shared by the activity and the alarm receiver. */
public final class WorkoutTimer {
    public enum State { IDLE, PREPARING, RUNNING, PAUSED, OVERTIME, FINISHED }

    public static final class Snapshot {
        public final State state;
        public final long durationMs;
        public final long remainingMs;
        public final long elapsedMs;
        public final long prepDurationMs;
        public final boolean stopwatch;
        public final boolean preparing;

        private Snapshot(State state, long durationMs, long remainingMs, long elapsedMs,
                         long prepDurationMs, boolean stopwatch, boolean preparing) {
            this.state = state;
            this.durationMs = durationMs;
            this.remainingMs = remainingMs;
            this.elapsedMs = elapsedMs;
            this.prepDurationMs = prepDurationMs;
            this.stopwatch = stopwatch;
            this.preparing = preparing;
        }
    }

    // Keep the original action and stored deadline so an update preserves an existing countdown.
    static final String ACTION_EXPIRE = "com.tonechronometer.app.EXPIRE";
    static final String ACTION_BEGIN = "com.tonechronometer.app.BEGIN";
    private static final long SYSTEM_ALARM_MIN_DELAY_MS = 5_000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable shortCallback;
    private static PowerManager.WakeLock shortLock;
    private final Context context;
    private final AlarmManager alarms;
    private final SharedPreferences settings;
    private final int bootCount;

    public WorkoutTimer(Context context) {
        this(context, true);
    }

    WorkoutTimer(Context context, boolean restoreAlarms) {
        this.context = context.getApplicationContext();
        alarms = this.context.getSystemService(AlarmManager.class);
        settings = this.context.getSharedPreferences("countdown", Context.MODE_PRIVATE);
        bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        State state = state();
        if (hasAlarm(state) || state == State.OVERTIME) {
            if (settings.getInt("boot", -1) != bootCount) {
                // elapsedRealtime resets on reboot; saved pauses and completed results remain valid.
                reset();
            } else if (restoreAlarms && hasAlarm(state) && deadline() > now()) {
                restoreSchedule();
            }
        }
    }

    public Snapshot read() {
        long time = now();
        int cue = advance(time);
        if (cue != 0) FinishTone.play(context, cue, null);
        return snapshot(time);
    }

    private Snapshot snapshot(long time) {
        State state = state();
        long duration = duration();
        boolean preparing = state == State.PREPARING
                || (state == State.PAUSED && settings.getBoolean("pausedPrep", false));
        long elapsed = 0;
        long remaining = duration;
        switch (state) {
            case PREPARING:
                remaining = Math.max(0, deadline() - duration - time);
                break;
            case RUNNING:
            case OVERTIME:
                remaining = Math.max(0, deadline() - time);
                elapsed = Math.max(0, time - deadline() + duration);
                break;
            case PAUSED:
                remaining = settings.getLong("paused", duration);
                elapsed = settings.getLong("elapsed", duration - remaining);
                break;
            case FINISHED:
                remaining = 0;
                elapsed = settings.getLong("elapsed", duration);
                break;
            default:
                break;
        }
        return new Snapshot(state, duration, remaining, elapsed, settings.getLong("prep", 0),
                settings.getBoolean("stopwatch", false), preparing);
    }

    public void setDuration(long durationMs) {
        if (durationMs < 1_000 || durationMs > 5_999_000) {
            throw new IllegalArgumentException("Duration must be between 1 second and 99:59");
        }
        cancelAlarms();
        settings.edit().putLong("duration", durationMs).putString("state", State.IDLE.name()).apply();
    }

    public void start() {
        start(0, false);
    }

    public void start(int prepSeconds, boolean stopwatch) {
        if (prepSeconds != 0 && prepSeconds != 3 && prepSeconds != 5 && prepSeconds != 10) {
            throw new IllegalArgumentException("Unsupported preparation duration");
        }
        Snapshot current = read();
        if (hasAlarm(current.state) || current.state == State.OVERTIME) return;
        long prep = prepSeconds * 1000L;
        long lead = prep;
        long elapsed = 0;
        if (current.state == State.PAUSED) {
            prep = current.prepDurationMs;
            lead = current.preparing ? current.remainingMs : 0;
            stopwatch = current.stopwatch;
            elapsed = current.elapsedMs;
        }
        long goal = now() + lead + current.durationMs - elapsed;
        State next = lead > 0 ? State.PREPARING : State.RUNNING;
        if (stopwatch && elapsed >= current.durationMs) next = State.OVERTIME;
        // Scheduling can fail if exact-alarm access was revoked. Preserve prior state in that case.
        if (hasAlarm(next)) schedule(next, goal, current.durationMs);
        settings.edit().putString("state", next.name()).putLong("deadline", goal)
                .putLong("prep", prep).putBoolean("stopwatch", stopwatch)
                .putInt("boot", bootCount).remove("elapsed").remove("pausedPrep").apply();
    }

    public void pause() {
        Snapshot current = read();
        if (hasAlarm(current.state) || current.state == State.OVERTIME) savePause(current);
    }

    private void savePause(Snapshot current) {
        settings.edit().putString("state", State.PAUSED.name()).putLong("paused", current.remainingMs)
                .putLong("elapsed", current.elapsedMs).putBoolean("pausedPrep", current.preparing).apply();
        cancelAlarms();
    }

    /** Finish manually and preserve the measured result, including time beyond the goal. */
    public void stop() {
        Snapshot current = read();
        if (current.state == State.IDLE || current.state == State.FINISHED) return;
        settings.edit().putString("state", State.FINISHED.name()).putLong("elapsed", current.elapsedMs).apply();
        cancelAlarms();
    }

    public void reset() {
        settings.edit().putString("state", State.IDLE.name()).remove("elapsed")
                .remove("prep").remove("stopwatch").remove("pausedPrep").apply();
        cancelAlarms();
    }

    public boolean canSchedule() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms();
    }

    public Intent exactAlarmSettings() {
        String action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM : Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
        return new Intent(action, Uri.parse("package:" + context.getPackageName()));
    }

    /** Return the sound resource only to the first caller to cross this milestone. */
    private int advance(long time) {
        State state = state();
        if (!hasAlarm(state)) return 0;
        long goal = deadline();
        if (time >= goal) {
            boolean stopwatch = settings.getBoolean("stopwatch", false);
            settings.edit().putString("state", (stopwatch ? State.OVERTIME : State.FINISHED).name())
                    .putLong("elapsed", duration()).apply();
            cancelAlarms();
            return R.raw.finish_tone;
        }
        if (state == State.PREPARING && time >= goal - duration()) {
            settings.edit().putString("state", State.RUNNING.name()).apply();
            cancelAlarm(ACTION_BEGIN);
            clearShortDeadline();
            bridgeShortDeadline(goal);
            return R.raw.start_tone;
        }
        return 0;
    }

    int handleAlarm(String action) {
        int cue = advance(now());
        State state = state();
        boolean early = cue == 0 && (ACTION_EXPIRE.equals(action)
                || (state == State.PREPARING && ACTION_BEGIN.equals(action)));
        if (hasAlarm(state) && (Intent.ACTION_TIME_CHANGED.equals(action) || early)) {
            // Keep the monotonic deadline across clock adjustments and unexpectedly early deliveries.
            restoreSchedule();
        }
        return cue;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    private long duration() {
        return settings.getLong("duration", 30_000L);
    }

    private long deadline() {
        return settings.getLong("deadline", 0);
    }

    private State state() {
        return State.valueOf(settings.getString("state", State.IDLE.name()));
    }

    private static boolean hasAlarm(State state) {
        return state == State.RUNNING || state == State.PREPARING;
    }

    private void restoreSchedule() {
        try {
            schedule(state(), deadline(), duration());
        } catch (SecurityException revoked) {
            savePause(snapshot(now()));
        }
    }

    private void schedule(State phase, long goal, long duration) {
        PendingIntent showTimer = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        try {
            // Register BOTH deadlines up front so even a one-second hold after a lead-in
            // has its goal alarm scheduled before Android's five-second minimum horizon.
            setAlarm(ACTION_EXPIRE, goal, showTimer);
            if (phase == State.PREPARING) setAlarm(ACTION_BEGIN, goal - duration, showTimer);
        } catch (SecurityException denied) {
            cancelAlarms();
            throw denied;
        }
        clearShortDeadline();
        bridgeShortDeadline(phase == State.PREPARING ? goal - duration : goal);
    }

    private void setAlarm(String action, long deadline, PendingIntent showTimer) {
        long wallTime = System.currentTimeMillis() + Math.max(0, deadline - now());
        alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(wallTime, showTimer),
                alarmIntent(action, PendingIntent.FLAG_IMMUTABLE));
    }

    private void bridgeShortDeadline(long deadline) {
        long left = Math.max(0, deadline - now());
        if (left > SYSTEM_ALARM_MIN_DELAY_MS) return;
        // Some devices clamp newly registered exact alarms to at least five seconds.
        shortLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "HoldBeat:shortDeadline");
        shortLock.acquire(left + 1_000);
        shortCallback = () -> {
            clearShortDeadline();
            int cue = advance(now());
            if (cue != 0) {
                FinishTone.play(context, cue, null);
            } else if (hasAlarm(state())) {
                // Handler uses uptime; reconcile rounding against the elapsed-realtime clock.
                bridgeShortDeadline(state() == State.PREPARING ? deadline() - duration() : deadline());
            }
        };
        main.postDelayed(shortCallback, left);
    }

    private static void clearShortDeadline() {
        if (shortCallback != null) {
            main.removeCallbacks(shortCallback);
            shortCallback = null;
        }
        if (shortLock != null) {
            if (shortLock.isHeld()) shortLock.release();
            shortLock = null;
        }
    }

    private void cancelAlarms() {
        clearShortDeadline();
        cancelAlarm(ACTION_BEGIN);
        cancelAlarm(ACTION_EXPIRE);
    }

    private void cancelAlarm(String action) {
        PendingIntent alarm = alarmIntent(action, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (alarm != null) alarms.cancel(alarm);
    }

    private PendingIntent alarmIntent(String action, int flags) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, TimerReceiver.class).setAction(action), flags);
    }
}
