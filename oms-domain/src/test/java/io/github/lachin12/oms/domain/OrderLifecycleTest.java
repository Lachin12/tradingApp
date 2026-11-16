package io.github.lachin12.oms.domain;

import static io.github.lachin12.oms.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

class OrderLifecycleTest {
    @Test
    void submissionCreatesOnlyNewStateWithDeterministicEventIdentity() {
        Order order = limit();

        assertAll(
                () -> assertEquals(ORDER_ID, order.id()),
                () -> assertEquals(ACCOUNT, order.account()),
                () -> assertEquals(INSTRUMENT, order.instrument()),
                () -> assertEquals(Side.BUY, order.side()),
                () -> assertEquals(OrderType.LIMIT, order.type()),
                () -> assertEquals(OrderStatus.NEW, order.status()),
                () -> assertEquals(scaled("0"), order.filledQuantity()),
                () -> assertEquals(scaled("10"), order.remainingQuantity()),
                () -> assertEquals(0, order.revision()),
                () -> assertEquals(1, order.events().size()));
        OrderEvent event = order.events().getFirst();
        assertAll(
                () -> assertEquals(ORDER_ID + ":1", event.id()),
                () -> assertEquals(ORDER_ID, event.orderId()),
                () -> assertEquals(1L, event.sequence()),
                () -> assertEquals(OrderEventType.SUBMITTED, event.type()),
                () -> assertNull(event.previousStatus()),
                () -> assertEquals(OrderStatus.NEW, event.status()),
                () -> assertEquals(T0, event.occurredAt()),
                () -> assertEquals(scaled("0"), event.cumulativeQuantity()),
                () -> assertNull(event.execution()),
                () -> assertNull(event.reason()));
    }

    @Test
    void marketAcceptanceDoesNotExecuteOrInferAnExecutionPrice() {
        Order submitted = market();
        Order accepted = submitted.accept(T0.plusSeconds(1));

        assertEquals(OrderStatus.NEW, submitted.status());
        assertEquals(OrderStatus.ACCEPTED, accepted.status());
        assertTrue(accepted.limitPrice().isEmpty());
        assertEquals(scaled("0"), accepted.filledQuantity());
        assertEquals(scaled("10"), accepted.remainingQuantity());
        assertEquals(List.of(OrderEventType.SUBMITTED, OrderEventType.ACCEPTED),
                accepted.events().stream().map(OrderEvent::type).toList());
        assertTrue(accepted.events().stream().allMatch(event -> event.execution() == null));
    }

    @Test
    void onlyLaterExecutionReportsFillAMarketOrder() {
        Order accepted = market().accept(T0.plusSeconds(1));
        Execution report = execution(10, "10", "101.25", T0.plusSeconds(2));
        Order filled = accepted.fill(report, T0.plusSeconds(3));

        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(scaled("10"), filled.filledQuantity());
        assertEquals(scaled("0"), filled.remainingQuantity());
        assertEquals(report, filled.events().getLast().execution());
    }

    @Test
    void accumulatesMultiplePartialFillsExactlyAndPreservesTheirHistory() {
        Order accepted = limit().accept(T0.plusSeconds(1));
        Execution firstReport = execution(10, "0.1", "99.99", T0.plusSeconds(2));
        Execution secondReport = execution(11, "0.2", "99.98", T0.plusSeconds(3));
        Execution lastReport = execution(12, "9.7", "100.00", T0.plusSeconds(4));

        Order first = accepted.fill(firstReport, T0.plusSeconds(2));
        Order second = first.fill(secondReport, T0.plusSeconds(3));
        Order complete = second.fill(lastReport, T0.plusSeconds(4));

        assertEquals(OrderStatus.PARTIALLY_FILLED, first.status());
        assertEquals(OrderStatus.PARTIALLY_FILLED, second.status());
        assertEquals(OrderStatus.FILLED, complete.status());
        assertEquals(scaled("0.3"), second.filledQuantity());
        assertEquals(scaled("9.7"), second.remainingQuantity());
        assertEquals(scaled("10"), complete.filledQuantity());
        assertEquals(scaled("0"), complete.remainingQuantity());
        assertEquals(List.of(firstReport, secondReport, lastReport), complete.events().stream()
                .filter(event -> event.type() == OrderEventType.EXECUTED).map(OrderEvent::execution).toList());
        assertEquals(List.of(scaled("0"), scaled("0"), scaled("0.1"), scaled("0.3"), scaled("10")),
                complete.events().stream().map(OrderEvent::cumulativeQuantity).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), complete.events().stream().map(OrderEvent::sequence).toList());
        assertEquals(4L, complete.revision());
    }

    @Test
    void immutableSnapshotsAndUnmodifiableHistoryCannotLosePastExecutions() {
        Order accepted = limit().accept(T0.plusSeconds(1));
        List<OrderEvent> originalHistory = accepted.events();
        Order partial = accepted.fill(execution(10, "2", "99.99", T0.plusSeconds(2)), T0.plusSeconds(2));
        Order cancelled = partial.cancel("  User request  ", T0.plusSeconds(3));

        assertEquals(2, originalHistory.size());
        assertEquals(OrderStatus.ACCEPTED, accepted.status());
        assertEquals(scaled("0"), accepted.filledQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(3, partial.events().size());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(scaled("2"), cancelled.filledQuantity());
        assertEquals(scaled("8"), cancelled.remainingQuantity());
        assertEquals(partial.events(), cancelled.events().subList(0, 3));
        assertEquals("User request", cancelled.events().getLast().reason());
        assertThrows(UnsupportedOperationException.class, () -> originalHistory.add(cancelled.events().getLast()));
        assertThrows(UnsupportedOperationException.class, () -> cancelled.events().clear());
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void enforcesSideSpecificLimitPriceAndAllowsPriceImprovement(Side side) {
        Order accepted = limit(side).accept(T0.plusSeconds(1));
        String worse = side == Side.BUY ? "100.01" : "99.99";
        String better = side == Side.BUY ? "99.99" : "100.01";

        assertThrows(IllegalArgumentException.class,
                () -> accepted.fill(execution(10, "1", worse, T0.plusSeconds(2)), T0.plusSeconds(2)));
        Order partial = accepted.fill(execution(11, "1", better, T0.plusSeconds(2)), T0.plusSeconds(2));
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(scaled("1"), partial.filledQuantity());
        assertEquals(OrderStatus.FILLED,
                partial.fill(execution(12, "9", "100", T0.plusSeconds(3)), T0.plusSeconds(3)).status());
    }

    @Test
    void overfillRejectionDoesNotAppendAnEventOrChangeQuantities() {
        Order partial = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "9.9", "100", T0.plusSeconds(2)), T0.plusSeconds(2));

        assertThrows(IllegalArgumentException.class,
                () -> partial.fill(execution(11, "0.2", "100", T0.plusSeconds(3)), T0.plusSeconds(3)));
        assertEquals(scaled("9.9"), partial.filledQuantity());
        assertEquals(scaled("0.1"), partial.remainingQuantity());
        assertEquals(3, partial.events().size());
    }

    @Test
    void duplicateExecutionIdCannotBeAppliedTwiceEvenWithDifferentPayload() {
        Execution report = execution(10, "1", "100", T0.plusSeconds(2));
        Order partial = limit().accept(T0.plusSeconds(1)).fill(report, T0.plusSeconds(2));

        assertThrows(IllegalArgumentException.class, () -> partial.fill(report, T0.plusSeconds(3)));
        assertThrows(IllegalArgumentException.class,
                () -> partial.fill(execution(10, "2", "99", T0.plusSeconds(3)), T0.plusSeconds(3)));
        assertEquals(scaled("1"), partial.filledQuantity());
        assertEquals(3, partial.events().size());
    }

    @Test
    void duplicateReportIsRecognizedAfterFinalFill() {
        Execution report = execution(10, "10", "100", T0.plusSeconds(2));
        Order filled = limit().accept(T0.plusSeconds(1)).fill(report, T0.plusSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> filled.fill(report, T0.plusSeconds(3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.15", "0.00000001"})
    void rejectsExecutionQuantityOffInstrumentStep(String quantity) {
        Order accepted = limit().accept(T0.plusSeconds(1));
        assertThrows(IllegalArgumentException.class,
                () -> accepted.fill(execution(10, quantity, "100", T0.plusSeconds(2)), T0.plusSeconds(2)));
    }

    @Test
    void rejectsExecutionPriceOffInstrumentTick() {
        Order accepted = market().accept(T0.plusSeconds(1));
        assertThrows(IllegalArgumentException.class,
                () -> accepted.fill(execution(10, "1", "100.001", T0.plusSeconds(2)), T0.plusSeconds(2)));
    }

    @Test
    void executionRequiresIdentityTimestampAndPositiveExactValues() {
        assertThrows(IllegalArgumentException.class, () -> new Execution(null, decimal("1"), decimal("100"), T0));
        assertThrows(IllegalArgumentException.class, () -> new Execution(ORDER_ID, null, decimal("100"), T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "0", "100", T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "-1", "100", T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "1", "0", T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "1", "-100", T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "1", "100.000000001", T0));
        assertThrows(IllegalArgumentException.class, () -> execution(10, "1", "100", null));
    }

    @ParameterizedTest(name = "{0}: {1} permitted={2}")
    @MethodSource("commandTransitions")
    void aggregateCommandsEnforceStateMachine(OrderStatus status, String command, boolean permitted) {
        Order order = atState(status);
        if (!permitted) {
            assertThrows(IllegalStateException.class, () -> invoke(order, command));
            return;
        }
        Order result = invoke(order, command);
        OrderStatus expected = switch (command) {
            case "accept" -> OrderStatus.ACCEPTED;
            case "reject" -> OrderStatus.REJECTED;
            case "cancel" -> OrderStatus.CANCELLED;
            case "fill" -> OrderStatus.PARTIALLY_FILLED;
            default -> throw new IllegalArgumentException(command);
        };
        assertEquals(expected, result.status());
        assertEquals(order.events().size() + 1, result.events().size());
        assertEquals(order.status(), result.events().getLast().previousStatus());
    }

    static Stream<Arguments> commandTransitions() {
        return Stream.of(OrderStatus.values()).flatMap(status -> Stream.of("accept", "reject", "cancel", "fill")
                .map(command -> Arguments.of(status, command, switch (command) {
                    case "accept", "reject" -> status == OrderStatus.NEW;
                    case "cancel" -> status == OrderStatus.NEW || status == OrderStatus.ACCEPTED
                            || status == OrderStatus.PARTIALLY_FILLED;
                    case "fill" -> status == OrderStatus.ACCEPTED || status == OrderStatus.PARTIALLY_FILLED;
                    default -> false;
                })));
    }

    @Test
    void cancellationBeforeFillRejectsLaterExecutionInThisLocalSimulation() {
        Order cancelled = limit().accept(T0.plusSeconds(1)).cancel("User request", T0.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> cancelled.fill(execution(10, "10", "100", T0.plusSeconds(3)), T0.plusSeconds(3)));
        assertEquals(scaled("0"), cancelled.filledQuantity());
        assertEquals(3, cancelled.events().size());
    }

    @Test
    void fullFillBeforeCancellationRejectsCancellation() {
        Order filled = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "10", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
        assertThrows(IllegalStateException.class, () -> filled.cancel("User request", T0.plusSeconds(3)));
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(scaled("10"), filled.filledQuantity());
    }

    @Test
    void partialFillBeforeCancellationPreservesTheFill() {
        Order cancelled = limit().accept(T0.plusSeconds(1))
                .fill(execution(10, "2", "100", T0.plusSeconds(2)), T0.plusSeconds(2))
                .cancel("User request", T0.plusSeconds(3));
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(scaled("2"), cancelled.filledQuantity());
        assertEquals(OrderEventType.EXECUTED, cancelled.events().get(2).type());
        assertEquals(scaled("2"), cancelled.events().getLast().cumulativeQuantity());
    }

    @Test
    void rejectsBackwardsEventTimeButAllowsEqualTimestampsWithSequenceOrdering() {
        Order accepted = limit().accept(T0);
        assertThrows(IllegalArgumentException.class, () -> accepted.cancel("User request", T0.minusNanos(1)));
        Order cancelled = accepted.cancel("User request", T0);
        assertEquals(List.of(1L, 2L, 3L), cancelled.events().stream().map(OrderEvent::sequence).toList());
        assertTrue(cancelled.events().stream().allMatch(event -> event.occurredAt().equals(T0)));
    }

    @Test
    void cannotRecordAnExecutionBeforeItsExecutionTimestamp() {
        Order accepted = limit().accept(T0.plusSeconds(1));
        assertThrows(IllegalArgumentException.class,
                () -> accepted.fill(execution(10, "1", "100", T0.plusSeconds(3)), T0.plusSeconds(2)));
    }

    @Test
    void executionTimeCanPrecedeReceiptAndAcceptanceTime() {
        Order accepted = limit().accept(T0.plusSeconds(1));
        Order partial = accepted.fill(execution(10, "1", "100", T0), T0.plusSeconds(2));
        assertEquals(T0, partial.events().getLast().execution().executedAt());
        assertEquals(T0.plusSeconds(2), partial.events().getLast().occurredAt());
    }

    @Test
    void commandsRequireTimestampAndExecutionReport() {
        assertThrows(IllegalArgumentException.class, () -> limit().accept(null));
        assertThrows(IllegalArgumentException.class, () -> limit().cancel("User request", null));
        assertThrows(IllegalArgumentException.class, () -> limit().reject("Risk check", null));
        Order accepted = limit().accept(T0);
        assertThrows(IllegalArgumentException.class, () -> accepted.fill(null, T0));
        assertThrows(IllegalArgumentException.class, () -> accepted.fill(execution(10, "1", "100", T0), null));
    }

    private static Order atState(OrderStatus status) {
        Order submitted = limit();
        return switch (status) {
            case NEW -> submitted;
            case ACCEPTED -> submitted.accept(T0.plusSeconds(1));
            case PARTIALLY_FILLED -> submitted.accept(T0.plusSeconds(1))
                    .fill(execution(10, "1", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
            case FILLED -> submitted.accept(T0.plusSeconds(1))
                    .fill(execution(10, "10", "100", T0.plusSeconds(2)), T0.plusSeconds(2));
            case CANCELLED -> submitted.accept(T0.plusSeconds(1)).cancel("User request", T0.plusSeconds(2));
            case REJECTED -> submitted.reject("Risk check", T0.plusSeconds(1));
        };
    }

    private static Order invoke(Order order, String command) {
        Instant commandTime = T0.plusSeconds(10);
        return switch (command) {
            case "accept" -> order.accept(commandTime);
            case "reject" -> order.reject("Risk check", commandTime);
            case "cancel" -> order.cancel("User request", commandTime);
            case "fill" -> order.fill(execution(100, "0.1", "100", commandTime), commandTime);
            default -> throw new IllegalArgumentException(command);
        };
    }
}
