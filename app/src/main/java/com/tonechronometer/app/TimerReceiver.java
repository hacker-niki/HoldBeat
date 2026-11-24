package com.tonechronometer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!WorkoutTimer.ACTION_EXPIRE.equals(action) && !WorkoutTimer.ACTION_BEGIN.equals(action)
                && !WorkoutTimer.ACTION_SET.equals(action) && !Intent.ACTION_TIME_CHANGED.equals(action)) return;
        int cue = new WorkoutTimer(context, false).handleAlarm(action);
        if (cue != 0) {
            PendingResult result = goAsync();
            FinishTone.play(context, cue, result::finish);
        }
    }
}
