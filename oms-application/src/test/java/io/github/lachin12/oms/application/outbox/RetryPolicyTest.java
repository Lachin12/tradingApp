package io.github.lachin12.oms.application.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {
    @Test
    void doublesFromFirstAttemptAndSaturatesAtNonPowerOfTwoCap() {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(100), Duration.ofMillis(750));

        assertEquals(Duration.ofMillis(100), policy.delayFor(1));
        assertEquals(Duration.ofMillis(200), policy.delayFor(2));
        assertEquals(Duration.ofMillis(400), policy.delayFor(3));
        assertEquals(Duration.ofMillis(750), policy.delayFor(4));
        assertEquals(Duration.ofMillis(750), policy.delayFor(5));
    }

    @Test
    void hugeAttemptUsesBoundedWorkAndCannotOverflow() {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(1), Duration.ofHours(1));

        assertTimeout(Duration.ofSeconds(1),
                () -> assertEquals(Duration.ofHours(1), policy.delayFor(Integer.MAX_VALUE)));
        assertEquals(Duration.ofMillis(2_097_152), policy.delayFor(22));
        assertEquals(Duration.ofHours(1), policy.delayFor(23));
    }

    @Test
    void equalInitialAndMaximumRemainConstant() {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(1));
        assertEquals(Duration.ofMillis(1), policy.delayFor(1));
        assertEquals(Duration.ofMillis(1), policy.delayFor(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void attemptsStartAtOne(int attempt) {
        RetryPolicy policy = new RetryPolicy(Duration.ofMillis(1), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> policy.delayFor(attempt));
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsInvalidBounds(Duration invalid) {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(invalid, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofMillis(1), invalid));
    }

    static Stream<Duration> invalidDurations() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1),
                Duration.ofMillis(1).plusNanos(1), Duration.ofHours(1).plusMillis(1), Duration.ofSeconds(Long.MAX_VALUE));
    }

    @Test
    void rejectsInitialGreaterThanMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(Duration.ofMillis(2), Duration.ofMillis(1)));
    }
}
