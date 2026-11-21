package io.github.lachin12.oms.domain;

import static io.github.lachin12.oms.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderEventTest {
    @Test
    void cancellationQuantityMustAgreeWithPreviousState() {
        for (OrderStatus status : new OrderStatus[]{OrderStatus.NEW, OrderStatus.ACCEPTED}) {
            long sequence = status == OrderStatus.NEW ? 2 : 3;
            assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, sequence,
                    OrderEventType.CANCELLED, status, OrderStatus.CANCELLED, T0,
                    decimal("1"), null, "User request"));
        }
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 4,
                OrderEventType.CANCELLED, OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCELLED, T0,
                decimal("0"), null, "User request"));
    }

    @Test
    void firstExecutionIsEntireCumulativeQuantityAndSubsequentExecutionAddsToIt() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 3,
                OrderEventType.EXECUTED, OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED, T0,
                decimal("2"), execution(10, "1", "100", T0), null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 4,
                OrderEventType.EXECUTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, T0,
                decimal("1"), execution(10, "1", "100", T0), null));
    }

    @Test
    void sequenceMustBePossibleForPreviousState() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 100,
                OrderEventType.ACCEPTED, OrderStatus.NEW, OrderStatus.ACCEPTED, T0,
                decimal("0"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 2,
                OrderEventType.EXECUTED, OrderStatus.ACCEPTED, OrderStatus.FILLED, T0,
                decimal("1"), execution(10, "1", "100", T0), null));
    }
    @Test
    void submittedFactCannotContainFillsOrAnEarlierState() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 1,
                OrderEventType.SUBMITTED, null, OrderStatus.NEW, T0, decimal("1"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 1,
                OrderEventType.SUBMITTED, OrderStatus.ACCEPTED, OrderStatus.NEW, T0, decimal("0"), null, null));
    }

    @Test
    void executionFactRequiresAReportAndLegalExecutionTransition() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 2,
                OrderEventType.EXECUTED, OrderStatus.ACCEPTED, OrderStatus.FILLED, T0,
                decimal("1"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 2,
                OrderEventType.EXECUTED, OrderStatus.NEW, OrderStatus.FILLED, T0,
                decimal("1"), execution(10, "1", "100", T0), null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 3,
                OrderEventType.EXECUTED, OrderStatus.CANCELLED, OrderStatus.FILLED, T0,
                decimal("1"), execution(10, "1", "100", T0), null));
    }

    @Test
    void factCannotClaimLessCumulativeQuantityThanItsOwnExecution() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 3,
                OrderEventType.EXECUTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED, T0,
                decimal("1"), execution(10, "2", "100", T0), null));
    }

    @Test
    void cancellationFactCannotRewriteFilledHistory() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 4,
                OrderEventType.CANCELLED, OrderStatus.FILLED, OrderStatus.CANCELLED, T0,
                decimal("10"), null, "User request"));
    }

    @Test
    void stableFactIdentityDependsOnlyOnOrderAndSequence() {
        OrderEvent first = new OrderEvent(ORDER_ID, 2, OrderEventType.ACCEPTED,
                OrderStatus.NEW, OrderStatus.ACCEPTED, T0, decimal("0"), null, null);
        OrderEvent second = new OrderEvent(ORDER_ID, 2, OrderEventType.ACCEPTED,
                OrderStatus.NEW, OrderStatus.ACCEPTED, T0, decimal("0.00000000"), null, null);
        assertEquals(ORDER_ID + ":2", first.id());
        assertEquals(first.id(), second.id());
        assertEquals(first, second);
    }

    @Test
    void eventSequenceCannotBeZeroAndOnlySubmittedCanHaveSequenceOne() {
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 0,
                OrderEventType.SUBMITTED, null, OrderStatus.NEW, T0, decimal("0"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderEvent(ORDER_ID, 1,
                OrderEventType.ACCEPTED, OrderStatus.NEW, OrderStatus.ACCEPTED, T0, decimal("0"), null, null));
    }
}
