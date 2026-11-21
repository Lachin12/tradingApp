package io.github.lachin12.oms.application.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.lachin12.oms.application.UnitOfWork;
import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final RetryPolicy RETRY = new RetryPolicy(Duration.ofMillis(100), Duration.ofSeconds(10));
    private static final UUID TOKEN_1 = new UUID(0, 1);
    private static final UUID TOKEN_2 = new UUID(0, 2);
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    @Mock private OutboxStore store;
    @Mock private EventPublisher publisher;
    private final TrackingTransactions transactions = new TrackingTransactions();
    private final AtomicInteger tokenSequence = new AtomicInteger();
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OutboxDispatcher(store, transactions, publisher,
                () -> new UUID(0, tokenSequence.incrementAndGet()), LEASE, RETRY, () -> transactions.active);
    }

    @Test
    void noEligibleMessagesEndsRunWithoutPublishingOrCompletionWrite() throws Exception {
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of());

        assertEquals(new DispatchResult(0, 0, 0, 0), dispatcher.dispatch(100));

        verifyNoInteractions(publisher);
        verify(store, never()).acknowledge(any());
        verify(store, never()).reschedule(any(), any());
        assertEquals(1, tokenSequence.get());
        assertEquals(1, transactions.commits);
    }

    @Test
    void publicationRunsAfterClaimCommitAndOutsideAnyTransaction() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenAnswer(call -> {
            assertTrue(transactions.active);
            assertEquals(0, transactions.commits);
            return List.of(claim);
        });
        doAnswer(call -> {
            assertFalse(transactions.active);
            assertEquals(1, transactions.commits);
            return null;
        }).when(publisher).publish(claim.message());
        when(store.acknowledge(claim)).thenAnswer(call -> {
            assertTrue(transactions.active);
            assertEquals(1, transactions.commits);
            return true;
        });

        assertEquals(new DispatchResult(1, 1, 0, 0), dispatcher.dispatch(1));

        assertEquals(2, transactions.commits);
        verify(store, never()).reschedule(any(), any());
    }

    @Test
    void eachMessageGetsAFreshClaimImmediatelyBeforeItsPublication() throws Exception {
        DeliveryClaim first = claim(message(10), TOKEN_1, 1);
        DeliveryClaim second = claim(message(20), TOKEN_2, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(first));
        when(store.claim(1, LEASE, TOKEN_2)).thenReturn(List.of(second));
        when(store.acknowledge(any(DeliveryClaim.class))).thenAnswer(call -> {
            DeliveryClaim acknowledged = call.getArgument(0);
            assertTrue(acknowledged == first || acknowledged == second, "Unexpected acknowledgement claim");
            assertTrue(transactions.active);
            return true;
        });
        doAnswer(call -> {
            OutboxMessage publication = call.getArgument(0);
            assertTrue(publication == first.message() || publication == second.message(),
                    "Unexpected publication message");
            assertFalse(transactions.active);
            assertEquals(publication == first.message() ? 1 : 3, transactions.commits);
            return null;
        }).when(publisher).publish(any(OutboxMessage.class));

        assertEquals(new DispatchResult(2, 2, 0, 0), dispatcher.dispatch(2));

        InOrder sequence = inOrder(store, publisher);
        sequence.verify(store).claim(1, LEASE, TOKEN_1);
        sequence.verify(publisher).publish(first.message());
        sequence.verify(store).acknowledge(first);
        sequence.verify(store).claim(1, LEASE, TOKEN_2);
        sequence.verify(publisher).publish(second.message());
        sequence.verify(store).acknowledge(second);
        sequence.verifyNoMoreInteractions();
        assertEquals(2, tokenSequence.get());
    }

    @ParameterizedTest
    @MethodSource("publicationFailures")
    void onlyPublicationExceptionsTriggerRetryWithAttemptSpecificDelay(Exception failure) throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 3);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        doThrow(failure).when(publisher).publish(claim.message());
        when(store.reschedule(claim, Duration.ofMillis(400))).thenAnswer(call -> {
            assertTrue(transactions.active);
            assertEquals(1, transactions.commits);
            return true;
        });

        assertEquals(new DispatchResult(1, 0, 1, 0), dispatcher.dispatch(1));

        verify(store, never()).acknowledge(any());
        assertEquals(2, transactions.commits);
    }

    static Stream<Exception> publicationFailures() {
        return Stream.of(new IOException("Transport unavailable"), new IllegalStateException("Transport disconnected"));
    }

    @Test
    void failedDeliveryDoesNotPreventAnotherOrdersDeliveryInSameRun() throws Exception {
        DeliveryClaim first = claim(message(10), TOKEN_1, 1);
        DeliveryClaim second = claim(message(20), TOKEN_2, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(first));
        when(store.claim(1, LEASE, TOKEN_2)).thenReturn(List.of(second));
        doThrow(new IOException("Temporary failure")).when(publisher).publish(first.message());
        when(store.reschedule(first, Duration.ofMillis(100))).thenReturn(true);
        when(store.acknowledge(second)).thenReturn(true);

        assertEquals(new DispatchResult(2, 1, 1, 0), dispatcher.dispatch(2));

        verify(publisher).publish(second.message());
        verify(store, never()).acknowledge(first);
    }

    @Test
    void successfulPublishWithLostAcknowledgementLeaseCountsAsLeaseLost() {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        when(store.acknowledge(claim)).thenReturn(false);

        assertEquals(new DispatchResult(1, 0, 0, 1), dispatcher.dispatch(1));

        verify(store, never()).reschedule(any(), any());
    }

    @Test
    void failedPublishWithLostRetryLeaseCountsAsLeaseLost() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        doThrow(new IOException("Transport failure")).when(publisher).publish(claim.message());
        when(store.reschedule(claim, Duration.ofMillis(100))).thenReturn(false);

        assertEquals(new DispatchResult(1, 0, 0, 1), dispatcher.dispatch(1));

        verify(store, never()).acknowledge(any());
    }

    @Test
    void interruptionRestoresFlagAndLeavesClaimForLeaseRecovery() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        InterruptedException interruption = new InterruptedException("Stop requested");
        doThrow(interruption).when(publisher).publish(claim.message());
        assertFalse(Thread.currentThread().isInterrupted());

        try {
            OutboxDispatchInterruptedException failure = assertThrows(OutboxDispatchInterruptedException.class,
                    () -> dispatcher.dispatch(10));
            assertSame(interruption, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(store, never()).acknowledge(any());
            verify(store, never()).reschedule(any(), any());
            assertEquals(1, transactions.commits);
            assertEquals(1, tokenSequence.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void publisherErrorsPropagateAndLeaveClaimForRecovery() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        AssertionError fatal = new AssertionError("Fatal publisher error");
        doThrow(fatal).when(publisher).publish(claim.message());

        assertSame(fatal, assertThrows(AssertionError.class, () -> dispatcher.dispatch(1)));

        verify(store, never()).acknowledge(any());
        verify(store, never()).reschedule(any(), any());
    }

    @Test
    void claimFailurePropagatesUnchangedWithoutPublishing() {
        IllegalStateException internal = new IllegalStateException("Claim storage failure");
        when(store.claim(1, LEASE, TOKEN_1)).thenThrow(internal);

        assertSame(internal, assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(1)));

        verifyNoInteractions(publisher);
        assertEquals(0, transactions.commits);
    }

    @Test
    void claimCommitFailurePreventsPublication() {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        IllegalStateException internal = new IllegalStateException("Claim commit failed");
        transactions.failAt = 1;
        transactions.failure = internal;

        assertSame(internal, assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(1)));

        verifyNoInteractions(publisher);
        assertEquals(0, transactions.commits);
    }

    @Test
    void acknowledgementFailurePropagatesWithoutRetryingPublicationOrRescheduling() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        IllegalArgumentException internal = new IllegalArgumentException("Acknowledgement storage failure");
        when(store.acknowledge(claim)).thenThrow(internal);

        assertSame(internal, assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(10)));

        verify(publisher, times(1)).publish(claim.message());
        verify(store, never()).reschedule(any(), any());
        assertEquals(1, tokenSequence.get());
    }

    @Test
    void acknowledgementCommitFailurePropagatesWithoutRescheduling() {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        when(store.acknowledge(claim)).thenReturn(true);
        IllegalStateException internal = new IllegalStateException("Acknowledgement commit failed");
        transactions.failAt = 2;
        transactions.failure = internal;

        assertSame(internal, assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(1)));

        verify(store, never()).reschedule(any(), any());
        assertEquals(1, transactions.commits);
    }

    @Test
    void retryStorageFailurePropagatesUnchangedWithoutAcknowledgement() throws Exception {
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        doThrow(new IOException("Transport failure")).when(publisher).publish(claim.message());
        IllegalStateException internal = new IllegalStateException("Retry storage failure");
        when(store.reschedule(claim, Duration.ofMillis(100))).thenThrow(internal);

        assertSame(internal, assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(10)));

        verify(store, never()).acknowledge(any());
        assertEquals(1, tokenSequence.get());
    }

    @Test
    void refusesAdapterBatchClaimBeforeCommittingIt() {
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(
                claim(message(10), TOKEN_1, 1), claim(message(20), TOKEN_1, 1)));

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(1));

        verifyNoInteractions(publisher);
        assertEquals(0, transactions.commits);
    }

    @Test
    void rejectsInvocationInsideAmbientTransactionBeforeClaiming() {
        OutboxDispatcher ambientDispatcher = new OutboxDispatcher(store, transactions, publisher,
                () -> TOKEN_1, LEASE, RETRY, () -> true);

        assertThrows(IllegalStateException.class, () -> ambientDispatcher.dispatch(1));

        verifyNoInteractions(store, publisher);
        assertEquals(0, transactions.executions);
    }

    @Test
    void refusesPublicationWhenTransactionAdapterLeavesAmbientTransactionActive() {
        AtomicBoolean active = new AtomicBoolean();
        UnitOfWork incorrectlyJoiningTransactions = new UnitOfWork() {
            @Override public <T> T execute(Supplier<T> work) {
                T result = work.get();
                active.set(true);
                return result;
            }
        };
        DeliveryClaim claim = claim(message(10), TOKEN_1, 1);
        when(store.claim(1, LEASE, TOKEN_1)).thenReturn(List.of(claim));
        OutboxDispatcher unsafeDispatcher = new OutboxDispatcher(store, incorrectlyJoiningTransactions,
                publisher, () -> TOKEN_1, LEASE, RETRY, active::get);

        assertThrows(IllegalStateException.class, () -> unsafeDispatcher.dispatch(1));

        verifyNoInteractions(publisher);
        verify(store, never()).acknowledge(any());
        verify(store, never()).reschedule(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void invalidRunLimitsFailBeforeAccessingStoreOrGeneratingTokens(int limit) {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(limit));
        verifyNoInteractions(store, publisher);
        assertEquals(0, tokenSequence.get());
    }

    @Test
    void reachesRunLimitWithoutClaimingAnExtraMessage() {
        when(store.claim(eq(1), eq(LEASE), any(UUID.class))).thenAnswer(call -> List.of(
                claim(message(10), call.getArgument(2), 1)));
        when(store.acknowledge(any())).thenReturn(true);

        assertEquals(new DispatchResult(100, 100, 0, 0), dispatcher.dispatch(100));

        assertEquals(100, tokenSequence.get());
        verify(store, times(100)).claim(eq(1), eq(LEASE), any(UUID.class));
    }

    @ParameterizedTest
    @MethodSource("invalidLeases")
    void rejectsInvalidLeaseConfiguration(Duration lease) {
        assertThrows(IllegalArgumentException.class, () -> new OutboxDispatcher(store, transactions, publisher,
                () -> TOKEN_1, lease, RETRY, () -> false));
    }

    static Stream<Duration> invalidLeases() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(999),
                Duration.ofMinutes(5).plusMillis(1), Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE));
    }

    private static DeliveryClaim claim(OutboxMessage message, UUID token, int attempt) {
        return new DeliveryClaim(message, token, attempt, NOW.plus(LEASE));
    }

    static OutboxMessage message(int identity) {
        Account account = new Account(new UUID(0, 100), "Simulation account", true);
        Instrument instrument = new Instrument(new UUID(0, 200), "ACME", Currency.getInstance("USD"),
                new BigDecimal("0.01"), new BigDecimal("0.1"));
        Order order = Order.submit(new UUID(0, identity), account, instrument, Side.BUY, OrderType.MARKET,
                new BigDecimal("1"), null, NOW);
        return new OutboxMessage(order.events().getFirst(), 1);
    }

    private static final class TrackingTransactions implements UnitOfWork {
        private boolean active;
        private int executions;
        private int commits;
        private int failAt;
        private RuntimeException failure;

        @Override public <T> T execute(Supplier<T> work) {
            assertFalse(active, "Unexpected nested transaction");
            executions++;
            active = true;
            try {
                T result = work.get();
                if (executions == failAt) throw failure;
                commits++;
                return result;
            } finally {
                active = false;
            }
        }
    }
}
