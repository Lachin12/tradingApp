package io.github.lachin12.oms.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Explicit trading timestamp range, narrower than Java Instant and PostgreSQL's range. */
public final class ExecutionTimestamps {
    private static final Instant MINIMUM = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAXIMUM_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    private ExecutionTimestamps() { }

    public static Instant normalize(Instant value) {
        if (value == null || value.isBefore(MINIMUM) || !value.isBefore(MAXIMUM_EXCLUSIVE)) {
            throw new InvalidOrderRequestException("Execution timestamp must use a UTC year from 0001 through 9999");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
