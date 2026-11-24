package com.tonechronometer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

public final class SettingsActivity extends Activity {
    static final String EXTRA_ACTIVE = "active_hold";
    private static final int[] PREP_SECONDS = {0, 3, 5, 10};
    private final int[] precisionLabels = {
            R.string.precision_whole, R.string.precision_tenths,
            R.string.precision_hundredths, R.string.precision_ms
    };
    private WorkoutSettings settings;
    private int openRow;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        View screen = findViewById(R.id.settings_screen);
        ScreenInsets.apply(this, screen);
        settings = new WorkoutSettings(this);
        Switch keepScreenOn = findViewById(R.id.keep_screen_on);
        keepScreenOn.setChecked(settings.keepScreenOn());
        screen.setKeepScreenOn(keepScreenOn.isChecked());
        keepScreenOn.setOnCheckedChangeListener((button, checked) -> {
            settings.setKeepScreenOn(checked);
            screen.setKeepScreenOn(checked);
        });
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());
        findViewById(R.id.next_hold_hint).setVisibility(
                getIntent().getBooleanExtra(EXTRA_ACTIVE, false) ? View.VISIBLE : View.GONE);
        render();
        if (state != null && state.getInt("open_row") != 0) {
            findViewById(state.getInt("open_row")).performClick();
        }
    }

    private void render() {
        int digits = settings.precision();
        String[] precisions = new String[precisionLabels.length];
        for (int i = 0; i < precisionLabels.length; i++) {
            precisions[i] = getString(precisionLabels[i]);
        }
        String[] prepOptions = new String[PREP_SECONDS.length];
        int selectedPrep = 0;
        for (int i = 0; i < PREP_SECONDS.length; i++) {
            int seconds = PREP_SECONDS[i];
            prepOptions[i] = seconds == 0 ? getString(R.string.off) :
                    getResources().getQuantityString(R.plurals.seconds_count, seconds, seconds);
            if (seconds == settings.prepSeconds()) selectedPrep = i;
        }
        String[] modes = {getString(R.string.countdown), getString(R.string.stopwatch)};
        bind(R.id.precision_setting, R.string.precision, precisions, digits);
        bind(R.id.prep_setting, R.string.preparation, prepOptions, selectedPrep);
        bind(R.id.mode_setting, R.string.mode, modes, settings.stopwatch() ? 1 : 0);

        // Use the same locale and decimal separator as the dial.
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String preview = String.format(locale, "%02d:%02d", 0, 12)
                + TimerDial.fractionText(345, digits, locale);
        ((TextView) findViewById(R.id.precision_preview)).setText(preview);
        ((TextView) findViewById(R.id.mode_summary)).setText(
                settings.stopwatch() ? R.string.stopwatch_summary : R.string.countdown_summary);
    }

    private void bind(int rowId, int titleId, String[] choices, int selected) {
        View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.setting_title)).setText(titleId);
        ((TextView) row.findViewById(R.id.setting_value)).setText(choices[selected]);
        row.setContentDescription(getString(R.string.setting_value, getString(titleId), choices[selected]));
        row.setOnClickListener(v -> {
            openRow = rowId;
            new AlertDialog.Builder(this)
                    .setTitle(titleId)
                    .setSingleChoiceItems(choices, selected, (dialog, choice) -> {
                        if (rowId == R.id.precision_setting) {
                            settings.setPrecision(choice);
                        } else if (rowId == R.id.prep_setting) {
                            settings.setPrepSeconds(PREP_SECONDS[choice]);
                        } else {
                            settings.setStopwatch(choice == 1);
                        }
                        dialog.dismiss();
                        render();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener(ignored -> openRow = 0)
                    .show();
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("open_row", openRow);
    }
}
