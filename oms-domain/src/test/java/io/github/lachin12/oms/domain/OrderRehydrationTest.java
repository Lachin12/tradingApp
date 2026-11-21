package io.github.lachin12.oms.domain;

import static io.github.lachin12.oms.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OrderRehydrationTest {
    @ParameterizedTest
    @MethodSource("validSnapshots")
    void replaysValidHistoryThroughSameLifecycleRules(Order original) {
        Order restored = restore(original, original.events());

        assertEquals(original.id(), restored.id());
        assertEquals(original.account(), restored.account());
        assertEquals(original.instrument(), restored.instrument());
        assertEquals(original.side(), restored.side());
        assertEquals(original.type(), restored.type());
        assertEquals(original.quantity(), restored.quantity());
        assertEquals(original.limitPrice(), restored.limitPrice());
        assertEquals(original.status(), restored.status());
        assertEquals(original.filledQuantity(), restored.filledQuantity());
        assertEquals(original.remainingQuantity(), restored.remainingQuantity());
        assertEquals(original.revision(), restored.revision());
        assertEquals(original.events(), restored.events());
    }

    static Stream<Order> validSnapshots() {
        Order submitted = limit();
        Order accepted = submitted.accept(T0.plusSeconds(1));
        Order partial = accepted.fill(execution(10, "2", "99.99", T0.plusSeconds(2)), T0.plusSeconds(2));
        return Stream.of(submitted, accepted, partial,
                partial.fill(execution(11, "8", "100", T0.plusSeconds(3)), T0.plusSeconds(3)),
                partial.cancel("User request", T0.plusSeconds(3)),
                submitted.reject("Risk check", T0.plusSeconds(1)),
                market().accept(T0.plusSeconds(1)));
    }

    @Test
    void restoredHistoryOwnsAnImmutableSnapshotOfInputFacts() {
        Order original = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "2", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
        List<OrderEvent> input = new ArrayList<>(original.events());
        Order restored = restore(original, input);

        input.clear();

        assertEquals(original.events(), restored.events());
        assertEquals(ACCOUNT, restored.account());
        assertEquals(INSTRUMENT, restored.instrument());
        assertThrows(UnsupportedOperationException.class, () -> restored.events().clear());
        Order cancelled = restored.cancel("User request", T0.plusSeconds(3));
        assertEquals(3, restored.events().size());
        assertEquals(4, cancelled.events().size());
    }

    @Test
    void rejectsAbsentHistory() {
        assertThrows(IllegalArgumentException.class, () -> restore(limit(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> restore(limit(), null));
    }

    @Test
    void rejectsSequenceGap() {
        Order partial = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "1", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
        OrderEvent gap = new OrderEvent(ORDER_ID, 5, OrderEventType.EXECUTED,
                OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED, T0.plusSeconds(3), decimal("2"),
                execution(11, "1", "100", T0.plusSeconds(3)), null);
        List<OrderEvent> history = new ArrayList<>(partial.events());
        history.add(gap);

        assertThrows(IllegalArgumentException.class, () -> restore(partial, history));
    }

    @Test
    void rejectsHistoryFromDifferentOrder() {
        Order original = limit().accept(T0.plusSeconds(1));
        OrderEvent accepted = original.events().getLast();
        OrderEvent foreign = new OrderEvent(new UUID(0, 999), accepted.sequence(), accepted.type(),
                accepted.previousStatus(), accepted.status(), accepted.occurredAt(), accepted.cumulativeQuantity(), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> restore(original, List.of(original.events().getFirst(), foreign)));
    }

    @Test
    void rejectsCorruptCumulativeQuantityEvenWhenEventShapeIsLegal() {
        Order partial = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "1", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
        OrderEvent corrupt = new OrderEvent(ORDER_ID, 4, OrderEventType.EXECUTED,
                OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED, T0.plusSeconds(3), decimal("3"),
                execution(11, "1", "100", T0.plusSeconds(3)), null);
        List<OrderEvent> history = new ArrayList<>(partial.events());
        history.add(corrupt);

        assertThrows(IllegalArgumentException.class, () -> restore(partial, history));
    }

    @Test
    void rejectsRepeatedSubmission() {
        Order original = limit().accept(T0.plusSeconds(1));
        List<OrderEvent> history = new ArrayList<>(original.events());
        history.add(original.events().getFirst());

        assertThrows(IllegalArgumentException.class, () -> restore(original, history));
    }

    @Test
    void rejectsDuplicateExecutionIdentityWithinPersistedHistory() {
        Execution report = execution(10, "1", "100", T0.plusSeconds(2));
        Order partial = limit().accept(T0.plusSeconds(1)).fill(report, T0.plusSeconds(2));
        OrderEvent duplicate = new OrderEvent(ORDER_ID, 4, OrderEventType.EXECUTED,
                OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED, T0.plusSeconds(3), decimal("2"), report, null);
        List<OrderEvent> history = new ArrayList<>(partial.events());
        history.add(duplicate);

        assertThrows(IllegalArgumentException.class, () -> restore(partial, history));
    }

    @Test
    void rejectsBackwardsRecordedTimeInPersistedHistory() {
        Order submitted = limit();
        OrderEvent earlierAcceptance = new OrderEvent(ORDER_ID, 2, OrderEventType.ACCEPTED,
                OrderStatus.NEW, OrderStatus.ACCEPTED, T0.minusSeconds(1), decimal("0"), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> restore(submitted, List.of(submitted.events().getFirst(), earlierAcceptance)));
    }

    private static Order restore(Order terms, List<OrderEvent> history) {
        return Order.rehydrate(terms.id(), terms.account(), terms.instrument(), terms.side(), terms.type(),
                terms.quantity(), terms.limitPrice().orElse(null), history);
    }
}
