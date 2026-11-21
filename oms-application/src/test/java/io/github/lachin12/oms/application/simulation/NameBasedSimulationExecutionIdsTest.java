package io.github.lachin12.oms.application.simulation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lachin12.oms.application.InvalidOrderRequestException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NameBasedSimulationExecutionIdsTest {
    private static final UUID SCENARIO = new UUID(0, 1);
    private static final UUID ORDER = new UUID(0, 2);

    @Test
    void identityIsStableAcrossRepeatedCallsAndFreshInstances() {
        NameBasedSimulationExecutionIds first = new NameBasedSimulationExecutionIds();
        UUID expected = UUID.fromString("2069d500-be1a-3d00-b72b-9500782ac009");

        assertEquals(expected, first.forStep(SCENARIO, ORDER, 0));
        assertEquals(expected, first.forStep(SCENARIO, ORDER, 0));
        assertEquals(expected, new NameBasedSimulationExecutionIds().forStep(SCENARIO, ORDER, 0));
        assertEquals(3, expected.version());
    }

    @Test
    void changingScenarioOrderOrStepChangesIdentity() {
        NameBasedSimulationExecutionIds ids = new NameBasedSimulationExecutionIds();
        assertEquals(4, Set.of(ids.forStep(SCENARIO, ORDER, 0),
                ids.forStep(new UUID(0, 3), ORDER, 0),
                ids.forStep(SCENARIO, new UUID(0, 3), 0),
                ids.forStep(SCENARIO, ORDER, 1)).size());
    }

    @Test
    void directGeneratorCallsValidateIdentityInputs() {
        NameBasedSimulationExecutionIds ids = new NameBasedSimulationExecutionIds();
        assertThrows(InvalidOrderRequestException.class, () -> ids.forStep(null, ORDER, 0));
        assertThrows(InvalidOrderRequestException.class, () -> ids.forStep(SCENARIO, null, 0));
        assertThrows(InvalidOrderRequestException.class, () -> ids.forStep(SCENARIO, ORDER, -1));
        assertThrows(InvalidOrderRequestException.class, () -> ids.forStep(SCENARIO, ORDER, 1000));
    }
}
