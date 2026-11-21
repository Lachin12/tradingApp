package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.*;
import io.github.lachin12.oms.domain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Real transactions and independent connections; no test-wide rollback transaction. */
@Testcontainers
@Timeout(30)
class JdbcOmsStoreIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.12-bookworm");

    private static final UUID ACCOUNT_ID = uuid(1);
    private static final UUID INSTRUMENT_ID = uuid(2);
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00.123456789Z");
    private static final Instant MICROS = Instant.parse("2026-01-01T09:00:00.123456Z");
    private static final AtomicLong IDS = new AtomicLong(100);
    private static JdbcTemplate jdbc;
    private static JdbcOmsStore store;
    private static SpringUnitOfWork transactions;
    private OmsService service;
    private ExecutorService workers;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcOmsStore(jdbc);
        transactions = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetData() {
        // Administrative fixture setup; application history UPDATE/DELETE remains forbidden.
        jdbc.execute("TRUNCATE outbox_events, order_events, submission_keys, orders, accounts, instruments CASCADE");
        jdbc.update("INSERT INTO accounts(id, name, active) VALUES (?, ?, true)", ACCOUNT_ID, "Test account");
        jdbc.update("INSERT INTO instruments(id, symbol, currency, tick_size, quantity_step) VALUES (?, ?, ?, ?, ?)",
                INSTRUMENT_ID, "TEST", "USD", decimal("0.01"), decimal("1"));
        service = newService(store, store);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() {
        workers.shutdownNow();
    }

    @Test
    void exactOrderHistoryAndPendingOutboxRoundTripInOneTransaction() {
        StoredOrder submitted = submit("round-trip");
        StoredOrder restored = service.get(submitted.order().id());

        assertEquals(0, restored.version());
        assertEquals(OrderStatus.ACCEPTED, restored.order().status());
        assertEquals(decimal("10.00000000"), restored.order().quantity());
        assertEquals(decimal("100.00000000"), restored.order().limitPrice().orElseThrow());
        assertEquals(decimal("0.00000000"), restored.order().filledQuantity());
        assertEquals(submitted.order().events(), restored.order().events());
        assertEquals(MICROS, restored.order().events().getFirst().occurredAt());
        assertEquals(List.of(OrderEventType.SUBMITTED, OrderEventType.ACCEPTED),
                restored.order().events().stream().map(OrderEvent::type).toList());
        assertEquals(1, count("orders"));
        assertEquals(1, count("submission_keys"));
        assertEquals(2, count("order_events"));
        assertEquals(2, count("outbox_events"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE published_at IS NULL AND attempts = 0", Integer.class));
    }

    @Test
    void equivalentRetriesReturnTheSameOrdersCurrentStateWithoutNewFacts() {
        StoredOrder original = submit("same-key");
        StoredOrder cancelled = service.cancel(original.order().id(), 0, "User request");
        SubmissionResult replay = service.submit(command("10.0000", "100.000000000"), "same-key");

        assertTrue(replay.replay());
        assertEquals(original.order().id(), replay.storedOrder().order().id());
        assertEquals(OrderStatus.CANCELLED, replay.storedOrder().order().status());
        assertEquals(cancelled.version(), replay.storedOrder().version());
        assertEquals(cancelled.order().events(), replay.storedOrder().order().events());
        assertEquals(1, count("orders"));
        assertEquals(3, count("order_events"));
        assertEquals(3, count("outbox_events"));
    }

    @Test
    void conflictingKeyPayloadDoesNotCreateAnotherOrder() {
        StoredOrder original = submit("conflict");

        assertThrows(IdempotencyConflictException.class,
                () -> service.submit(command("11", "100"), "conflict"));
        assertEquals(original.order().events(), service.get(original.order().id()).order().events());
        assertEquals(1, count("orders"));
        assertEquals(1, count("submission_keys"));
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void keyScopeIsAccountSpecific() {
        UUID otherAccount = uuid(3);
        jdbc.update("INSERT INTO accounts(id, name, active) VALUES (?, ?, true)", otherAccount, "Other account");
        StoredOrder first = submit("scoped");
        SubmissionResult second = service.submit(new SubmitOrder(otherAccount, INSTRUMENT_ID,
                Side.BUY, OrderType.LIMIT, decimal("10"), decimal("100")), "scoped");

        assertFalse(second.replay());
        assertNotEquals(first.order().id(), second.storedOrder().order().id());
        assertEquals(2, count("orders"));
        assertEquals(2, count("submission_keys"));
        assertEquals(1, service.list(ACCOUNT_ID, 10, 0).size());
        assertEquals(1, service.list(otherAccount, 10, 0).size());
    }

    @Test
    void simultaneousIdenticalSubmissionsCreateOneOrderAndOneInitialHistory() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        SubmissionStore coordinatedClaims = (account, key, fingerprint, proposedId) -> {
            await(start);
            return store.claim(account, key, fingerprint, proposedId);
        };
        OmsService concurrentService = newService(store, coordinatedClaims);
        Future<SubmissionResult> first = workers.submit(() -> concurrentService.submit(command("10", "100"), "parallel"));
        Future<SubmissionResult> second = workers.submit(() -> concurrentService.submit(command("10.0", "100.00"), "parallel"));

        SubmissionResult a = first.get(10, TimeUnit.SECONDS);
        SubmissionResult b = second.get(10, TimeUnit.SECONDS);
        assertEquals(a.storedOrder().order().id(), b.storedOrder().order().id());
        assertNotEquals(a.replay(), b.replay());
        assertEquals(1, count("orders"));
        assertEquals(1, count("submission_keys"));
        assertEquals(2, count("order_events"));
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void waitingSubmissionCanTakeOwnershipAfterOriginalClaimRollsBack() throws Exception {
        String fingerprint = RequestFingerprint.of(command("10", "100"));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Future<?> owner = workers.submit(() -> assertThrows(DeliberateRollback.class,
                () -> transactions.execute(() -> {
                    store.claim(ACCOUNT_ID, "rollback-owner", fingerprint, nextId());
                    claimed.countDown();
                    await(releaseOwner);
                    throw new DeliberateRollback();
                })));
        assertTrue(claimed.await(5, TimeUnit.SECONDS));
        OmsService contenderService = newService(store, (account, key, payloadFingerprint, proposedId) -> {
            jdbc.queryForObject("SELECT set_config('application_name', 'oms-waiting-claim', true)", String.class);
            return store.claim(account, key, payloadFingerprint, proposedId);
        });
        Future<SubmissionResult> contender = workers.submit(() ->
                contenderService.submit(command("10", "100"), "rollback-owner"));
        try {
            // Observe an actual database lock waiter, rather than assuming thread timing.
            awaitDatabaseWaiter("oms-waiting-claim");
        } finally {
            releaseOwner.countDown();
        }
        owner.get(10, TimeUnit.SECONDS);
        SubmissionResult result = contender.get(10, TimeUnit.SECONDS);
        assertFalse(result.replay());
        assertEquals(OrderStatus.ACCEPTED, result.storedOrder().order().status());
        assertEquals(1, count("orders"));
        assertEquals(1, count("submission_keys"));
    }

    @Test
    void invalidInstrumentTermsRollBackClaimAndAllowCorrectedRetry() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(command("1.5", "100"), "invalid-step"));
        assertEquals(0, count("submission_keys"));
        assertEquals(0, count("orders"));
        assertFalse(service.submit(command("2", "100"), "invalid-step").replay());
    }

    @Test
    void aDeferredOrderForeignKeyCannotLeaveAnOrphanSubmissionClaim() {
        assertThrows(RuntimeException.class, () -> transactions.execute(() ->
                store.claim(ACCOUNT_ID, "orphan", RequestFingerprint.of(command("10", "100")), nextId())));
        assertEquals(0, count("submission_keys"));
    }

    @Test
    void outboxInsertFailureRollsBackOrderClaimAndAllLifecycleEvents() {
        jdbc.execute("""
                CREATE FUNCTION test_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'deliberate outbox failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_outbox_failure BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION test_reject_outbox()");
        try {
            assertThrows(DataAccessException.class, () -> submit("outbox-failure"));
            assertEquals(0, count("orders"));
            assertEquals(0, count("submission_keys"));
            assertEquals(0, count("order_events"));
            assertEquals(0, count("outbox_events"));
        } finally {
            jdbc.execute("DROP TRIGGER test_outbox_failure ON outbox_events");
            jdbc.execute("DROP FUNCTION test_reject_outbox()");
        }
        assertFalse(service.submit(command("10", "100"), "outbox-failure").replay());
    }

    @Test
    void failedEventAppendRollsBackAnOtherwiseSuccessfulVersionedUpdate() {
        StoredOrder original = submit("update-rollback");
        jdbc.execute("""
                CREATE FUNCTION test_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'deliberate event failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_event_failure BEFORE INSERT ON order_events FOR EACH ROW EXECUTE FUNCTION test_reject_event()");
        try {
            assertThrows(DataAccessException.class,
                    () -> service.cancel(original.order().id(), 0, "User request"));
        } finally {
            jdbc.execute("DROP TRIGGER test_event_failure ON order_events");
            jdbc.execute("DROP FUNCTION test_reject_event()");
        }
        StoredOrder reloaded = service.get(original.order().id());
        assertEquals(0, reloaded.version());
        assertEquals(OrderStatus.ACCEPTED, reloaded.order().status());
        assertEquals(original.order().events(), reloaded.order().events());
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void twoConcurrentCancellationsCannotBothUpdateTheSameVersion() throws Exception {
        StoredOrder original = submit("cancel-race");
        CyclicBarrier readsComplete = new CyclicBarrier(2);
        Callable<Outcome> cancel = () -> capture(() -> transactions.execute(() -> {
            StoredOrder snapshot = store.find(original.order().id()).orElseThrow();
            await(readsComplete);
            return store.update(snapshot.order().cancel("User request", MICROS), snapshot.version());
        }));
        Future<Outcome> a = workers.submit(cancel);
        Future<Outcome> b = workers.submit(cancel);
        List<Outcome> outcomes = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

        assertEquals(1, outcomes.stream().filter(outcome -> outcome.failure() == null).count());
        assertEquals(1, outcomes.stream().filter(outcome -> outcome.failure() instanceof ConcurrentOrderUpdateException).count());
        StoredOrder cancelled = service.get(original.order().id());
        assertEquals(1, cancelled.version());
        assertEquals(OrderStatus.CANCELLED, cancelled.order().status());
        assertEquals(3, count("order_events"));
        assertEquals(3, count("outbox_events"));
    }

    @Test
    void blockedSqlCompareAndSwapRechecksVersionAfterTheWinnerCommits() throws Exception {
        StoredOrder original = submit("sql-cas-waiter");
        CountDownLatch winnerUpdated = new CountDownLatch(1);
        CountDownLatch commitWinner = new CountDownLatch(1);
        Future<StoredOrder> winner = workers.submit(() -> transactions.execute(() -> {
            StoredOrder updated = store.update(original.order().cancel("Winning cancellation", MICROS), 0);
            winnerUpdated.countDown();
            await(commitWinner);
            return updated;
        }));
        assertTrue(winnerUpdated.await(5, TimeUnit.SECONDS));
        Future<Outcome> loser = workers.submit(() -> capture(() -> transactions.execute(() -> {
            jdbc.queryForObject("SELECT set_config('application_name', 'oms-waiting-cas', true)", String.class);
            // The winner is still uncommitted: the adapter's preliminary SELECT sees
            // version 0. Its UPDATE must then wait and recheck the SQL predicate.
            return store.update(original.order().cancel("Losing cancellation", MICROS), 0);
        })));
        try {
            awaitDatabaseWaiter("oms-waiting-cas");
        } finally {
            commitWinner.countDown();
        }
        assertEquals(1, winner.get(10, TimeUnit.SECONDS).version());
        assertInstanceOf(ConcurrentOrderUpdateException.class, loser.get(10, TimeUnit.SECONDS).failure());
        StoredOrder reloaded = service.get(original.order().id());
        assertEquals(1, reloaded.version());
        assertEquals(OrderStatus.CANCELLED, reloaded.order().status());
        assertEquals("Winning cancellation", reloaded.order().events().getLast().reason());
        assertEquals(3, count("order_events"));
        assertEquals(3, count("outbox_events"));
    }

    enum Winner { FILL, CANCEL }

    @ParameterizedTest
    @EnumSource(Winner.class)
    void fullFillAndCancelRacesPreserveTheCommittedWinner(Winner winner) throws Exception {
        StoredOrder original = submit("full-race");
        Execution report = new Execution(uuid(500), decimal("10"), decimal("99.99"), MICROS);
        UnaryOperator<Order> fill = order -> order.fill(report, MICROS);
        UnaryOperator<Order> cancel = order -> order.cancel("User request", MICROS);
        orderedRace(original, winner == Winner.FILL ? fill : cancel, winner == Winner.FILL ? cancel : fill);

        StoredOrder committed = service.get(original.order().id());
        assertEquals(1, committed.version());
        assertEquals(winner == Winner.FILL ? OrderStatus.FILLED : OrderStatus.CANCELLED, committed.order().status());
        assertEquals(decimal(winner == Winner.FILL ? "10.00000000" : "0.00000000"), committed.order().filledQuantity());
        assertEquals(3, count("order_events"));
        assertEquals(3, count("outbox_events"));
        if (winner == Winner.FILL) {
            assertThrows(IllegalStateException.class,
                    () -> service.cancel(original.order().id(), 1, "Too late"));
        } else {
            assertThrows(IllegalStateException.class,
                    () -> service.recordExecution(original.order().id(), 1, report));
        }
    }

    @ParameterizedTest
    @EnumSource(Winner.class)
    void partialFillAndCancelRacesRequireReloadAndRevalidation(Winner winner) throws Exception {
        StoredOrder original = submit("partial-race");
        Execution report = new Execution(uuid(501), decimal("4"), decimal("100"), MICROS);
        UnaryOperator<Order> fill = order -> order.fill(report, MICROS);
        UnaryOperator<Order> cancel = order -> order.cancel("User request", MICROS);
        orderedRace(original, winner == Winner.FILL ? fill : cancel, winner == Winner.FILL ? cancel : fill);

        if (winner == Winner.FILL) {
            StoredOrder cancelled = service.cancel(original.order().id(), 1, "Reloaded request");
            assertEquals(OrderStatus.CANCELLED, cancelled.order().status());
            assertEquals(decimal("4.00000000"), cancelled.order().filledQuantity());
            assertEquals(2, cancelled.version());
            assertEquals(4, count("order_events"));
            assertEquals(4, count("outbox_events"));
        } else {
            assertThrows(IllegalStateException.class,
                    () -> service.recordExecution(original.order().id(), 1, report));
            assertEquals(decimal("0.00000000"), service.get(original.order().id()).order().filledQuantity());
            assertEquals(3, count("order_events"));
        }
    }

    @Test
    void duplicateExecutionIdentityAcrossOrdersRollsBackTheSecondUpdate() {
        StoredOrder first = submit("execution-a");
        StoredOrder second = submit("execution-b");
        Execution report = new Execution(uuid(600), decimal("1"), decimal("100"), MICROS);
        service.recordExecution(first.order().id(), 0, report);

        assertThrows(DataAccessException.class,
                () -> service.recordExecution(second.order().id(), 0, report));
        StoredOrder unchanged = service.get(second.order().id());
        assertEquals(0, unchanged.version());
        assertEquals(OrderStatus.ACCEPTED, unchanged.order().status());
        assertEquals(second.order().events(), unchanged.order().events());
        assertEquals(5, count("order_events"));
        assertEquals(5, count("outbox_events"));
    }

    @Test
    void executionTimestampIsNormalizedBeforePersistenceAndReplaysExactly() {
        StoredOrder original = submit("execution-time");
        Execution report = new Execution(uuid(601), decimal("1"), decimal("100"), NOW.minusSeconds(1));
        StoredOrder updated = service.recordExecution(original.order().id(), 0, report);
        StoredOrder reloaded = service.get(original.order().id());
        assertEquals(updated.order().events(), reloaded.order().events());
        assertEquals(MICROS.minusSeconds(1), reloaded.order().events().getLast().execution().executedAt());
    }

    @Test
    void originalReferenceSnapshotsSurviveLaterReferenceDataChanges() {
        StoredOrder original = service.submit(command("3", "100.01"), "reference-snapshot").storedOrder();
        jdbc.update("UPDATE accounts SET active = false, name = 'Renamed' WHERE id = ?", ACCOUNT_ID);
        jdbc.update("UPDATE instruments SET tick_size = 5, quantity_step = 7, symbol = 'CHANGED' WHERE id = ?", INSTRUMENT_ID);

        StoredOrder restored = service.get(original.order().id());
        assertEquals(original.order().account(), restored.order().account());
        assertEquals(original.order().instrument(), restored.order().instrument());
        assertEquals(original.order().events(), restored.order().events());
        assertEquals(OrderStatus.CANCELLED,
                service.cancel(original.order().id(), 0, "Original terms retained").order().status());
    }

    @Test
    void databaseRejectsHistoryUpdatesAndDeletes() {
        StoredOrder original = submit("immutable-history");
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE order_events SET occurred_at = occurred_at WHERE order_id = ?", original.order().id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM order_events WHERE order_id = ?", original.order().id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE outbox_events SET created_at = created_at WHERE order_id = ?", original.order().id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM outbox_events WHERE order_id = ?", original.order().id()));
        assertEquals(original.order().events(), service.get(original.order().id()).order().events());
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void deliveryBookkeepingCanChangeWithoutRewritingBusinessFacts() {
        StoredOrder original = submit("delivery-state");
        String eventId = original.order().events().getFirst().id();
        assertEquals(1, jdbc.update("UPDATE outbox_delivery SET attempts = attempts + 1, last_error = ? WHERE event_id = ?",
                "No publisher configured in Phase 1", eventId));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?", Integer.class, eventId));
        assertEquals(original.order().events(), service.get(original.order().id()).order().events());
        assertEquals(2, count("outbox_events"));
    }

    @Test
    void readsStayCoherentWhileAnotherTransactionCommitsFills() throws Exception {
        StoredOrder original = submit("coherent-read");
        CyclicBarrier start = new CyclicBarrier(2);
        Future<?> writer = workers.submit(() -> {
            await(start);
            for (int i = 0; i < 9; i++) {
                service.recordExecution(original.order().id(), i,
                        new Execution(uuid(700 + i), decimal("1"), decimal("100"), MICROS));
            }
        });
        Future<?> reader = workers.submit(() -> {
            await(start);
            for (int i = 0; i < 30; i++) {
                StoredOrder restored = service.get(original.order().id());
                assertEquals(restored.order().status(), restored.order().events().getLast().status());
                assertEquals(restored.order().filledQuantity(), restored.order().events().getLast().cumulativeQuantity());
                assertEquals(restored.version() + 2, restored.order().events().size());
            }
        });
        writer.get(10, TimeUnit.SECONDS);
        reader.get(10, TimeUnit.SECONDS);
        assertEquals(decimal("9.00000000"), service.get(original.order().id()).order().filledQuantity());
        assertEquals(11, count("outbox_events"));
    }

    private void orderedRace(StoredOrder original, UnaryOperator<Order> winningMutation,
                             UnaryOperator<Order> losingMutation) throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        Future<StoredOrder> winner = workers.submit(() -> {
            try {
                return transactions.execute(() -> {
                    StoredOrder snapshot = store.find(original.order().id()).orElseThrow();
                    await(bothRead);
                    return store.update(winningMutation.apply(snapshot.order()), snapshot.version());
                });
            } finally {
                winnerCommitted.countDown();
            }
        });
        Future<Outcome> loser = workers.submit(() -> capture(() -> transactions.execute(() -> {
            StoredOrder snapshot = store.find(original.order().id()).orElseThrow();
            await(bothRead);
            await(winnerCommitted);
            return store.update(losingMutation.apply(snapshot.order()), snapshot.version());
        })));
        assertEquals(1, winner.get(10, TimeUnit.SECONDS).version());
        assertInstanceOf(ConcurrentOrderUpdateException.class, loser.get(10, TimeUnit.SECONDS).failure());
    }

    private static void awaitDatabaseWaiter(String applicationName) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name = ? AND cardinality(pg_blocking_pids(pid)) > 0)
                    """, Boolean.class, applicationName);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.yield();
        }
        fail("Contending transaction never became a database lock waiter");
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        catch (BrokenBarrierException | TimeoutException ex) { throw new IllegalStateException(ex); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting transaction coordination");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private OmsService newService(OrderStore orderStore, SubmissionStore submissionStore) {
        return new OmsService(store, orderStore, submissionStore, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), JdbcOmsStoreIT::nextId);
    }

    private StoredOrder submit(String key) { return service.submit(command("10", "100"), key).storedOrder(); }
    private static SubmitOrder command(String quantity, String price) {
        return new SubmitOrder(ACCOUNT_ID, INSTRUMENT_ID, Side.BUY, OrderType.LIMIT, decimal(quantity), decimal(price));
    }
    private static UUID nextId() { return uuid(IDS.getAndIncrement()); }
    private static UUID uuid(long id) { return new UUID(0, id); }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private static Outcome capture(Callable<StoredOrder> action) {
        try { return new Outcome(action.call(), null); }
        catch (Exception ex) { return new Outcome(null, ex); }
    }
    private record Outcome(StoredOrder result, Exception failure) { }
    private static final class DeliberateRollback extends RuntimeException { }
}
