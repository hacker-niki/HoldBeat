package com.tonechronometer.app;

/** Indexes the work/rest boundaries from the start of the first set, in elapsed milliseconds. */
final class SetSequence {
    final long workMs;
    final long restMs;
    final int count;
    final long totalMs;
    final int endStep;

    SetSequence(long workMs, long restMs, int count) {
        if ((restMs != 0 && restMs < 1_000) || restMs > 5_999_000 || count < 1 || count > 99) {
            throw new IllegalArgumentException("Rest must be 0 or 1 second to 99:59; choose 1 to 99 sets");
        }
        this.workMs = workMs;
        this.restMs = restMs;
        this.count = count;
        totalMs = workMs * count + restMs * (count - 1);
        endStep = restMs == 0 ? count : 2 * count - 1;
    }

    int stepAt(long elapsed) {
        if (elapsed < 0) return -1; // Preparation before the first work interval.
        if (elapsed >= totalMs) return endStep;
        if (restMs == 0) return (int) (elapsed / workMs);
        long cycle = workMs + restMs;
        return (int) (elapsed / cycle) * 2 + (elapsed % cycle < workMs ? 0 : 1);
    }

    long startOf(int step) {
        if (step == endStep) return totalMs;
        if (restMs == 0) return step * workMs;
        return (step / 2) * (workMs + restMs) + (isRest(step) ? workMs : 0);
    }

    boolean isRest(int step) {
        return restMs > 0 && step >= 0 && step < endStep && step % 2 == 1;
    }

    int setNumber(int step) {
        if (step < 0) return 1;
        if (step >= endStep) return count;
        return (restMs == 0 ? step : step / 2) + 1;
    }
}
