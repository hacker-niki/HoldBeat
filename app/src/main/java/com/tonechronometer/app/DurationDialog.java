package com.tonechronometer.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** Numeric duration and set-plan editors share the same confirmation and rotation handling. */
public final class DurationDialog extends DialogFragment {
    static DurationDialog forDuration(long ms) {
        DurationDialog dialog = new DurationDialog();
        Bundle args = new Bundle();
        args.putLong("duration", ms);
        dialog.setArguments(args);
        return dialog;
    }

    static DurationDialog forSets(long workMs, long restMs, int count) {
        DurationDialog dialog = forDuration(workMs);
        dialog.getArguments().putLong("rest", restMs);
        dialog.getArguments().putInt("count", count);
        return dialog;
    }

    @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
        MainActivity activity = (MainActivity) getActivity();
        Bundle args = getArguments();
        boolean sets = args.containsKey("count");
        View content = activity.getLayoutInflater().inflate(
                sets ? R.layout.dialog_set_plan : R.layout.dialog_duration, null);
        int duration = (int) (args.getLong("duration") / 1000);
        EditText last;
        Runnable apply;
        if (sets) {
            EditText work = field(content, R.id.work_seconds, duration);
            EditText rest = field(content, R.id.rest_seconds, (int) (args.getLong("rest") / 1000));
            EditText count = field(content, R.id.set_count, args.getInt("count"));
            last = count;
            apply = () -> {
                int workSeconds = value(work, -1);
                int restSeconds = value(rest, -1);
                int setsCount = value(count, -1);
                if (!within(work, workSeconds, 1, 5999) || !within(rest, restSeconds, 0, 5999)
                        || !within(count, setsCount, 1, 99)) return;
                activity.chooseSetPlan(workSeconds * 1000L, restSeconds * 1000L, setsCount);
                dismiss();
            };
        } else {
            EditText minutes = field(content, R.id.minutes, duration / 60);
            EditText seconds = field(content, R.id.seconds, duration % 60);
            last = seconds;
            apply = () -> {
                int min = value(minutes, 0);
                int sec = value(seconds, 0);
                if (min < 0 || min > 99 || sec < 0 || sec > 59 || min + sec == 0) {
                    content.findViewById(R.id.duration_error).setVisibility(View.VISIBLE);
                    return;
                }
                activity.chooseDuration((min * 60L + sec) * 1000);
                dismiss();
            };
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(sets ? R.string.workout_plan : R.string.custom_duration).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(sets ? R.string.save_set_plan : R.string.set_duration, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> apply.run()));
        last.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            apply.run();
            return true;
        });
        return dialog;
    }

    private EditText field(View content, int id, int initialValue) {
        EditText input = content.findViewById(id);
        input.setText(String.valueOf(initialValue));
        return input;
    }

    private int value(EditText field, int emptyValue) {
        String input = field.getText().toString();
        if (input.isEmpty()) return emptyValue;
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private boolean within(EditText field, int value, int min, int max) {
        if (value >= min && value <= max) return true;
        field.requestFocus();
        field.setError(getString(R.string.number_range, min, max));
        return false;
    }
}
