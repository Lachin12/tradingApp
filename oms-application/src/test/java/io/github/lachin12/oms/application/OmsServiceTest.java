package io.github.lachin12.oms.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OmsServiceTest {
    private static final UUID ACCOUNT_ID = new UUID(0, 1);
    private static final UUID INSTRUMENT_ID = new UUID(0, 2);
    private static final UUID ORDER_ID = new UUID(0, 3);
    private static final UUID EXECUTION_ID = new UUID(0, 4);
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00.123456789Z");
    private static final Instant CANONICAL_NOW = NOW.truncatedTo(ChronoUnit.MICROS);
    private static final Account ACCOUNT = new Account(ACCOUNT_ID, "Simulation account", true);
    private static final Instrument INSTRUMENT = new Instrument(INSTRUMENT_ID, "ACME", Currency.getInstance("USD"),
            decimal("0.01"), decimal("0.1"));

    @Mock private ReferenceData references;
    @Mock private OrderStore orders;
    @Mock private SubmissionStore submissions;
    @Mock private ExecutionStore executions;
    private final RecordingUnitOfWork transactions = new RecordingUnitOfWork();
    private final MutableClock clock = new MutableClock(NOW);
    private final AtomicInteger generatedIds = new AtomicInteger();
    private OmsService service;

    @BeforeEach
    void createService() {
        service = new OmsService(references, orders, submissions, executions, transactions, clock, () -> {
            generatedIds.incrementAndGet();
            return ORDER_ID;
        });
    }

    @Test
    void submissionUsesInjectedIdentityAndMicrosecondClockInsideOneTransaction() {
        SubmitOrder request = marketRequest();
        when(submissions.claim(ACCOUNT_ID, "request-1", RequestFingerprint.of(request), ORDER_ID)).thenAnswer(call -> {
            assertTrue(transactions.active);
            return new SubmissionClaim(ORDER_ID, call.getArgument(2), true);
        });
        when(references.findAccount(ACCOUNT_ID)).thenAnswer(call -> {
            assertTrue(transactions.active);
            return Optional.of(ACCOUNT);
        });
        when(references.findInstrument(INSTRUMENT_ID)).thenAnswer(call -> {
            assertTrue(transactions.active);
            return Optional.of(INSTRUMENT);
        });
        when(orders.insert(any(Order.class))).thenAnswer(call -> {
            assertTrue(transactions.active);
            return new StoredOrder(call.getArgument(0), 0);
        });

        SubmissionResult result = service.submit(request, "request-1");

        Order accepted = result.storedOrder().order();
        assertFalse(result.replay());
        assertEquals(ORDER_ID, accepted.id());
        assertEquals(OrderStatus.ACCEPTED, accepted.status());
        assertEquals(decimal("0.00000000"), accepted.filledQuantity());
        assertEquals(decimal("10.00000000"), accepted.remainingQuantity());
        assertTrue(accepted.limitPrice().isEmpty());
        assertEquals(2, accepted.events().size());
        assertTrue(accepted.events().stream().allMatch(event -> event.occurredAt().equals(CANONICAL_NOW)));
        assertTrue(accepted.events().stream().allMatch(event -> event.execution() == null));
        assertEquals(1, generatedIds.get());
        assertEquals(1, transactions.executions);
        assertFalse(transactions.active);
    }

    @Test
    void retryWithEquivalentDecimalsReturnsCurrentSnapshotWithoutNewValidationOrInsert() {
        SubmitOrder original = limitRequest("10", "100");
        SubmitOrder retry = limitRequest("10.000000000", "100.000000000");
        Order cancelled = acceptedOrder().cancel("User request", CANONICAL_NOW.plusSeconds(1));
        StoredOrder current = new StoredOrder(cancelled, 7);
        when(submissions.claim(ACCOUNT_ID, "retry", RequestFingerprint.of(retry), ORDER_ID))
                .thenReturn(new SubmissionClaim(ORDER_ID, RequestFingerprint.of(original), false));
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(current));

        SubmissionResult result = service.submit(retry, "retry");

        assertTrue(result.replay());
        assertSame(current, result.storedOrder());
        assertEquals(OrderStatus.CANCELLED, result.storedOrder().order().status());
        verifyNoInteractions(references);
        verify(orders, never()).insert(any());
        verify(orders, never()).update(any(), anyLong());
        assertEquals(1, transactions.executions);
    }

    @Test
    void sameKeyWithDifferentPayloadConflictsWithoutReadingOrWritingOrder() {
        SubmitOrder original = limitRequest("10", "100");
        SubmitOrder changed = limitRequest("20", "100");
        when(submissions.claim(ACCOUNT_ID, "key", RequestFingerprint.of(changed), ORDER_ID))
                .thenReturn(new SubmissionClaim(ORDER_ID, RequestFingerprint.of(original), false));

        assertThrows(IdempotencyConflictException.class, () -> service.submit(changed, "key"));

        verifyNoInteractions(references, orders);
        assertFalse(transactions.active);
    }

    @Test
    void idempotencyKeyIsScopedToAccount() {
        UUID anotherAccount = new UUID(0, 99);
        UUID anotherOrder = new UUID(0, 100);
        SubmitOrder first = marketRequest();
        SubmitOrder second = new SubmitOrder(anotherAccount, INSTRUMENT_ID, Side.BUY, OrderType.MARKET,
                decimal("10"), null);
        StoredOrder stored = new StoredOrder(acceptedOrder(), 0);
        Order secondOrder = Order.submit(anotherOrder, new Account(anotherAccount, "Second account", true),
                INSTRUMENT, Side.BUY, OrderType.MARKET, decimal("10"), null, CANONICAL_NOW).accept(CANONICAL_NOW);
        when(submissions.claim(eq(ACCOUNT_ID), eq("same-key"), anyString(), eq(ORDER_ID)))
                .thenReturn(new SubmissionClaim(ORDER_ID, RequestFingerprint.of(first), false));
        when(submissions.claim(eq(anotherAccount), eq("same-key"), anyString(), eq(ORDER_ID)))
                .thenReturn(new SubmissionClaim(anotherOrder, RequestFingerprint.of(second), false));
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(stored));
        when(orders.find(anotherOrder)).thenReturn(Optional.of(new StoredOrder(secondOrder, 0)));

        assertTrue(service.submit(first, "same-key").replay());
        SubmissionResult secondResult = service.submit(second, "same-key");
        assertTrue(secondResult.replay());
        assertEquals(anotherAccount, secondResult.storedOrder().order().account().id());

        verify(submissions).claim(ACCOUNT_ID, "same-key", RequestFingerprint.of(first), ORDER_ID);
        verify(submissions).claim(anotherAccount, "same-key", RequestFingerprint.of(second), ORDER_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "two words", "\t", "\n", "é", "key\u007f"})
    void invalidKeysFailBeforeOpeningTransaction(String key) {
        assertThrows(InvalidOrderRequestException.class, () -> service.submit(marketRequest(), key));
        verifyNoInteractions(submissions, references, orders);
        assertEquals(0, transactions.executions);
        assertEquals(0, generatedIds.get());
    }

    @Test
    void overlongKeyFailsBeforeOpeningTransaction() {
        assertThrows(InvalidOrderRequestException.class, () -> service.submit(marketRequest(), "x".repeat(129)));
        assertEquals(0, transactions.executions);
    }

    @Test
    void missingAccountDoesNotInsertAnOrder() {
        claimOwner(marketRequest());
        when(references.findAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.submit(marketRequest(), "key"));

        verify(references, never()).findInstrument(any());
        verifyNoInteractions(orders);
    }

    @Test
    void missingInstrumentDoesNotInsertAnOrder() {
        claimOwner(marketRequest());
        when(references.findAccount(ACCOUNT_ID)).thenReturn(Optional.of(ACCOUNT));
        when(references.findInstrument(INSTRUMENT_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.submit(marketRequest(), "key"));
        verifyNoInteractions(orders);
    }

    @Test
    void offStepTermsAreRejectedAfterReferenceLookupWithoutInserting() {
        SubmitOrder offStep = new SubmitOrder(ACCOUNT_ID, INSTRUMENT_ID, Side.BUY, OrderType.MARKET,
                decimal("0.15"), null);
        claimOwner(offStep);
        when(references.findAccount(ACCOUNT_ID)).thenReturn(Optional.of(ACCOUNT));
        when(references.findInstrument(INSTRUMENT_ID)).thenReturn(Optional.of(INSTRUMENT));

        assertThrows(InvalidOrderRequestException.class, () -> service.submit(offStep, "key"));
        verifyNoInteractions(orders);
        assertFalse(transactions.active);
    }

    @Test
    void getMissingOrderReportsNotFound() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.get(ORDER_ID));
    }

    @Test
    void getReturnsStoredVersion() {
        StoredOrder stored = new StoredOrder(acceptedOrder(), 12);
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(stored));
        assertSame(stored, service.get(ORDER_ID));
    }

    @Test
    void staleCancellationDoesNotApplyOrPersistCommand() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 3)));

        assertThrows(ConcurrentOrderUpdateException.class, () -> service.cancel(ORDER_ID, 2, "User request"));

        verify(orders, never()).update(any(), anyLong());
    }

    @Test
    void cancellationCarriesExpectedVersionAndPreservesHistory() {
        Order accepted = acceptedOrder();
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(accepted, 3)));
        when(orders.update(any(Order.class), eq(3L))).thenAnswer(call -> new StoredOrder(call.getArgument(0), 4));

        StoredOrder result = service.cancel(ORDER_ID, 3, "User request");

        assertEquals(4, result.version());
        assertEquals(OrderStatus.CANCELLED, result.order().status());
        assertEquals(accepted.events(), result.order().events().subList(0, 2));
        verify(orders).update(result.order(), 3);
        assertEquals(1, transactions.executions);
    }

    @Test
    void databaseVersionConflictPropagatesWithoutBlindRetry() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 3)));
        when(orders.update(any(Order.class), eq(3L))).thenThrow(new ConcurrentOrderUpdateException());

        assertThrows(ConcurrentOrderUpdateException.class, () -> service.cancel(ORDER_ID, 3, "User request"));

        verify(orders, times(1)).find(ORDER_ID);
        verify(orders, times(1)).update(any(Order.class), eq(3L));
    }

    @Test
    void cancellationClampsRegressingClockToLastLifecycleTimestamp() {
        Order accepted = acceptedOrder();
        clock.current = CANONICAL_NOW.minusSeconds(60);
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(accepted, 0)));
        when(orders.update(any(), eq(0L))).thenAnswer(call -> new StoredOrder(call.getArgument(0), 1));

        StoredOrder result = service.cancel(ORDER_ID, 0, "User request");

        assertEquals(CANONICAL_NOW, result.order().events().getLast().occurredAt());
    }

    @Test
    void executionBoundaryCanonicalizesSubmicrosecondTimestampBeforeDomainValidation() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 0)));
        when(orders.update(any(), eq(0L))).thenAnswer(call -> new StoredOrder(call.getArgument(0), 1));
        Execution report = new Execution(EXECUTION_ID, decimal("1"), decimal("99.99"), NOW);
        claimExecutionOwner();

        StoredOrder result = service.recordExecution(ORDER_ID, 0, report);

        assertEquals(OrderStatus.PARTIALLY_FILLED, result.order().status());
        assertEquals(CANONICAL_NOW, result.order().events().getLast().execution().executedAt());
        assertEquals(CANONICAL_NOW, result.order().events().getLast().occurredAt());
        assertEquals(EXECUTION_ID, result.order().events().getLast().execution().id());
    }

    @Test
    void staleExecutionDoesNotPersist() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 1)));
        Execution report = new Execution(EXECUTION_ID, decimal("1"), decimal("99.99"), CANONICAL_NOW);
        claimExecutionOwner();

        assertThrows(ConcurrentOrderUpdateException.class, () -> service.recordExecution(ORDER_ID, 0, report));
        verify(orders, never()).update(any(), anyLong());
    }

    @Test
    void listReturnsImmutableSnapshot() {
        List<StoredOrder> underlying = new ArrayList<>(List.of(new StoredOrder(acceptedOrder(), 0)));
        when(orders.list(ACCOUNT_ID, 20, 0)).thenReturn(underlying);

        List<StoredOrder> result = service.list(ACCOUNT_ID, 20, 0);
        underlying.clear();

        assertEquals(1, result.size());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void rejectsInvalidPaginationAndExpectedVersionBeforeDatabaseAccess() {
        assertThrows(InvalidOrderRequestException.class, () -> service.list(ACCOUNT_ID, 0, 0));
        assertThrows(InvalidOrderRequestException.class, () -> service.list(ACCOUNT_ID, 101, 0));
        assertThrows(InvalidOrderRequestException.class, () -> service.list(ACCOUNT_ID, 1, -1));
        assertThrows(InvalidOrderRequestException.class, () -> service.list(ACCOUNT_ID, 1, 10001));
        assertThrows(InvalidOrderRequestException.class, () -> service.cancel(ORDER_ID, -1, "User request"));
        assertThrows(InvalidOrderRequestException.class, () -> service.recordExecution(ORDER_ID, -1,
                new Execution(EXECUTION_ID, decimal("1"), decimal("100"), CANONICAL_NOW)));
        assertThrows(InvalidOrderRequestException.class, () -> service.get(null));
        assertThrows(InvalidOrderRequestException.class, () -> service.list(null, 1, 0));
        verifyNoInteractions(orders);
        assertEquals(0, transactions.executions);
    }

    @Test
    void missingRequestAndExecutionAreTypedValidationFailures() {
        assertThrows(InvalidOrderRequestException.class, () -> service.submit(null, "key"));
        assertThrows(InvalidOrderRequestException.class, () -> service.recordExecution(ORDER_ID, 0, null));
        verifyNoInteractions(submissions, references, orders);
        assertEquals(0, transactions.executions);
    }

    @Test
    void invalidCancellationReasonIsTypedValidationFailure() {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 0)));

        InvalidOrderRequestException failure = assertThrows(InvalidOrderRequestException.class,
                () -> service.cancel(ORDER_ID, 0, " "));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        verify(orders, never()).update(any(), anyLong());
    }

    @Test
    void cancellationOfTerminalOrderIsTypedBusinessConflict() {
        Order cancelled = acceptedOrder().cancel("User request", CANONICAL_NOW);
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(cancelled, 1)));

        OrderConflictException failure = assertThrows(OrderConflictException.class,
                () -> service.cancel(ORDER_ID, 1, "Second request"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        verify(orders, never()).update(any(), anyLong());
    }

    @Test
    void fillOfCancelledOrderIsTypedBusinessConflict() {
        Order cancelled = acceptedOrder().cancel("User request", CANONICAL_NOW);
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(cancelled, 1)));
        Execution report = new Execution(EXECUTION_ID, decimal("1"), decimal("100"), CANONICAL_NOW);
        claimExecutionOwner();

        assertThrows(OrderConflictException.class, () -> service.recordExecution(ORDER_ID, 1, report));
        verify(orders, never()).update(any(), anyLong());
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void submissionStoreFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        when(submissions.claim(ACCOUNT_ID, "key", RequestFingerprint.of(marketRequest()), ORDER_ID))
                .thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.submit(marketRequest(), "key")));
        verifyNoInteractions(references, orders);
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void referenceDataFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        claimOwner(marketRequest());
        when(references.findAccount(ACCOUNT_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.submit(marketRequest(), "key")));
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void orderReadFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        when(orders.find(ORDER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.get(ORDER_ID)));
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void orderInsertFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        claimOwner(marketRequest());
        when(references.findAccount(ACCOUNT_ID)).thenReturn(Optional.of(ACCOUNT));
        when(references.findInstrument(INSTRUMENT_ID)).thenReturn(Optional.of(INSTRUMENT));
        when(orders.insert(any())).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.submit(marketRequest(), "key")));
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void orderUpdateFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 0)));
        when(orders.update(any(), eq(0L))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.cancel(ORDER_ID, 0, "User request")));
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void transactionFailuresAreNotConvertedToBusinessErrors(RuntimeException failure) {
        UnitOfWork failingTransactions = new UnitOfWork() {
            @Override public <T> T execute(Supplier<T> work) { throw failure; }
        };
        OmsService failingService = new OmsService(references, orders, submissions, executions, failingTransactions,
                clock, () -> ORDER_ID);

        assertSame(failure, assertThrows(RuntimeException.class, () -> failingService.submit(marketRequest(), "key")));
        verifyNoInteractions(submissions, references, orders);
    }

    @Test
    void clockFailureIsNotConvertedToBusinessConflict() {
        Clock failingClock = mock(Clock.class);
        IllegalStateException internal = new IllegalStateException("Internal clock configuration");
        when(failingClock.instant()).thenThrow(internal);
        when(orders.find(ORDER_ID)).thenReturn(Optional.of(new StoredOrder(acceptedOrder(), 0)));
        OmsService failingService = new OmsService(references, orders, submissions, executions, transactions,
                failingClock, () -> ORDER_ID);

        assertSame(internal, assertThrows(IllegalStateException.class,
                () -> failingService.cancel(ORDER_ID, 0, "User request")));
        verify(orders, never()).update(any(), anyLong());
    }

    static Stream<RuntimeException> internalFailures() {
        return Stream.of(new IllegalArgumentException("Internal database argument"),
                new IllegalStateException("Internal database state"));
    }

    private void claimExecutionOwner() {
        when(executions.claimExecution(eq(ORDER_ID), any(Execution.class))).thenAnswer(call ->
                new ExecutionClaim(call.getArgument(0), call.getArgument(1), true));
    }

    private void claimOwner(SubmitOrder request) {
        when(submissions.claim(ACCOUNT_ID, "key", RequestFingerprint.of(request), ORDER_ID))
                .thenReturn(new SubmissionClaim(ORDER_ID, RequestFingerprint.of(request), true));
    }

    private static SubmitOrder marketRequest() {
        return new SubmitOrder(ACCOUNT_ID, INSTRUMENT_ID, Side.BUY, OrderType.MARKET, decimal("10"), null);
    }

    private static SubmitOrder limitRequest(String quantity, String price) {
        return new SubmitOrder(ACCOUNT_ID, INSTRUMENT_ID, Side.BUY, OrderType.LIMIT, decimal(quantity), decimal(price));
    }

    private static Order acceptedOrder() {
        return Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT, Side.BUY, OrderType.LIMIT,
                decimal("10"), decimal("100"), CANONICAL_NOW).accept(CANONICAL_NOW);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static final class RecordingUnitOfWork implements UnitOfWork {
        private boolean active;
        private int executions;

        @Override
        public <T> T execute(Supplier<T> work) {
            executions++;
            active = true;
            try {
                return work.get();
            } finally {
                active = false;
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) { this.current = current; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
