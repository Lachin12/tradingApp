package io.github.lachin12.oms.application.outbox;

import java.time.Duration;

final class OutboxDurations {
    private OutboxDurations() { }

    static Duration wholeMilliseconds(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0
                || value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(name + " must be whole milliseconds in " + minimum + ".." + maximum);
        }
        return value;
    }
}
