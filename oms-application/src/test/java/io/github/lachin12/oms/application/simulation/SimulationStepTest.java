package io.github.lachin12.oms.application.simulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lachin12.oms.application.InvalidOrderRequestException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

class SimulationStepTest {
    private static final UUID SCENARIO = new UUID(0, 1);
    private static final Instant EXECUTED_AT = Instant.parse("2026-01-01T12:00:00.123456789Z");

    @Test
    void canonicalizesExactDecimalsAndTruncatesOnlySubmicrosecondTimestamp() {
        SimulationStep step = new SimulationStep(SCENARIO, 999, 0,
                new BigDecimal("1.000000000"), new BigDecimal("100.250000000"), EXECUTED_AT);

        assertEquals(new BigDecimal("1.00000000"), step.quantity());
        assertEquals(new BigDecimal("100.25000000"), step.price());
        assertEquals(Instant.parse("2026-01-01T12:00:00.123456Z"), step.executedAt());
        assertEquals(SCENARIO, step.scenarioId());
        assertEquals(999, step.step());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.000000001", "100000000000000000000"})
    void rejectsInvalidPricesAndQuantitiesWithoutRounding(String value) {
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, new BigDecimal(value), BigDecimal.ONE, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, new BigDecimal(value), EXECUTED_AT));
    }

    @Test
    void rejectsMissingFieldsNegativeVersionAndOutOfRangeStep() {
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(null, 0, 0, BigDecimal.ONE, BigDecimal.ONE, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, null, BigDecimal.ONE, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, null, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, BigDecimal.ONE, null));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, -1, 0, BigDecimal.ONE, BigDecimal.ONE, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 1000, 0, BigDecimal.ONE, BigDecimal.ONE, EXECUTED_AT));
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, -1, BigDecimal.ONE, BigDecimal.ONE, EXECUTED_AT));
    }

    @ParameterizedTest
    @MethodSource("outOfRangeTimes")
    void rejectsTimestampsOutsideTradingRange(Instant invalid) {
        assertThrows(InvalidOrderRequestException.class,
                () -> new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, BigDecimal.ONE, invalid));
    }

    static Stream<Instant> outOfRangeTimes() {
        return Stream.of(Instant.MIN, Instant.MAX, Instant.parse("0000-12-31T23:59:59Z"),
                Instant.parse("+300000-01-01T00:00:00Z"));
    }

    @Test
    void acceptsInclusiveYearBoundsAndCanonicalizesLastMicrosecondOfYear9999() {
        SimulationStep earliest = new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, BigDecimal.ONE,
                Instant.parse("0001-01-01T00:00:00Z"));
        SimulationStep latest = new SimulationStep(SCENARIO, 0, 0, BigDecimal.ONE, BigDecimal.ONE,
                Instant.parse("9999-12-31T23:59:59.999999999Z"));

        assertEquals(Instant.parse("0001-01-01T00:00:00Z"), earliest.executedAt());
        assertEquals(Instant.parse("9999-12-31T23:59:59.999999Z"), latest.executedAt());
    }
}
