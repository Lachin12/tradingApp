package io.github.lachin12.oms.application.outbox;

import java.time.Duration;

/** Bounded exponential delay: the first failed attempt uses initialDelay. No jitter is added here. */
public record RetryPolicy(Duration initialDelay, Duration maxDelay) {
    public RetryPolicy {
        initialDelay = OutboxDurations.wholeMilliseconds(initialDelay, Duration.ofMillis(1),
                Duration.ofHours(1), "Initial delay");
        maxDelay = OutboxDurations.wholeMilliseconds(maxDelay, Duration.ofMillis(1),
                Duration.ofHours(1), "Maximum delay");
        if (initialDelay.compareTo(maxDelay) > 0) {
            throw new IllegalArgumentException("Initial delay cannot exceed maximum delay");
        }
    }

    public Duration delayFor(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive");
        long delay = initialDelay.toMillis();
        long cap = maxDelay.toMillis();
        // At most 22 doublings are possible within the allowed range, even for Integer.MAX_VALUE.
        for (int step = 1; step < attempt && delay < cap; step++) {
            delay = delay > cap / 2 ? cap : delay * 2;
        }
        return Duration.ofMillis(delay);
    }
}
