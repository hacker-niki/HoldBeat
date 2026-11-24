package com.tonechronometer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Workout dial with small fractional digits and a separate whole-second TalkBack cadence. */
public final class TimerDial extends View {
    private static final float START = 116f;
    private static final float SWEEP = 308f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF(-126, -126, 126, 126);
    private final Typeface digits = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final int accent;
    private final int ink;
    private final int muted;
    private final int line;
    private final int panel;
    private final Locale locale;
    private final DecimalFormatSymbols symbols;
    private final char[] decimals = new char[4];
    private static final int[] QUANTUM_MS = {1000, 100, 10, 1};
    private String time = "";
    private String status = "";
    private String caption = "";
    private String label = "";
    private long lastSeconds = -1;
    private int lastStatus;
    private int lastCaption;
    private int lastPrecision = -1;
    private float fraction;

    public TimerDial(Context context, AttributeSet attrs) {
        super(context, attrs);
        accent = context.getColor(R.color.accent);
        ink = context.getColor(R.color.ink);
        muted = context.getColor(R.color.muted);
        line = context.getColor(R.color.line);
        panel = context.getColor(R.color.panel);
        locale = getResources().getConfiguration().getLocales().get(0);
        symbols = DecimalFormatSymbols.getInstance(locale);
    }

    void show(WorkoutTimer.Snapshot snapshot, boolean stopwatch, int precision) {
        boolean elapsed = stopwatch && !snapshot.preparing;
        long millis = elapsed ? snapshot.elapsedMs : snapshot.remainingMs;
        int quantum = QUANTUM_MS[precision];
        long rounded = (elapsed ? millis / quantum : (millis + quantum - 1) / quantum) * quantum;
        long seconds = rounded / 1000;
        int captionId = snapshot.preparing ? R.string.starts_in : elapsed ? R.string.elapsed : R.string.remaining;
        int statusId = statusLabel(snapshot.state, stopwatch);
        if (seconds != lastSeconds) {
            time = String.format(locale, "%02d:%02d", seconds / 60, seconds % 60);
        }
        fillFraction(decimals, (int) (rounded % 1000), precision, symbols);
        if (statusId != lastStatus || captionId != lastCaption || precision != lastPrecision || seconds != lastSeconds) {
            label = getContext().getString(statusId);
            status = label.toUpperCase(locale);
            caption = getContext().getString(captionId);
            if (precision == 0) {
                int description = elapsed ? R.string.elapsed_description : R.string.dial_description;
                setContentDescription(getContext().getString(description, label, spokenTime(seconds)));
            } else {
                String value = time + new String(decimals, 0, precision + 1);
                setContentDescription(getContext().getString(R.string.precise_description, label, caption, value));
            }
            lastStatus = statusId;
            lastCaption = captionId;
            lastPrecision = precision;
            lastSeconds = seconds;
        }
        long interval = snapshot.preparing ? snapshot.prepDurationMs : snapshot.durationMs;
        fraction = interval > 0 ? Math.min(1f, (float) millis / interval) : 0;
        invalidate();
    }

    private int statusLabel(WorkoutTimer.State state, boolean stopwatch) {
        switch (state) {
            case PREPARING: return R.string.preparing;
            case RUNNING: return R.string.running;
            case PAUSED: return R.string.paused;
            case OVERTIME: return R.string.target_reached;
            case FINISHED: return stopwatch ? R.string.stopped : R.string.finished;
            default: return R.string.ready;
        }
    }

    private String spokenTime(long seconds) {
        int mins = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        String spokenSeconds = getResources().getQuantityString(R.plurals.seconds_count, secs, secs);
        if (mins == 0) return spokenSeconds;
        String spokenMinutes = getResources().getQuantityString(R.plurals.minutes_count, mins, mins);
        return secs == 0 ? spokenMinutes : getContext().getString(R.string.minutes_and_seconds, spokenMinutes, spokenSeconds);
    }

    static String fractionText(int millis, int digits, Locale locale) {
        if (digits == 0) return "";
        char[] text = new char[digits + 1];
        fillFraction(text, millis, digits, DecimalFormatSymbols.getInstance(locale));
        return new String(text);
    }

    private static void fillFraction(char[] target, int millis, int digits, DecimalFormatSymbols symbols) {
        target[0] = symbols.getDecimalSeparator();
        int divisor = 100;
        for (int i = 1; i <= digits; i++) {
            target[i] = (char) (symbols.getZeroDigit() + millis / divisor % 10);
            divisor /= 10;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float size = Math.min(340 * density, Math.min(getWidth(), getHeight() - 14 * density));
        if (size <= 0) return;
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(size / 270f, size / 270f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(6.5f);
        paint.setColor(line);
        canvas.drawArc(arc, START, SWEEP, false, paint);
        if (fraction > 0) {
            paint.setColor(accent);
            canvas.drawArc(arc, START, SWEEP * fraction, false, paint);
        }

        canvas.save();
        canvas.rotate(START + 90);
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int tick = 0; tick <= 60; tick++) {
            boolean major = tick % 5 == 0;
            paint.setColor(major ? muted : line);
            paint.setStrokeWidth(major ? 1.4f : 1f);
            canvas.drawLine(0, -116, 0, major ? -107 : -112, paint);
            canvas.rotate(SWEEP / 60);
        }
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(medium);
        paint.setTextSize(10.5f);
        paint.setTextAlign(Paint.Align.LEFT);
        float badgeWidth = paint.measureText(status) + 31;
        paint.setColor(panel);
        canvas.drawRoundRect(-badgeWidth / 2, -49, badgeWidth / 2, -27, 11, 11, paint);
        paint.setColor(accent);
        canvas.drawCircle(-badgeWidth / 2 + 12, -38, 2.6f, paint);
        canvas.drawText(status, -badgeWidth / 2 + 19, -34.4f, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(digits);
        paint.setTextSize(67);
        float wholeWidth = paint.measureText(time);
        paint.setTextSize(34);
        int decimalCount = lastPrecision > 0 ? lastPrecision + 1 : 0;
        float decimalWidth = paint.measureText(decimals, 0, decimalCount);
        float width = wholeWidth + decimalWidth;
        float scale = Math.min(1f, 206f / width);
        float x = -width * scale / 2;
        paint.setTextSize(67 * scale);
        paint.setColor(ink);
        canvas.drawText(time, x, 23, paint);
        if (decimalCount > 0) {
            paint.setTextSize(34 * scale);
            paint.setColor(accent);
            canvas.drawText(decimals, 0, decimalCount, x + wholeWidth * scale, 23, paint);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(medium);
        paint.setTextSize(12.5f);
        paint.setColor(muted);
        canvas.drawText(caption, 0, 48, paint);

        paint.setColor(accent);
        paint.setStrokeWidth(2.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int bar = -2; bar <= 2; bar++) {
            float half = bar == 0 ? 7 : Math.abs(bar) == 1 ? 4.5f : 2;
            canvas.drawLine(bar * 5.2f, 114 - half, bar * 5.2f, 114 + half, paint);
        }
        canvas.restore();
    }
}
