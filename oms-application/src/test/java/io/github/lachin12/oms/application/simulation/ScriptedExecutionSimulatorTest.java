package io.github.lachin12.oms.application.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.lachin12.oms.application.ExecutionConflictException;
import io.github.lachin12.oms.application.ExecutionResult;
import io.github.lachin12.oms.application.InvalidOrderRequestException;
import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScriptedExecutionSimulatorTest {
    private static final UUID ORDER_ID = new UUID(0, 1);
    private static final UUID SCENARIO = new UUID(0, 2);
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    @Mock private OmsService orders;
    private final NameBasedSimulationExecutionIds ids = new NameBasedSimulationExecutionIds();

    @Test
    void explicitPartialAndFullStepsForwardSuppliedPricesQuantitiesTimesAndVersions() {
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, ids);
        SimulationStep first = step(0, 0, "3", "101.25", START.plusSeconds(1));
        SimulationStep second = step(1, 1, "7", "102.50", START.plusSeconds(2));
        Execution firstExecution = execution(first);
        Execution secondExecution = execution(second);
        Order accepted = marketOrder();
        Order partial = accepted.fill(firstExecution, first.executedAt());
        Order filled = partial.fill(secondExecution, second.executedAt());
        when(orders.reportExecution(ORDER_ID, 0, firstExecution)).thenReturn(new ExecutionResult(new StoredOrder(partial, 1), false));
        when(orders.reportExecution(ORDER_ID, 1, secondExecution)).thenReturn(new ExecutionResult(new StoredOrder(filled, 2), false));

        SimulationResult firstResult = simulator.execute(ORDER_ID, first);
        SimulationResult secondResult = simulator.execute(ORDER_ID, second);

        assertEquals(OrderStatus.PARTIALLY_FILLED, firstResult.storedOrder().order().status());
        assertEquals(OrderStatus.FILLED, secondResult.storedOrder().order().status());
        assertEquals(firstExecution.id(), firstResult.executionId());
        assertEquals(secondExecution.id(), secondResult.executionId());
        assertFalse(firstResult.replay());
        assertFalse(secondResult.replay());
        verify(orders).reportExecution(ORDER_ID, 0, firstExecution);
        verify(orders).reportExecution(ORDER_ID, 1, secondExecution);
        verifyNoMoreInteractions(orders);
        assertEquals(OrderStatus.ACCEPTED, accepted.status());
        assertEquals(new BigDecimal("0.00000000"), accepted.filledQuantity());
        assertTrue(accepted.limitPrice().isEmpty());
    }

    @Test
    void replayIsReportedByCoreAndKeepsIdentityIndependentOfExpectedVersion() {
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, ids);
        SimulationStep retry = step(0, 99, "3", "101.25", START.plusSeconds(1));
        Execution report = execution(retry);
        StoredOrder current = new StoredOrder(marketOrder().fill(report, retry.executedAt()), 1);
        when(orders.reportExecution(ORDER_ID, 99, report)).thenReturn(new ExecutionResult(current, true));

        SimulationResult result = simulator.execute(ORDER_ID, retry);

        assertTrue(result.replay());
        assertSame(current, result.storedOrder());
        assertEquals(ids.forStep(SCENARIO, ORDER_ID, 0), result.executionId());
    }

    @Test
    void payloadChangeDoesNotGenerateNewIdentityAndCoreConflictPropagates() {
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, ids);
        SimulationStep changed = step(0, 1, "4", "101.25", START.plusSeconds(1));
        ExecutionConflictException conflict = new ExecutionConflictException();
        when(orders.reportExecution(ORDER_ID, 1, execution(changed))).thenThrow(conflict);

        assertSame(conflict, assertThrows(ExecutionConflictException.class, () -> simulator.execute(ORDER_ID, changed)));
        assertEquals(execution(step(0, 0, "3", "100", START)).id(), execution(changed).id());
    }

    @Test
    void coreRetainsAuthorityOverFutureExecutionTimestamps() {
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, ids);
        SimulationStep future = step(0, 0, "1", "100", Instant.parse("2080-01-01T00:00:00Z"));
        InvalidOrderRequestException invalid = new InvalidOrderRequestException("Execution timestamp cannot be in the future");
        when(orders.reportExecution(ORDER_ID, 0, execution(future))).thenThrow(invalid);

        assertSame(invalid, assertThrows(InvalidOrderRequestException.class, () -> simulator.execute(ORDER_ID, future)));
    }

    @Test
    void missingInputsFailBeforeGeneratingIdentityOrCallingCore() {
        SimulationExecutionIds generator = mock(SimulationExecutionIds.class);
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, generator);

        assertThrows(InvalidOrderRequestException.class,
                () -> simulator.execute(null, step(0, 0, "1", "100", START)));
        assertThrows(InvalidOrderRequestException.class, () -> simulator.execute(ORDER_ID, null));

        verifyNoInteractions(generator, orders);
    }

    @Test
    void identityGeneratorFailurePropagatesUnchanged() {
        IllegalStateException internal = new IllegalStateException("Identity provider unavailable");
        ScriptedExecutionSimulator simulator = new ScriptedExecutionSimulator(orders, (scenario, order, step) -> {
            throw internal;
        });

        assertSame(internal, assertThrows(IllegalStateException.class,
                () -> simulator.execute(ORDER_ID, step(0, 0, "1", "100", START))));
        verifyNoInteractions(orders);
    }

    private Execution execution(SimulationStep step) {
        return new Execution(ids.forStep(step.scenarioId(), ORDER_ID, step.step()),
                step.quantity(), step.price(), step.executedAt());
    }

    private static SimulationStep step(int step, long version, String quantity, String price, Instant at) {
        return new SimulationStep(SCENARIO, step, version, new BigDecimal(quantity), new BigDecimal(price), at);
    }

    private static Order marketOrder() {
        Account account = new Account(new UUID(0, 3), "Simulation account", true);
        Instrument instrument = new Instrument(new UUID(0, 4), "ACME", Currency.getInstance("USD"),
                new BigDecimal("0.01"), new BigDecimal("0.1"));
        return Order.submit(ORDER_ID, account, instrument, Side.BUY, OrderType.MARKET,
                new BigDecimal("10"), null, START).accept(START);
    }
}
