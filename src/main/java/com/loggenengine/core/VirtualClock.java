package com.loggenengine.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manages virtual (simulated) time for the simulation engine.
 * Advances monotonically in fixed tick increments.
 */
public class VirtualClock {

    private Instant currentTime;
    private final long tickSizeSeconds;

    public VirtualClock(Instant startTime, long tickSizeSeconds) {
        this.currentTime = startTime;
        this.tickSizeSeconds = tickSizeSeconds;
    }

    public Instant now() {
        return currentTime;
    }

    public void advance() {
        currentTime = currentTime.plusSeconds(tickSizeSeconds);
    }

    /**
     * Time-of-day traffic multiplier.
     * Peaks at 9am (0.9), noon (0.7), 6pm (0.6). Valley at 3am (0.1).
     * Formula uses sum of Gaussians centered on peak hours.
     */
    public double todMultiplier() {
        double hour = (currentTime.getEpochSecond() % 86400L) / 3600.0;

        double morning = 0.9 * Math.exp(-0.5 * Math.pow((hour - 9.0) / 2.0, 2));
        double noon    = 0.7 * Math.exp(-0.5 * Math.pow((hour - 12.0) / 1.5, 2));
        double evening = 0.6 * Math.exp(-0.5 * Math.pow((hour - 18.0) / 2.0, 2));

        double raw = morning + noon + evening;
        // Clamp: minimum 0.1 (3am valley), maximum 1.0
        return Math.min(1.0, Math.max(0.1, raw));
    }

    /**
     * Add a small jitter (0..maxJitterMs) to the current time without
     * advancing the main clock. Used to spread events within a tick.
     */
    public Instant withJitter(long jitterMs) {
        return currentTime.plus(jitterMs, ChronoUnit.MILLIS);
    }
}
