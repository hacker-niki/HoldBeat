package com.tonechronometer.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/** The platform restores the two fields when the screen rotates. */
public final class DurationDialog extends DialogFragment {
    static DurationDialog forDuration(long ms) {
        DurationDialog dialog = new DurationDialog();
        Bundle args = new Bundle();
        args.putLong("duration", ms);
        dialog.setArguments(args);
        return dialog;
    }

    @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
        MainActivity activity = (MainActivity) getActivity();
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_duration, null);
        EditText minutes = content.findViewById(R.id.minutes);
        EditText seconds = content.findViewById(R.id.seconds);
        long total = getArguments().getLong("duration") / 1000;
        minutes.setText(String.valueOf(total / 60));
        seconds.setText(String.valueOf(total % 60));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.custom_duration).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.set_duration, null).create();
        Runnable apply = () -> {
            int min = value(minutes);
            int sec = value(seconds);
            if (min < 0 || min > 99 || sec < 0 || sec > 59 || min + sec == 0) {
                content.findViewById(R.id.duration_error).setVisibility(View.VISIBLE);
                return;
            }
            activity.chooseDuration((min * 60L + sec) * 1000);
            dismiss();
        };
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> apply.run()));
        seconds.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            apply.run();
            return true;
        });
        return dialog;
    }

    private int value(EditText field) {
        String input = field.getText().toString();
        if (input.isEmpty()) return 0;
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
