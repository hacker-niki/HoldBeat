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

        public final boolean sets;
        public final boolean resting;
        public final long restMs;
        public final int setNumber;
        public final int setCount;

        private Snapshot(State state, long durationMs, long remainingMs, long elapsedMs,
                         long prepDurationMs, boolean stopwatch, boolean preparing, SetSequence sequence, int step) {
            this.state = state;
            this.durationMs = durationMs;
            this.remainingMs = remainingMs;
            this.elapsedMs = elapsedMs;
            this.prepDurationMs = prepDurationMs;
            this.stopwatch = stopwatch;
            this.preparing = preparing;
            sets = sequence != null;
            resting = sets && sequence.isRest(step);
            restMs = sets ? sequence.restMs : 0;
            setNumber = sets ? sequence.setNumber(step) : 1;
            setCount = sets ? sequence.count : 1;
        }
    }

    // Keep the original action and stored deadline so an update preserves an existing countdown.
    static final String ACTION_EXPIRE = "com.tonechronometer.app.EXPIRE";
    static final String ACTION_BEGIN = "com.tonechronometer.app.BEGIN";
    static final String ACTION_SET = "com.tonechronometer.app.SET_BOUNDARY";
    // Seven slots cover a five-second scheduling horizon even at one second per phase.
    private static final int SET_ALARM_SLOTS = 7;
    private static final long SYSTEM_ALARM_MIN_DELAY_MS = 5_000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable shortCallback;
    private static PowerManager.WakeLock shortLock;
    private static long armedSetGoal = -1;
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
                restoreSchedule(true);
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
        SetSequence sequence = setSequence();
        int step = settings.getInt("setsStep", 0);
        long goal = deadline();
        long programMs = sequence == null ? duration : sequence.totalMs;
        long elapsed = 0;
        long remaining = duration;
        switch (state) {
            case PREPARING:
                remaining = Math.max(0, goal - programMs - time);
                break;
            case RUNNING:
            case OVERTIME:
                elapsed = Math.max(0, time - goal + programMs);
                remaining = sequence == null ? Math.max(0, goal - time)
                        : Math.max(0, sequence.startOf(step + 1) - elapsed);
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
                settings.getBoolean("stopwatch", false), preparing, sequence, step);
    }

    public void setDuration(long durationMs) {
        if (durationMs < 1_000 || durationMs > 5_999_000) {
            throw new IllegalArgumentException("Duration must be between 1 second and 99:59");
        }
        cancelAlarms();
        settings.edit().putLong("duration", durationMs).putString("state", State.IDLE.name())
                .remove("setsCount").apply();
    }

    public void start() {
        start(0, false);
    }

    public void start(int prepSeconds, boolean stopwatch) {
        start(prepSeconds, stopwatch, null);
    }

    public void startSets(int prepSeconds, long restMs, int setCount) {
        start(prepSeconds, false, new SetSequence(duration(), restMs, setCount));
    }

    private void start(int prepSeconds, boolean stopwatch, SetSequence sequence) {
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
            sequence = setSequence();
        }
        long programMs = sequence == null ? current.durationMs : sequence.totalMs;
        long goal = now() + lead + programMs - elapsed;
        State next = lead > 0 ? State.PREPARING : State.RUNNING;
        if (stopwatch && elapsed >= current.durationMs) next = State.OVERTIME;
        // Scheduling can fail if exact-alarm access was revoked. Preserve prior state in that case.
        int step = lead > 0 ? -1 : 0;
        if (sequence != null) {
            if (lead == 0) step = sequence.stepAt(elapsed);
            scheduleSets(sequence, goal, step, -1);
        } else if (hasAlarm(next)) {
            schedule(next, goal, current.durationMs);
        }
        settings.edit().putString("state", next.name()).putLong("deadline", goal)
                .putInt("setsCount", sequence == null ? 0 : sequence.count)
                .putLong("setsRest", sequence == null ? 0 : sequence.restMs).putInt("setsStep", step)
                .putLong("prep", prep).putBoolean("stopwatch", stopwatch)
                .putInt("boot", bootCount).remove("elapsed").remove("pausedPrep").apply();
        if (sequence != null && lead == 0 && current.state != State.PAUSED) {
            FinishTone.play(context, R.raw.start_tone, null);
        }
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
        cancelAlarms();
        settings.edit().putString("state", State.IDLE.name()).remove("elapsed").remove("setsCount")
                .remove("prep").remove("stopwatch").remove("pausedPrep").apply();
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
        SetSequence sequence = setSequence();
        if (sequence != null) return advanceSets(sequence, time, goal);
        if (time >= goal) {
            boolean stopwatch = settings.getBoolean("stopwatch", false);
            settings.edit().putString("state", (stopwatch ? State.OVERTIME : State.FINISHED).name())
                    .putLong("elapsed", duration()).apply();
            cancelAlarms();
            return R.raw.finish_tone;
        }
        if (state == State.PREPARING && time >= goal - duration()) {
            settings.edit().putString("state", State.RUNNING.name()).apply();
            cancelAlarm(ACTION_BEGIN, 0);
            clearShortDeadline();
            bridgeShortDeadline(goal);
            return R.raw.start_tone;
        }
        return 0;
    }

    private int advanceSets(SetSequence sequence, long time, long goal) {
        int step = sequence.stepAt(time - goal + sequence.totalMs);
        if (step <= settings.getInt("setsStep", 0)) return 0;
        if (step == sequence.endStep) {
            settings.edit().putString("state", State.FINISHED.name()).putInt("setsStep", step)
                    .putLong("elapsed", sequence.totalMs).apply();
            cancelAlarms();
            return R.raw.finish_tone;
        }
        settings.edit().putString("state", State.RUNNING.name()).putInt("setsStep", step).apply();
        try {
            scheduleSets(sequence, goal, step, settings.getInt("setsQueuedThrough", -1));
        } catch (SecurityException revoked) {
            savePause(snapshot(time));
        }
        // Late broadcasts catch up to the active phase without replaying missed cues.
        return sequence.isRest(step) ? R.raw.rest_tone : R.raw.start_tone;
    }

    int handleAlarm(String action) {
        int cue = advance(now());
        State state = state();
        if (ACTION_SET.equals(action) && cue == 0 && hasAlarm(state)) {
            SetSequence sequence = setSequence();
            if (sequence != null) bridgeNextSetBoundary(sequence);
        }
        boolean early = cue == 0 && (ACTION_EXPIRE.equals(action)
                || (state == State.PREPARING && ACTION_BEGIN.equals(action)));
        if (hasAlarm(state) && (Intent.ACTION_TIME_CHANGED.equals(action) || early)) {
            // Keep the monotonic deadline across clock adjustments and unexpectedly early deliveries.
            restoreSchedule(false);
        }
        return cue;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    private long duration() {
        return settings.getLong("duration", 30_000L);
    }

    private SetSequence setSequence() {
        int count = settings.getInt("setsCount", 0);
        return count == 0 ? null : new SetSequence(duration(), settings.getLong("setsRest", 0), count);
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

    private void restoreSchedule(boolean preserveQueuedAlarms) {
        try {
            SetSequence sequence = setSequence();
            if (sequence == null) {
                schedule(state(), deadline(), duration());
            } else {
                // Restore from the current clock, including a phase missed during process death.
                int step = sequence.stepAt(now() - deadline() + sequence.totalMs);
                if (step < sequence.endStep) {
                    // Only this live process can attest that it registered the full window.
                    // An Android PendingIntent can still exist after its alarm was removed.
                    int queued = preserveQueuedAlarms && armedSetGoal == deadline()
                            ? settings.getInt("setsQueuedThrough", -1) : -1;
                    scheduleSets(sequence, deadline(), step, queued);
                }
            }
        } catch (SecurityException revoked) {
            savePause(snapshot(now()));
        }
    }

    private void schedule(State phase, long goal, long duration) {
        PendingIntent showTimer = showTimerIntent();
        try {
            // Register BOTH deadlines up front so even a one-second hold after a lead-in
            // has its goal alarm scheduled before Android's five-second minimum horizon.
            setAlarm(ACTION_EXPIRE, 0, goal, showTimer);
            if (phase == State.PREPARING) setAlarm(ACTION_BEGIN, 0, goal - duration, showTimer);
        } catch (SecurityException denied) {
            cancelAlarms();
            throw denied;
        }
        clearShortDeadline();
        bridgeShortDeadline(phase == State.PREPARING ? goal - duration : goal);
    }

    private void scheduleSets(SetSequence sequence, long goal, int step, int queuedThrough) {
        long start = goal - sequence.totalMs;
        int next = step + 1;
        int last = next;
        // Prearm short phases following the next alarm before the OS can clamp them,
        // including when that alarm restarts the app process after a long work interval.
        long horizon = start + sequence.startOf(next) + SYSTEM_ALARM_MIN_DELAY_MS;
        while (last < sequence.endStep && start + sequence.startOf(last) < horizon) {
            last++;
        }
        try {
            PendingIntent showTimer = showTimerIntent();
            for (int boundary = Math.max(next, queuedThrough + 1); boundary <= last; boundary++) {
                setAlarm(ACTION_SET, boundary % SET_ALARM_SLOTS, start + sequence.startOf(boundary), showTimer);
            }
        } catch (SecurityException denied) {
            cancelSetAlarms();
            throw denied;
        }
        if (queuedThrough < 0) armedSetGoal = goal;
        settings.edit().putInt("setsQueuedThrough", Math.max(last, queuedThrough)).apply();
        clearShortDeadline();
        bridgeShortDeadline(start + sequence.startOf(next));
    }

    private void bridgeNextSetBoundary(SetSequence sequence) {
        clearShortDeadline();
        int next = settings.getInt("setsStep", 0) + 1;
        bridgeShortDeadline(deadline() - sequence.totalMs + sequence.startOf(next));
    }

    private PendingIntent showTimerIntent() {
        return PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private void setAlarm(String action, int request, long deadline, PendingIntent showTimer) {
        long wallTime = System.currentTimeMillis() + Math.max(0, deadline - now());
        alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(wallTime, showTimer),
                alarmIntent(action, request, PendingIntent.FLAG_IMMUTABLE));
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
                SetSequence sequence = setSequence();
                if (sequence == null) {
                    bridgeShortDeadline(state() == State.PREPARING ? deadline() - duration() : deadline());
                } else {
                    bridgeNextSetBoundary(sequence);
                }
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
        if (settings.getInt("setsCount", 0) > 0) cancelSetAlarms();
        cancelAlarm(ACTION_BEGIN, 0);
        cancelAlarm(ACTION_EXPIRE, 0);
    }

    private void cancelSetAlarms() {
        armedSetGoal = -1;
        for (int slot = 0; slot < SET_ALARM_SLOTS; slot++) cancelAlarm(ACTION_SET, slot);
    }

    private void cancelAlarm(String action, int request) {
        PendingIntent alarm = alarmIntent(action, request, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (alarm != null) alarms.cancel(alarm);
    }

    private PendingIntent alarmIntent(String action, int request, int flags) {
        return PendingIntent.getBroadcast(context, request, new Intent(context, TimerReceiver.class).setAction(action), flags);
    }
}
